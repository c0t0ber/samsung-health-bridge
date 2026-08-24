package com.roktober.samsunghealthbridge.sheets

import com.roktober.samsunghealthbridge.model.RawHealthRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawUpsertPlannerTest {
    @Test
    fun `updates existing keys and appends only new keys`() {
        val existing = listOf(listOf("steps:a"), listOf("sleep:b"))
        val updated = row("sleep:b")
        val added = row("weight:c")

        val plan = RawUpsertPlanner.plan(existing, listOf(added, updated))

        assertEquals(listOf(RawRowUpdate(3, updated)), plan.updates)
        assertEquals(listOf(added), plan.appends)
    }

    @Test
    fun `second export is idempotent and performs no duplicate append`() {
        val rows = listOf(row("steps:a"), row("sleep:b"))
        val firstPlan = RawUpsertPlanner.plan(emptyList(), rows)
        val sheetAfterFirstExport = firstPlan.appends.map(RawHealthRecord::toCells)

        val secondPlan = RawUpsertPlanner.plan(sheetAfterFirstExport, rows)

        assertTrue(secondPlan.appends.isEmpty())
        assertEquals(listOf(2, 3), secondPlan.updates.map { it.rowNumber })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate incoming record keys before a network write`() {
        RawUpsertPlanner.plan(emptyList(), listOf(row("steps:a"), row("steps:a")))
    }

    @Test(expected = DuplicateRawRecordKeyException::class)
    fun `rejects an already duplicated sheet key`() {
        RawUpsertPlanner.plan(
            existingRows = listOf(listOf("steps:a"), listOf("steps:a")),
            incomingRows = listOf(row("steps:a")),
        )
    }

    @Test
    fun `ignores empty sheet rows when indexing record keys`() {
        val incoming = row("steps:a")

        val plan = RawUpsertPlanner.plan(listOf(emptyList(), listOf("")), listOf(incoming))

        assertEquals(listOf(incoming), plan.appends)
        assertTrue(plan.updates.isEmpty())
    }

    private fun row(recordKey: String) =
        RawHealthRecord(
            recordKey = recordKey,
            recordType = recordKey.substringBefore(':'),
            startTime = Instant.parse("2026-08-16T01:00:00Z"),
            endTime = Instant.parse("2026-08-16T02:00:00Z"),
            zoneOffset = "+08:00",
            value = 1L,
            unit = "count",
            subtype = null,
            dataOrigin = "com.sec.android.app.shealth",
            clientRecordId = null,
            lastModifiedTime = Instant.parse("2026-08-16T02:30:00Z"),
            detailsJson = null,
            exportedAt = Instant.parse("2026-08-16T03:00:00Z"),
        )
}
