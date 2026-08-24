package com.roktober.samsunghealthbridge.sheets

import com.roktober.samsunghealthbridge.model.RawHealthRecord

data class RawRowUpdate(val rowNumber: Int, val row: RawHealthRecord)

data class RawUpsertPlan(
    val updates: List<RawRowUpdate>,
    val appends: List<RawHealthRecord>,
)

class DuplicateRawRecordKeyException(val duplicateRecordKey: String) :
    IllegalStateException("Raw sheet contains more than one row for record key $duplicateRecordKey")

object RawUpsertPlanner {
    fun plan(
        existingRows: List<List<Any?>>,
        incomingRows: List<RawHealthRecord>,
    ): RawUpsertPlan {
        require(incomingRows.map { it.recordKey }.distinct().size == incomingRows.size) {
            "Incoming raw rows contain duplicate record keys"
        }

        val rowByRecordKey = linkedMapOf<String, Int>()
        existingRows.forEachIndexed { index, cells ->
            val recordKey = cells.firstOrNull()?.toString()?.takeIf(String::isNotBlank)
                ?: return@forEachIndexed
            if (rowByRecordKey.putIfAbsent(recordKey, index + FIRST_DATA_ROW) != null) {
                throw DuplicateRawRecordKeyException(recordKey)
            }
        }

        val updates = mutableListOf<RawRowUpdate>()
        val appends = mutableListOf<RawHealthRecord>()
        incomingRows.sortedBy(RawHealthRecord::recordKey).forEach { row ->
            val existingRowNumber = rowByRecordKey[row.recordKey]
            if (existingRowNumber == null) {
                appends += row
            } else {
                updates += RawRowUpdate(existingRowNumber, row)
            }
        }
        return RawUpsertPlan(updates = updates, appends = appends)
    }

    private const val FIRST_DATA_ROW = 2
}
