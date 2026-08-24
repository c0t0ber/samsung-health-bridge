package com.roktober.samsunghealthbridge.sheets

import com.roktober.samsunghealthbridge.model.RawHealthRecord
import com.roktober.samsunghealthbridge.model.SheetRow
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class SpreadsheetCreationResult(val spreadsheetId: String)

data class SheetSyncResult(
    val spreadsheetId: String,
    val readbackRows: List<SheetRow>,
)

/** A normalized Health Connect record stored in the diagnostic Raw tab. */
data class RawSheetSyncResult(
    val spreadsheetId: String,
    val readbackRows: List<RawHealthRecord>,
)

class SheetsApi(
    private val endpoint: String = "https://sheets.googleapis.com/v4",
) {
    private val requestMutex = Mutex()

    /** Creates exactly one spreadsheet containing exactly one tab and returns its ID immediately. */
    suspend fun createSpreadsheet(accessToken: String): SpreadsheetCreationResult =
        requestMutex.withLock {
            val body =
                JSONObject()
                    .put("properties", JSONObject().put("title", SPREADSHEET_TITLE))
                    .put(
                        "sheets",
                        JSONArray().put(JSONObject().put("properties", JSONObject().put("title", TAB_NAME))),
                    )
            val response = request("POST", "/spreadsheets", accessToken, body)
            val spreadsheetId = response.optString("spreadsheetId").takeIf(String::isNotBlank)
                ?: throw SheetsApiException("Google Sheets did not return a spreadsheet ID")
            SpreadsheetCreationResult(spreadsheetId)
        }

    /**
     * Idempotently installs the header after the caller has durably stored [spreadsheetId].
     * An empty header is initialized; any non-empty header must match the schema exactly.
     */
    suspend fun initializeSpreadsheet(accessToken: String, spreadsheetId: String) {
        requestMutex.withLock {
            validateSpreadsheetId(spreadsheetId)
            val header = readHeader(accessToken, spreadsheetId)
            if (header.isEmpty()) {
                writeHeader(accessToken, spreadsheetId)
            } else if (header != SheetRow.HEADER) {
                throw SheetsApiException("The Daily sheet header is incompatible")
            }
            validateHeader(accessToken, spreadsheetId)
        }
    }

    /**
     * Idempotently adds the diagnostic Raw tab to an existing spreadsheet and installs its exact
     * schema. The method never creates another spreadsheet.
     */
    suspend fun initializeRawSheet(accessToken: String, spreadsheetId: String) {
        requestMutex.withLock {
            validateSpreadsheetId(spreadsheetId)
            ensureRawSheet(accessToken, spreadsheetId)
            validateRawHeader(accessToken, spreadsheetId)
        }
    }

    /** Returns every valid record currently stored in the Raw tab. */
    suspend fun readRawSheetRows(
        accessToken: String,
        spreadsheetId: String,
    ): List<RawHealthRecord> =
        requestMutex.withLock {
            validateSpreadsheetId(spreadsheetId)
            ensureRawSheet(accessToken, spreadsheetId)
            validateRawHeader(accessToken, spreadsheetId)
            readRawSheetCells(accessToken, spreadsheetId).mapIndexed { index, cells ->
                parseRawSheetRow(cells, index + FIRST_DATA_ROW)
            }
        }

    /**
     * Updates records with known record keys and appends missing records. Incoming and existing
     * duplicate keys are rejected before the first values write. A retry after a partial network
     * failure is safe because committed appends become updates on the next attempt.
     */
    suspend fun upsertRawRows(
        accessToken: String,
        spreadsheetId: String,
        rows: List<RawHealthRecord>,
    ): RawSheetSyncResult =
        withContext(Dispatchers.Default) {
            requestMutex.withLock {
                validateSpreadsheetId(spreadsheetId)
                ensureRawSheet(accessToken, spreadsheetId)
                validateRawHeader(accessToken, spreadsheetId)

            val existingCells = readRawSheetCells(accessToken, spreadsheetId)
            val plan = RawUpsertPlanner.plan(existingCells, rows)
            val updateChunks = plan.updates.rawWriteChunks { it.row.toApiCells() }
            val appendChunks = plan.appends.rawWriteChunks { it.toApiCells() }
            updateChunks.forEach { updates ->
                batchUpdateRaw(accessToken, spreadsheetId, updates)
            }
            appendChunks.forEach { appends ->
                appendRaw(accessToken, spreadsheetId, appends)
            }

            val expectedByKey = rows.associateBy { it.recordKey }
            val allReadback =
                readRawSheetCells(accessToken, spreadsheetId).mapIndexed { index, cells ->
                    parseRawSheetRow(cells, index + FIRST_DATA_ROW)
                }
            val duplicateKey =
                allReadback.groupingBy { it.recordKey }.eachCount().entries
                    .firstOrNull { it.value > 1 }?.key
            if (duplicateKey != null) {
                throw DuplicateRawRecordKeyException(duplicateKey)
            }
            val readback = allReadback.filter { it.recordKey in expectedByKey }
            val actualByKey = readback.associateBy { it.recordKey }
            if (actualByKey.keys != expectedByKey.keys ||
                expectedByKey.any { (key, expected) ->
                    actualByKey[key]?.let { actual -> !rawRowsMatch(expected, actual) } ?: true
                }
            ) {
                throw SheetsApiException("Google Sheets readback did not match the requested Raw rows")
            }
                RawSheetSyncResult(
                    spreadsheetId = spreadsheetId,
                    readbackRows = readback.sortedBy { it.recordKey },
                )
            }
        }

    /** Validates the configured file and returns all parsable Daily data rows. */
    suspend fun readRows(accessToken: String, spreadsheetId: String): List<SheetRow> =
        requestMutex.withLock {
            validateSpreadsheetId(spreadsheetId)
            validateHeader(accessToken, spreadsheetId)
            readRawRows(accessToken, spreadsheetId).mapIndexed { index, cells ->
                parseSheetRow(cells, index + FIRST_DATA_ROW)
            }
        }

    /**
     * Updates existing dates, appends missing dates, then reads the requested dates back and
     * verifies that the stored values exactly match the input rows.
     */
    suspend fun upsertRows(
        accessToken: String,
        spreadsheetId: String,
        rows: List<SheetRow>,
    ): SheetSyncResult =
        requestMutex.withLock {
            validateSpreadsheetId(spreadsheetId)
            require(rows.isNotEmpty()) { "At least one daily row is required" }
            validateHeader(accessToken, spreadsheetId)

            val existingRows = readRawRows(accessToken, spreadsheetId)
            val plan = UpsertPlanner.plan(existingRows, rows)
            if (plan.updates.isNotEmpty()) {
                batchUpdate(accessToken, spreadsheetId, plan.updates)
            }
            if (plan.appends.isNotEmpty()) {
                append(accessToken, spreadsheetId, plan.appends)
            }

            val expectedByDate = rows.associateBy { it.date }
            val allReadback =
                readRawRows(accessToken, spreadsheetId)
                    .mapIndexed { index, cells -> parseSheetRow(cells, index + FIRST_DATA_ROW) }
            val duplicateDate =
                allReadback.groupingBy { it.date }.eachCount().entries.firstOrNull { it.value > 1 }?.key
            if (duplicateDate != null) {
                throw DuplicateSheetDateException(duplicateDate)
            }
            val readback =
                allReadback
                    .filter { it.date in expectedByDate }
                    .sortedBy { it.date }
            val actualByDate = readback.associateBy { it.date }
            if (actualByDate.keys != expectedByDate.keys ||
                expectedByDate.any { (date, expected) -> actualByDate[date] != expected }
            ) {
                throw SheetsApiException("Google Sheets readback did not match the requested daily rows")
            }
            SheetSyncResult(spreadsheetId = spreadsheetId, readbackRows = readback)
        }

    private suspend fun writeHeader(accessToken: String, spreadsheetId: String) {
        val body = valueRange(listOf(SheetRow.HEADER))
        request(
            method = "PUT",
            path = "/spreadsheets/${encodePath(spreadsheetId)}/values/${encodePath(HEADER_RANGE)}" +
                "?valueInputOption=RAW",
            accessToken = accessToken,
            body = body,
        )
    }

    private suspend fun ensureRawSheet(accessToken: String, spreadsheetId: String) {
        if (!hasSheet(accessToken, spreadsheetId, RAW_TAB_NAME)) {
            val body =
                JSONObject().put(
                    "requests",
                    JSONArray().put(
                        JSONObject().put(
                            "addSheet",
                            JSONObject().put(
                                "properties",
                                JSONObject().put("title", RAW_TAB_NAME),
                            ),
                        ),
                    ),
                )
            try {
                request(
                    "POST",
                    "/spreadsheets/${encodePath(spreadsheetId)}:batchUpdate",
                    accessToken,
                    body,
                )
            } catch (error: SheetsApiException) {
                // Another process may have created the tab after our metadata read.
                if (!hasSheet(accessToken, spreadsheetId, RAW_TAB_NAME)) throw error
            }
        }

        val header = readRawHeader(accessToken, spreadsheetId)
        if (header.isEmpty()) {
            writeRawHeader(accessToken, spreadsheetId)
        } else if (header != RawHealthRecord.HEADER) {
            throw SheetsApiException("The Raw sheet header is incompatible")
        }
    }

    private suspend fun hasSheet(
        accessToken: String,
        spreadsheetId: String,
        title: String,
    ): Boolean {
        val response =
            request(
                "GET",
                "/spreadsheets/${encodePath(spreadsheetId)}?fields=sheets.properties.title",
                accessToken,
            )
        val sheets = response.optJSONArray("sheets") ?: return false
        return (0 until sheets.length()).any { index ->
            sheets.optJSONObject(index)?.optJSONObject("properties")?.optString("title") == title
        }
    }

    private suspend fun writeRawHeader(accessToken: String, spreadsheetId: String) {
        request(
            method = "PUT",
            path = "/spreadsheets/${encodePath(spreadsheetId)}/values/${encodePath(RAW_HEADER_RANGE)}" +
                "?valueInputOption=RAW",
            accessToken = accessToken,
            body = valueRange(listOf(RawHealthRecord.HEADER)),
        )
    }

    private suspend fun validateRawHeader(accessToken: String, spreadsheetId: String) {
        if (readRawHeader(accessToken, spreadsheetId) != RawHealthRecord.HEADER) {
            throw SheetsApiException("The Raw sheet header is missing or incompatible")
        }
    }

    private suspend fun readRawHeader(accessToken: String, spreadsheetId: String): List<String> {
        val response =
            request(
                "GET",
                "/spreadsheets/${encodePath(spreadsheetId)}/values/${encodePath(RAW_HEADER_RANGE)}" +
                    "?majorDimension=ROWS&valueRenderOption=UNFORMATTED_VALUE",
                accessToken,
            )
        return response.optJSONArray("values")?.optJSONArray(0)?.toKotlinList()
            ?.map(Any?::toString).orEmpty()
    }

    private suspend fun readRawSheetCells(
        accessToken: String,
        spreadsheetId: String,
    ): List<List<Any?>> {
        val response =
            request(
                "GET",
                "/spreadsheets/${encodePath(spreadsheetId)}/values/${encodePath(RAW_DATA_RANGE)}" +
                    "?majorDimension=ROWS&valueRenderOption=UNFORMATTED_VALUE",
                accessToken,
            )
        val values = response.optJSONArray("values") ?: return emptyList()
        return List(values.length()) { values.getJSONArray(it).toKotlinList() }
    }

    private suspend fun batchUpdateRaw(
        accessToken: String,
        spreadsheetId: String,
        updates: List<RawRowUpdate>,
    ) {
        if (updates.isEmpty()) return
        val data = JSONArray()
        updates.forEach { update ->
            data.put(
                valueRange(listOf(update.row.toApiCells()))
                    .put(
                        "range",
                        "$RAW_TAB_NAME!A${update.rowNumber}:M${update.rowNumber}",
                    ),
            )
        }
        request(
            "POST",
            "/spreadsheets/${encodePath(spreadsheetId)}/values:batchUpdate",
            accessToken,
            JSONObject().put("valueInputOption", "RAW").put("data", data),
        )
    }

    private suspend fun appendRaw(
        accessToken: String,
        spreadsheetId: String,
        rows: List<RawHealthRecord>,
    ) {
        if (rows.isEmpty()) return
        request(
            "POST",
            "/spreadsheets/${encodePath(spreadsheetId)}/values/${encodePath(RAW_DATA_RANGE)}:append" +
                "?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
            accessToken,
            valueRange(rows.map { it.toApiCells() }),
        )
    }

    private suspend fun validateHeader(accessToken: String, spreadsheetId: String) {
        if (readHeader(accessToken, spreadsheetId) != SheetRow.HEADER) {
            throw SheetsApiException("The Daily sheet header is missing or incompatible")
        }
    }

    private suspend fun readHeader(accessToken: String, spreadsheetId: String): List<String> {
        val response =
            request(
                "GET",
                "/spreadsheets/${encodePath(spreadsheetId)}/values/${encodePath(HEADER_RANGE)}" +
                    "?majorDimension=ROWS&valueRenderOption=UNFORMATTED_VALUE",
                accessToken,
            )
        val values = response.optJSONArray("values")
        return values?.optJSONArray(0)?.toKotlinList()?.map(Any?::toString).orEmpty()
    }

    private suspend fun readRawRows(accessToken: String, spreadsheetId: String): List<List<Any?>> {
        val response =
            request(
                "GET",
                "/spreadsheets/${encodePath(spreadsheetId)}/values/${encodePath(DATA_RANGE)}" +
                    "?majorDimension=ROWS&valueRenderOption=UNFORMATTED_VALUE",
                accessToken,
            )
        val values = response.optJSONArray("values") ?: return emptyList()
        return List(values.length()) { values.getJSONArray(it).toKotlinList() }
    }

    private suspend fun batchUpdate(
        accessToken: String,
        spreadsheetId: String,
        updates: List<RowUpdate>,
    ) {
        val data = JSONArray()
        updates.forEach { update ->
            data.put(
                valueRange(listOf(update.row.toApiCells()))
                    .put("range", "$TAB_NAME!A${update.rowNumber}:J${update.rowNumber}"),
            )
        }
        val body = JSONObject().put("valueInputOption", "RAW").put("data", data)
        request(
            "POST",
            "/spreadsheets/${encodePath(spreadsheetId)}/values:batchUpdate",
            accessToken,
            body,
        )
    }

    private suspend fun append(
        accessToken: String,
        spreadsheetId: String,
        rows: List<SheetRow>,
    ) {
        request(
            "POST",
            "/spreadsheets/${encodePath(spreadsheetId)}/values/${encodePath(DATA_RANGE)}:append" +
                "?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
            accessToken,
            valueRange(rows.map { it.toApiCells() }),
        )
    }

    private suspend fun request(
        method: String,
        path: String,
        accessToken: String,
        body: JSONObject? = null,
    ): JSONObject {
        var retryCount = 0
        while (true) {
            try {
                return requestOnce(method, path, accessToken, body)
            } catch (error: SheetsApiException) {
                if (!shouldRetry(method, path, error.httpStatus, retryCount)) throw error
                delay(RETRY_BASE_DELAY_MILLIS shl retryCount)
                retryCount += 1
            }
        }
    }

    private suspend fun requestOnce(
        method: String,
        path: String,
        accessToken: String,
        body: JSONObject?,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            require(accessToken.isNotBlank()) { "Google access token is required" }
            val connection = (URL(endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = method
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { output ->
                        output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                    }
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    connection.errorStream?.use { it.readBytes() }
                    throw SheetsApiException(
                        message = "Google Sheets request failed (HTTP $status)",
                        httpStatus = status,
                    )
                }
                val responseText = connection.inputStream.use { it.reader().readText() }
                if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            } catch (error: SheetsApiException) {
                throw error
            } catch (error: IOException) {
                throw SheetsApiException("Google Sheets network request failed", cause = error)
            } catch (error: JSONException) {
                throw SheetsApiException("Google Sheets returned an invalid response", cause = error)
            } finally {
                connection.disconnect()
            }
        }

    private fun shouldRetry(
        method: String,
        path: String,
        httpStatus: Int?,
        retryCount: Int,
    ): Boolean {
        if (retryCount >= MAX_RETRY_COUNT) return false
        val idempotentRequest =
            method == "GET" ||
                method == "PUT" ||
                path.endsWith("/values:batchUpdate") ||
                path.substringBefore('?').endsWith(":batchUpdate")
        if (httpStatus == null) return idempotentRequest
        if (httpStatus == 429) return true
        return idempotentRequest && (httpStatus == 408 || httpStatus >= 500)
    }

    private fun parseSheetRow(cells: List<Any?>, rowNumber: Int): SheetRow {
        fun cell(index: Int): Any? = cells.getOrNull(index)?.takeUnless { it == JSONObject.NULL }
        fun requiredString(index: Int, name: String): String =
            cell(index)?.toString()?.takeIf(String::isNotBlank)
                ?: throw invalidRow(rowNumber, name)
        fun optionalDouble(index: Int, name: String): Double? {
            val value = cell(index) ?: return null
            if (value.toString().isBlank()) return null
            return SheetNumberParser.parseDouble(value.toString()) ?: throw invalidRow(rowNumber, name)
        }
        fun optionalLong(index: Int, name: String): Long? {
            val value = cell(index) ?: return null
            if (value.toString().isBlank()) return null
            return value.toString().toDoubleOrNull()?.takeIf { it.isFinite() && it % 1.0 == 0.0 }
                ?.toLong() ?: throw invalidRow(rowNumber, name)
        }
        fun requiredLong(index: Int, name: String): Long =
            optionalLong(index, name) ?: throw invalidRow(rowNumber, name)

        return try {
            SheetRow(
                date = LocalDate.parse(requiredString(0, "date")),
                timezone = requiredString(1, "timezone"),
                weightKg = optionalDouble(2, "weight_kg"),
                bodyFatPercent = optionalDouble(3, "body_fat_percent"),
                steps = optionalLong(4, "steps"),
                activeMinutes = optionalLong(5, "active_minutes"),
                workoutCount = requiredLong(6, "workout_count").toIntExact(rowNumber),
                sleepMinutes = optionalLong(7, "sleep_minutes"),
                syncedAt = Instant.parse(requiredString(8, "synced_at")),
                sourceStatus = requiredString(9, "source_status"),
            )
        } catch (error: SheetsApiException) {
            throw error
        } catch (error: RuntimeException) {
            throw SheetsApiException("Daily row $rowNumber contains invalid values", cause = error)
        }
    }

    private fun parseRawSheetRow(cells: List<Any?>, rowNumber: Int): RawHealthRecord {
        fun cell(index: Int): String? =
            cells.getOrNull(index)?.takeUnless { it == JSONObject.NULL }?.toString()
                ?.takeUnless(String::isBlank)
        fun required(index: Int, column: String): String =
            cell(index) ?: throw SheetsApiException("Raw row $rowNumber contains an invalid $column")

        return try {
            RawHealthRecord(
                recordKey = required(0, "record_key"),
                recordType = required(1, "type"),
                startTime = Instant.parse(required(2, "start_time")),
                endTime = cell(3)?.let(Instant::parse),
                zoneOffset = cell(4),
                value = cell(5)?.toBigDecimalOrNull()
                    ?: cell(5)?.let { throw SheetsApiException("Raw row $rowNumber contains an invalid value") },
                unit = cell(6),
                subtype = cell(7),
                dataOrigin = required(8, "data_origin"),
                clientRecordId = cell(9),
                lastModifiedTime = Instant.parse(required(10, "last_modified_time")),
                detailsJson = cell(11),
                exportedAt = Instant.parse(required(12, "exported_at")),
            )
        } catch (error: SheetsApiException) {
            throw error
        } catch (error: RuntimeException) {
            throw SheetsApiException("Raw row $rowNumber contains invalid values", cause = error)
        }
    }

    private fun Long.toIntExact(rowNumber: Int): Int =
        takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            ?: throw SheetsApiException("Daily row $rowNumber contains an invalid workout_count")

    private fun invalidRow(rowNumber: Int, column: String) =
        SheetsApiException("Daily row $rowNumber contains an invalid $column")

    private fun SheetRow.toApiCells(): List<Any> =
        toCells().map { it ?: "" }

    private fun RawHealthRecord.toApiCells(): List<Any> = toCells()

    private fun <T> List<T>.rawWriteChunks(cellsOf: (T) -> List<Any>): List<List<T>> {
        val chunks = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        var currentBytes = 0

        forEach { item ->
            val cells = cellsOf(item)
            if (cells.any { cell -> cell is String && cell.length > MAX_SHEET_CELL_CHARACTERS }) {
                throw SheetsApiException(
                    "A Raw record exceeds the Google Sheets cell-size limit",
                )
            }
            val estimatedBytes =
                JSONArray(cells).toString().toByteArray(StandardCharsets.UTF_8).size +
                    RAW_ROW_JSON_OVERHEAD_BYTES
            if (current.isNotEmpty() &&
                (current.size >= RAW_WRITE_BATCH_SIZE ||
                    currentBytes + estimatedBytes > RAW_WRITE_TARGET_BYTES)
            ) {
                chunks += current
                current = mutableListOf()
                currentBytes = 0
            }
            current += item
            currentBytes += estimatedBytes
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private fun rawRowsMatch(expected: RawHealthRecord, actual: RawHealthRecord): Boolean =
        expected.copy(value = null).toCells() == actual.copy(value = null).toCells() &&
            canonicalNumber(expected.value) == canonicalNumber(actual.value)

    private fun canonicalNumber(value: Number?): String? =
        value?.toString()?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()

    private fun valueRange(rows: List<List<Any>>): JSONObject =
        JSONObject()
            .put("majorDimension", "ROWS")
            .put("values", JSONArray().apply { rows.forEach { put(JSONArray(it)) } })

    private fun JSONArray.toKotlinList(): List<Any?> =
        List(length()) { index -> opt(index).takeUnless { it == JSONObject.NULL } }

    private fun validateSpreadsheetId(spreadsheetId: String) {
        require(spreadsheetId.matches(SPREADSHEET_ID_PATTERN)) { "Invalid spreadsheet ID" }
    }

    private fun encodePath(value: String): String =
        value.toByteArray(StandardCharsets.UTF_8).joinToString("") { byte ->
            val unsigned = byte.toInt() and 0xff
            if ((unsigned in 'a'.code..'z'.code) ||
                (unsigned in 'A'.code..'Z'.code) ||
                (unsigned in '0'.code..'9'.code) ||
                unsigned == '-'.code || unsigned == '_'.code || unsigned == '.'.code || unsigned == '~'.code
            ) {
                unsigned.toChar().toString()
            } else {
                "%%%02X".format(unsigned)
            }
        }

    companion object {
        const val SPREADSHEET_TITLE = "Samsung Health Bridge"
        const val TAB_NAME = "Daily"
        const val RAW_TAB_NAME = "Raw"

        private const val HEADER_RANGE = "$TAB_NAME!A1:J1"
        private const val DATA_RANGE = "$TAB_NAME!A2:J"
        private const val RAW_HEADER_RANGE = "$RAW_TAB_NAME!A1:M1"
        private const val RAW_DATA_RANGE = "$RAW_TAB_NAME!A2:M"
        private const val FIRST_DATA_ROW = 2
        private const val RAW_WRITE_BATCH_SIZE = 500
        private const val RAW_WRITE_TARGET_BYTES = 1_500_000
        private const val RAW_ROW_JSON_OVERHEAD_BYTES = 200
        private const val MAX_SHEET_CELL_CHARACTERS = 50_000
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val MAX_RETRY_COUNT = 6
        private const val RETRY_BASE_DELAY_MILLIS = 1_000L
        private val SPREADSHEET_ID_PATTERN = Regex("[A-Za-z0-9_-]{10,}")
    }
}

class SheetsApiException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isUnauthorized: Boolean get() = httpStatus == HttpURLConnection.HTTP_UNAUTHORIZED
}
