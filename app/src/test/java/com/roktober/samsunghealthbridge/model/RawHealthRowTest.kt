package com.roktober.samsunghealthbridge.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class RawHealthRecordTest {
    @Test
    fun `serializes a complete row in stable header order`() {
        val row = row(value = 123L, detailsJson = "{\"count\":123}")

        val cells = row.toCells()

        assertEquals(RawHealthRecord.HEADER.size, cells.size)
        assertEquals("steps:id-1", cells[0])
        assertEquals("steps", cells[1])
        assertEquals("2026-08-16T01:00:00Z", cells[2])
        assertEquals("2026-08-16T02:00:00Z", cells[3])
        assertEquals("+08:00", cells[4])
        assertEquals(123L, cells[5])
        assertEquals("count", cells[6])
        assertEquals("com.sec.android.app.shealth", cells[8])
        assertEquals("2026-08-16T03:00:00Z", cells[12])
    }

    @Test
    fun `serializes every optional value as an empty string so updates clear stale cells`() {
        val cells =
            row(
                endTime = null,
                zoneOffset = null,
                value = null,
                unit = null,
                subtype = null,
                clientRecordId = null,
                detailsJson = null,
            ).toCells()

        listOf(3, 4, 5, 6, 7, 9, 11).forEach { index ->
            assertEquals("Optional cell $index must be cleared", "", cells[index])
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a blank stable record key`() {
        row().copy(recordKey = " ")
    }

    private fun row(
        startTime: Instant = Instant.parse("2026-08-16T01:00:00Z"),
        endTime: Instant? = Instant.parse("2026-08-16T02:00:00Z"),
        zoneOffset: String? = "+08:00",
        value: Number? = 123L,
        unit: String? = "count",
        subtype: String? = "",
        clientRecordId: String? = "client-1",
        detailsJson: String? = null,
    ) =
        RawHealthRecord(
            recordKey = "steps:id-1",
            recordType = "steps",
            startTime = startTime,
            endTime = endTime,
            zoneOffset = zoneOffset,
            value = value,
            unit = unit,
            subtype = subtype,
            dataOrigin = "com.sec.android.app.shealth",
            clientRecordId = clientRecordId,
            lastModifiedTime = Instant.parse("2026-08-16T02:30:00Z"),
            detailsJson = detailsJson,
            exportedAt = Instant.parse("2026-08-16T03:00:00Z"),
        )
}
