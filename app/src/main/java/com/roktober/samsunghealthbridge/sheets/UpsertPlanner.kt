package com.roktober.samsunghealthbridge.sheets

import com.roktober.samsunghealthbridge.model.SheetRow
import java.time.LocalDate

data class RowUpdate(val rowNumber: Int, val row: SheetRow)

data class UpsertPlan(
    val updates: List<RowUpdate>,
    val appends: List<SheetRow>,
)

class DuplicateSheetDateException(val duplicateDate: LocalDate) :
    IllegalStateException("Sheet contains more than one row for date $duplicateDate")

object UpsertPlanner {
    fun plan(
        existingRows: List<List<Any?>>,
        incomingRows: List<SheetRow>,
    ): UpsertPlan {
        require(incomingRows.map { it.date }.distinct().size == incomingRows.size) {
            "Incoming rows contain duplicate dates"
        }

        val rowByDate = linkedMapOf<LocalDate, Int>()
        existingRows.forEachIndexed { index, cells ->
            val date = cells.firstOrNull()?.toString()?.toLocalDateOrNull() ?: return@forEachIndexed
            if (rowByDate.putIfAbsent(date, index + FIRST_DATA_ROW) != null) {
                throw DuplicateSheetDateException(date)
            }
        }

        val sortedIncoming = incomingRows.sortedBy { it.date }
        val updates = mutableListOf<RowUpdate>()
        val appends = mutableListOf<SheetRow>()
        sortedIncoming.forEach { row ->
            val existingRowNumber = rowByDate[row.date]
            if (existingRowNumber == null) {
                appends += row
            } else {
                updates += RowUpdate(existingRowNumber, row)
            }
        }
        return UpsertPlan(updates = updates, appends = appends)
    }

    private fun String.toLocalDateOrNull(): LocalDate? =
        runCatching { LocalDate.parse(this) }.getOrNull()

    private const val FIRST_DATA_ROW = 2
}
