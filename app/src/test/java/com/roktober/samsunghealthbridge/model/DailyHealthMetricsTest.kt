package com.roktober.samsunghealthbridge.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyHealthMetricsTest {
    @Test
    fun `converts aggregate durations and keeps optional measurements blank`() {
        val row =
            DailyHealthMetrics(
                date = LocalDate.parse("2026-08-16"),
                zoneId = ZoneId.of("Asia/Shanghai"),
                weightKg = null,
                bodyFatPercent = null,
                steps = 12_345,
                exerciseDuration = Duration.ofSeconds(3_659),
                workoutCount = 2,
                sleepDuration = Duration.ofMinutes(431),
                contributingPackages = setOf("com.sec.android.app.shealth"),
                qualityFlags = setOf("timezone_record_offset"),
            ).toSheetRow(Instant.parse("2026-08-16T02:05:00Z"))

        assertEquals("Asia/Shanghai", row.timezone)
        assertNull(row.weightKg)
        assertNull(row.bodyFatPercent)
        assertEquals(12_345L, row.steps)
        assertEquals(60L, row.activeMinutes)
        assertEquals(431L, row.sleepMinutes)
        assertEquals(
            "partial:health_connect;missing=weight_kg,body_fat_percent;flags=timezone_record_offset",
            row.sourceStatus,
        )
    }

    @Test
    fun `keeps unavailable aggregates blank instead of inventing zeroes`() {
        val row =
            DailyHealthMetrics(
                date = LocalDate.parse("2026-08-16"),
                zoneId = ZoneId.of("UTC"),
                weightKg = null,
                bodyFatPercent = null,
                steps = null,
                exerciseDuration = null,
                workoutCount = 0,
                sleepDuration = null,
            ).toSheetRow(Instant.EPOCH)

        assertNull(row.steps)
        assertNull(row.activeMinutes)
        assertNull(row.sleepMinutes)
        assertEquals("no_data:health_connect", row.sourceStatus)
        assertEquals(SheetRow.HEADER.size, row.toCells().size)
    }
}
