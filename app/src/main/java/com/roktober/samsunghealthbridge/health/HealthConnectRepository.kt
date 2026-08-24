package com.roktober.samsunghealthbridge.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.roktober.samsunghealthbridge.model.DailyHealthMetrics
import com.roktober.samsunghealthbridge.model.RawHealthRecord
import com.roktober.samsunghealthbridge.model.RollingWindow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only access to the small Health Connect surface used by the app.
 *
 * The repository deliberately does not request route, heart-rate, or medical permissions.
 */
class HealthConnectRepository(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    val sdkStatus: HealthConnectSdkStatus
        get() =
            when (HealthConnectClient.getSdkStatus(applicationContext, PROVIDER_PACKAGE_NAME)) {
                HealthConnectClient.SDK_AVAILABLE -> HealthConnectSdkStatus.Available
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    HealthConnectSdkStatus.ProviderUpdateRequired
                else -> HealthConnectSdkStatus.Unavailable
            }

    private val client: HealthConnectClient?
        get() =
            if (sdkStatus == HealthConnectSdkStatus.Available) {
                HealthConnectClient.getOrCreate(applicationContext, PROVIDER_PACKAGE_NAME)
            } else {
                null
            }

    fun isBackgroundReadAvailable(): Boolean =
        isFeatureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)

    fun isHistoryReadAvailable(): Boolean =
        isFeatureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)

    /**
     * Returns only permissions supported by this device. History is opt-in because it is only
     * needed by the initial import of data older than Health Connect's normal 30-day window.
     */
    fun requestedReadPermissions(
        includeBackground: Boolean = true,
        includeHistory: Boolean = false,
    ): Set<String> {
        if (sdkStatus != HealthConnectSdkStatus.Available) return emptySet()

        return buildSet {
            addAll(CORE_READ_PERMISSIONS)
            if (includeBackground && isBackgroundReadAvailable()) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            }
            if (includeHistory && isHistoryReadAvailable()) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
            }
        }
    }

    suspend fun getPermissionStatus(): HealthConnectPermissionStatus {
        val currentSdkStatus = sdkStatus
        val availableClient = client
        if (currentSdkStatus != HealthConnectSdkStatus.Available || availableClient == null) {
            return HealthConnectPermissionStatus(
                sdkStatus = currentSdkStatus,
                grantedPermissions = emptySet(),
                missingCorePermissions = CORE_READ_PERMISSIONS,
                backgroundReadAvailable = false,
                backgroundReadGranted = false,
                historyReadAvailable = false,
                historyReadGranted = false,
            )
        }

        val granted = availableClient.permissionController.getGrantedPermissions()
        val backgroundAvailable =
            availableClient.isFeatureAvailable(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
            )
        val historyAvailable =
            availableClient.isFeatureAvailable(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY,
            )
        return HealthConnectPermissionStatus(
            sdkStatus = currentSdkStatus,
            grantedPermissions = granted,
            missingCorePermissions = CORE_READ_PERMISSIONS - granted,
            backgroundReadAvailable = backgroundAvailable,
            backgroundReadGranted =
                backgroundAvailable &&
                    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted,
            historyReadAvailable = historyAvailable,
            historyReadGranted =
                historyAvailable && HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted,
        )
    }

    /** Reads each requested user-experienced local calendar day independently. */
    suspend fun readDailyMetrics(
        dates: List<LocalDate>,
        zoneId: ZoneId,
    ): List<DailyHealthMetrics> {
        val currentSdkStatus = sdkStatus
        val availableClient = client ?: throw HealthConnectUnavailableException(currentSdkStatus)
        if (dates.isEmpty()) return emptyList()

        val queryWindow = RollingWindow.queryWindowFor(dates)
        // Health Connect interval records can start on the previous date and contribute to the
        // first requested day (most visibly an overnight sleep session). The query window adds
        // that context, while outputDates remains the original rolling window.
        val filter =
            TimeRangeFilter.between(
                queryWindow.startDate.atStartOfDay(),
                queryWindow.endDateExclusive.atStartOfDay(),
            )
        val steps = availableClient.readAllRecords(StepsRecord::class, filter)
        val sleeps = availableClient.readAllRecords(SleepSessionRecord::class, filter)
        val exercises = availableClient.readAllRecords(ExerciseSessionRecord::class, filter)
        val weights = availableClient.readAllRecords(WeightRecord::class, filter)
        val bodyFat = availableClient.readAllRecords(BodyFatRecord::class, filter)
        val historicalZones =
            HistoricalDayZoneResolver.resolveObservations(
                observations =
                    buildList {
                        steps.forEach { record ->
                            add(DayZoneObservation(record.startTime, record.startZoneOffset))
                        }
                        sleeps.forEach { record ->
                            add(DayZoneObservation(record.startTime, record.startZoneOffset))
                        }
                        exercises.forEach { record ->
                            add(DayZoneObservation(record.startTime, record.startZoneOffset))
                        }
                        weights.forEach { record ->
                            add(DayZoneObservation(record.time, record.zoneOffset))
                        }
                        bodyFat.forEach { record ->
                            add(DayZoneObservation(record.time, record.zoneOffset))
                        }
                    },
                fallbackZone = zoneId,
            )
        val groupedResults =
            availableClient.readCompleteAggregatesByDay(
                firstDate = queryWindow.startDate,
                endDateExclusive = queryWindow.endDateExclusive,
            )

        return assembleDailyMetrics(
            dates = queryWindow.outputDates,
            groupedResults = groupedResults,
            exercises = exercises,
            weights = weights,
            bodyFat = bodyFat,
            historicalZones = historicalZones,
            fallbackZone = zoneId,
        )
    }

    /**
     * Reads every accessible record for the five permissions requested by the app and produces
     * daily Health Connect aggregates for the complete returned date range.
     *
     * History permission is mandatory when the device exposes that feature. On older providers,
     * where the permission does not exist, Health Connect limits the accessible range itself and
     * the request is conservatively capped at 30 days.
     */
    suspend fun readAllHealthData(
        exportedAt: Instant,
        zoneId: ZoneId,
    ): CompleteHealthExport =
        withContext(Dispatchers.Default) {
        val currentSdkStatus = sdkStatus
        val availableClient = client ?: throw HealthConnectUnavailableException(currentSdkStatus)
        val permissionStatus = getPermissionStatus()
        if (permissionStatus.historyReadAvailable && !permissionStatus.historyReadGranted) {
            throw HealthHistoryPermissionRequiredException()
        }

        val rawFilter =
            if (permissionStatus.historyReadAvailable) {
                TimeRangeFilter.before(exportedAt)
            } else {
                TimeRangeFilter.between(exportedAt.minus(LEGACY_HISTORY_WINDOW), exportedAt)
            }
        val steps = availableClient.readAllRecords(StepsRecord::class, rawFilter)
        val sleeps = availableClient.readAllRecords(SleepSessionRecord::class, rawFilter)
        val exercises = availableClient.readAllRecords(ExerciseSessionRecord::class, rawFilter)
        val weights = availableClient.readAllRecords(WeightRecord::class, rawFilter)
        val bodyFat = availableClient.readAllRecords(BodyFatRecord::class, rawFilter)

        val rawRows =
            buildList {
                steps.forEach { add(RawHealthRecordNormalizer.normalize(it, exportedAt)) }
                sleeps.forEach { add(RawHealthRecordNormalizer.normalize(it, exportedAt)) }
                exercises.forEach { add(RawHealthRecordNormalizer.normalize(it, exportedAt)) }
                weights.forEach { add(RawHealthRecordNormalizer.normalize(it, exportedAt)) }
                bodyFat.forEach { add(RawHealthRecordNormalizer.normalize(it, exportedAt)) }
            }
        check(rawRows.map { it.recordKey }.distinct().size == rawRows.size) {
            "Health Connect returned duplicate record IDs during the diagnostic export"
        }

        val today = exportedAt.atZone(zoneId).toLocalDate()
        val historicalZones = HistoricalDayZoneResolver.resolve(rawRows, zoneId)
        val firstDate =
            rawRows.minOfOrNull { it.localDate(zoneId) }
                ?.coerceAtMost(today)
                ?: today
        val groupedResults =
            availableClient.readCompleteAggregatesByDay(
                firstDate = firstDate,
                endDateExclusive = today.plusDays(1),
            )
        val dates =
            generateSequence(firstDate) { date ->
                date.plusDays(1).takeIf { it <= today }
            }.toList()
        val dailyMetrics =
            assembleDailyMetrics(
                dates = dates,
                groupedResults = groupedResults,
                exercises = exercises,
                weights = weights,
                bodyFat = bodyFat,
                historicalZones = historicalZones,
                fallbackZone = zoneId,
            )

        val aggregateRows =
            dailyMetrics.flatMap { daily ->
                val result = groupedResults[daily.date]
                normalizeDailyAggregates(
                    date = daily.date,
                    zoneId = daily.zoneId,
                    result = result,
                    exportedAt = exportedAt,
                )
            }

        CompleteHealthExport(
            rows = (rawRows + aggregateRows).sortedWith(compareBy({ it.startTime }, { it.recordKey })),
            dailyMetrics = dailyMetrics,
            rawRecordCount = rawRows.size,
            aggregateRecordCount = aggregateRows.size,
        )
        }

    private fun assembleDailyMetrics(
        dates: List<LocalDate>,
        groupedResults: Map<LocalDate, androidx.health.connect.client.aggregate.AggregationResult>,
        exercises: List<ExerciseSessionRecord>,
        weights: List<WeightRecord>,
        bodyFat: List<BodyFatRecord>,
        historicalZones: Map<LocalDate, HistoricalDayZone>,
        fallbackZone: ZoneId,
    ): List<DailyHealthMetrics> {
        val exercisesByDate = exercises.groupBy { it.localDate(fallbackZone) }
        val weightsByDate = weights.groupBy { it.localDate(fallbackZone) }
        val bodyFatByDate = bodyFat.groupBy { it.localDate(fallbackZone) }
        return dates.map { date ->
            val grouped = groupedResults[date]
            val dayExercises = exercisesByDate[date].orEmpty()
            val latestWeight = weightsByDate[date].orEmpty().latestByTime()
            val latestBodyFat = bodyFatByDate[date].orEmpty().latestByTime()
            val historicalZone =
                historicalZones[date]
                    ?: HistoricalDayZone(
                        zoneId = fallbackZone,
                        qualityFlags = setOf("timezone_fallback"),
                    )
            DailyHealthMetrics(
                date = date,
                zoneId = historicalZone.zoneId,
                weightKg = latestWeight?.weight?.inKilograms,
                bodyFatPercent = latestBodyFat?.percentage?.value,
                steps = grouped?.get(StepsRecord.COUNT_TOTAL),
                exerciseDuration = grouped?.get(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL),
                workoutCount = dayExercises.distinctBy { it.metadata.id }.size,
                sleepDuration = grouped?.get(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                contributingPackages =
                    buildSet {
                        grouped?.dataOrigins?.let { addPackages(it) }
                        dayExercises.forEach { add(it.metadata.dataOrigin.packageName) }
                        latestWeight?.let { add(it.metadata.dataOrigin.packageName) }
                        latestBodyFat?.let { add(it.metadata.dataOrigin.packageName) }
                    },
                qualityFlags = historicalZone.qualityFlags,
            )
        }
    }

    private fun isFeatureAvailable(feature: Int): Boolean =
        client?.isFeatureAvailable(feature) == true

    private suspend fun <T : Record> HealthConnectClient.readAllRecords(
        recordType: KClass<T>,
        filter: TimeRangeFilter,
    ): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        val seenPageTokens = mutableSetOf<String>()
        do {
            val response =
                readRecords(
                    ReadRecordsRequest(
                        recordType = recordType,
                        timeRangeFilter = filter,
                        pageSize = READ_PAGE_SIZE,
                        pageToken = pageToken,
                    ),
                )
            records += response.records
            val nextPageToken = response.pageToken
            check(nextPageToken == null || seenPageTokens.add(nextPageToken)) {
                "Health Connect returned a repeated page token for ${recordType.simpleName}"
            }
            pageToken = nextPageToken
        } while (pageToken != null)
        return records
    }

    private fun normalizeDailyAggregates(
        date: LocalDate,
        zoneId: ZoneId,
        result: androidx.health.connect.client.aggregate.AggregationResult?,
        exportedAt: Instant,
    ): List<RawHealthRecord> {
        val origins = result?.dataOrigins.orEmpty().map { it.packageName }.sorted()
        // One AggregateRequest contains several record families, so dataOrigins is a bucket-wide
        // union rather than proof that every package contributed to every metric. Exact origins
        // remain available on the corresponding raw rows.
        val details = JSONObject().put("bucket_data_origins_union", JSONArray(origins)).toString()
        val start = date.atStartOfDay(zoneId).toInstant()
        val end = date.plusDays(1).atStartOfDay(zoneId).toInstant()

        fun aggregateRow(metric: String, value: Number?, unit: String): RawHealthRecord =
            RawHealthRecord(
                recordKey = "daily_aggregate:$date:$metric",
                recordType = "daily_aggregate",
                startTime = start,
                endTime = end,
                zoneOffset = date.atStartOfDay(zoneId).offset.id,
                value = value,
                unit = unit,
                subtype = metric,
                dataOrigin = AGGREGATE_DATA_ORIGIN,
                clientRecordId = null,
                lastModifiedTime = exportedAt,
                detailsJson = details,
                exportedAt = exportedAt,
            )

        return listOf(
            aggregateRow("steps_total", result?.get(StepsRecord.COUNT_TOTAL), "count"),
            aggregateRow(
                "exercise_duration_total",
                result?.get(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)?.toMillis()?.div(1_000.0),
                "seconds",
            ),
            aggregateRow(
                "sleep_duration_total",
                result?.get(SleepSessionRecord.SLEEP_DURATION_TOTAL)?.toMillis()?.div(1_000.0),
                "seconds",
            ),
            aggregateRow(
                "weight_avg",
                result?.get(WeightRecord.WEIGHT_AVG)?.inKilograms,
                "kg",
            ),
            aggregateRow(
                "weight_min",
                result?.get(WeightRecord.WEIGHT_MIN)?.inKilograms,
                "kg",
            ),
            aggregateRow(
                "weight_max",
                result?.get(WeightRecord.WEIGHT_MAX)?.inKilograms,
                "kg",
            ),
        )
    }

    private fun ExerciseSessionRecord.localDate(zoneId: ZoneId): LocalDate =
        startZoneOffset?.let(startTime::atOffset)?.toLocalDate()
            ?: startTime.atZone(zoneId).toLocalDate()

    private fun WeightRecord.localDate(zoneId: ZoneId): LocalDate =
        zoneOffset?.let(time::atOffset)?.toLocalDate() ?: time.atZone(zoneId).toLocalDate()

    private fun BodyFatRecord.localDate(zoneId: ZoneId): LocalDate =
        zoneOffset?.let(time::atOffset)?.toLocalDate() ?: time.atZone(zoneId).toLocalDate()

    private fun RawHealthRecord.localDate(zoneId: ZoneId): LocalDate =
        zoneOffset
            ?.let { offset -> runCatching { ZoneOffset.of(offset) }.getOrNull() }
            ?.let(startTime::atOffset)
            ?.toLocalDate()
            ?: startTime.atZone(zoneId).toLocalDate()

    private suspend fun HealthConnectClient.readCompleteAggregatesByDay(
        firstDate: LocalDate,
        endDateExclusive: LocalDate,
    ): Map<LocalDate, androidx.health.connect.client.aggregate.AggregationResult> {
        val results = linkedMapOf<LocalDate, androidx.health.connect.client.aggregate.AggregationResult>()
        AggregateChunkPlanner.plan(firstDate, endDateExclusive, AGGREGATE_CHUNK_DAYS)
            .forEach { chunk ->
                aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = COMPLETE_AGGREGATE_METRICS,
                        timeRangeFilter =
                            TimeRangeFilter.between(
                                chunk.queryStart.atStartOfDay(),
                                chunk.endDateExclusive.atStartOfDay(),
                            ),
                        timeRangeSlicer = Period.ofDays(1),
                    ),
                ).forEach { bucket ->
                    val date = bucket.startTime.toLocalDate()
                    if (date >= chunk.logicalStart && date < chunk.endDateExclusive) {
                        results[date] = bucket.result
                    }
                }
            }
        return results
    }

    private fun List<WeightRecord>.latestByTime(): WeightRecord? =
        maxWithOrNull(compareBy<WeightRecord> { it.time }.thenBy { it.metadata.id })

    private fun List<BodyFatRecord>.latestByTime(): BodyFatRecord? =
        maxWithOrNull(compareBy<BodyFatRecord> { it.time }.thenBy { it.metadata.id })

    private fun HealthConnectClient.isFeatureAvailable(feature: Int): Boolean =
        features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    private fun MutableSet<String>.addPackages(origins: Set<DataOrigin>) {
        origins.forEach { add(it.packageName) }
    }

    companion object {
        const val PROVIDER_PACKAGE_NAME = "com.google.android.apps.healthdata"
        const val SAMSUNG_HEALTH_PACKAGE_NAME = "com.sec.android.app.shealth"

        val CORE_READ_PERMISSIONS: Set<String> =
            setOf(
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getReadPermission(BodyFatRecord::class),
            )

        private val AGGREGATE_METRICS =
            setOf(
                StepsRecord.COUNT_TOTAL,
                ExerciseSessionRecord.EXERCISE_DURATION_TOTAL,
                SleepSessionRecord.SLEEP_DURATION_TOTAL,
            )

        private val COMPLETE_AGGREGATE_METRICS =
            AGGREGATE_METRICS +
                setOf(
                    WeightRecord.WEIGHT_AVG,
                    WeightRecord.WEIGHT_MIN,
                    WeightRecord.WEIGHT_MAX,
                )

        private const val READ_PAGE_SIZE = 1_000
        private const val AGGREGATE_CHUNK_DAYS = 90L
        private const val AGGREGATE_DATA_ORIGIN = "health_connect_aggregate"
        private val LEGACY_HISTORY_WINDOW: Duration = Duration.ofDays(30)
    }
}

data class CompleteHealthExport(
    val rows: List<RawHealthRecord>,
    val dailyMetrics: List<DailyHealthMetrics>,
    val rawRecordCount: Int,
    val aggregateRecordCount: Int,
)

enum class HealthConnectSdkStatus {
    Available,
    ProviderUpdateRequired,
    Unavailable,
}

class HealthConnectUnavailableException(
    val sdkStatus: HealthConnectSdkStatus,
) : IllegalStateException("Health Connect is not available: $sdkStatus")

class HealthHistoryPermissionRequiredException :
    IllegalStateException("Health Connect history permission is required for a complete export")

data class HealthConnectPermissionStatus(
    val sdkStatus: HealthConnectSdkStatus,
    val grantedPermissions: Set<String>,
    val missingCorePermissions: Set<String>,
    val backgroundReadAvailable: Boolean,
    val backgroundReadGranted: Boolean,
    val historyReadAvailable: Boolean,
    val historyReadGranted: Boolean,
) {
    val coreReadGranted: Boolean
        get() = missingCorePermissions.isEmpty()
}
