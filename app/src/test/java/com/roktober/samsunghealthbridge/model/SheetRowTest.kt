package com.roktober.samsunghealthbridge.model

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetRowTest {
    @Test
    fun `preview renders missing metrics as unavailable instead of null`() {
        val preview =
            SheetRow(
                date = LocalDate.parse("2026-08-16"),
                timezone = "+08:00",
                weightKg = null,
                bodyFatPercent = null,
                steps = null,
                activeMinutes = null,
                workoutCount = 0,
                sleepMinutes = null,
                syncedAt = Instant.EPOCH,
                sourceStatus = "no_data:health_connect",
            ).preview()

        assertEquals("2026-08-16 · — steps · — active min · — sleep min", preview)
    }
}
