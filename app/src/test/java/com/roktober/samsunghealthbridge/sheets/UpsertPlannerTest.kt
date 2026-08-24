package com.roktober.samsunghealthbridge.sheets

import com.roktober.samsunghealthbridge.model.SheetRow
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpsertPlannerTest {
    @Test
    fun `updates an existing date and appends only a new date`() {
        val existing = listOf(listOf("2026-08-15"), listOf("2026-08-16"))
        val unchangedDate = row("2026-08-16", 200)
        val newDate = row("2026-08-17", 300)

        val plan = UpsertPlanner.plan(existing, listOf(newDate, unchangedDate))

        assertEquals(listOf(RowUpdate(3, unchangedDate)), plan.updates)
        assertEquals(listOf(newDate), plan.appends)
    }

    @Test
    fun `second sync of appended rows plans updates and no duplicate append`() {
        val rows = listOf(row("2026-08-15", 100), row("2026-08-16", 200))
        val firstPlan = UpsertPlanner.plan(emptyList(), rows)
        val sheetAfterFirstSync = firstPlan.appends.map(SheetRow::toCells)

        val secondPlan = UpsertPlanner.plan(sheetAfterFirstSync, rows)

        assertTrue(secondPlan.appends.isEmpty())
        assertEquals(listOf(2, 3), secondPlan.updates.map { it.rowNumber })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate outgoing dates before any network write`() {
        UpsertPlanner.plan(emptyList(), listOf(row("2026-08-16", 100), row("2026-08-16", 200)))
    }

    @Test(expected = DuplicateSheetDateException::class)
    fun `rejects an already duplicated sheet date instead of appending another row`() {
        UpsertPlanner.plan(
            existingRows = listOf(listOf("2026-08-16"), listOf("2026-08-16")),
            incomingRows = listOf(row("2026-08-16", 100)),
        )
    }

    @Test
    fun `keeps null optional values as empty cells and stable column order`() {
        val cells = row("2026-08-16", 100).toCells()

        assertEquals(SheetRow.HEADER.size, cells.size)
        assertEquals("2026-08-16", cells[0])
        assertEquals("Asia/Shanghai", cells[1])
        assertEquals(null, cells[2])
        assertEquals(null, cells[3])
        assertEquals(100L, cells[4])
    }

    private fun row(date: String, steps: Long) =
        SheetRow(
            date = LocalDate.parse(date),
            timezone = "Asia/Shanghai",
            weightKg = null,
            bodyFatPercent = null,
            steps = steps,
            activeMinutes = 10,
            workoutCount = 1,
            sleepMinutes = 420,
            syncedAt = Instant.parse("2026-08-16T02:00:00Z"),
            sourceStatus = "ok",
        )
}
