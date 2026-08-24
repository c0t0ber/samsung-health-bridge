package com.roktober.samsunghealthbridge.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RollingWindowTest {
    @Test
    fun `returns every local date touched by the last 72 hours`() {
        val dates =
            RollingWindow.datesTouchedBy(
                now = Instant.parse("2026-08-16T02:00:00Z"),
                zoneId = ZoneId.of("Asia/Shanghai"),
            )

        assertEquals(
            listOf(
                LocalDate.parse("2026-08-13"),
                LocalDate.parse("2026-08-14"),
                LocalDate.parse("2026-08-15"),
                LocalDate.parse("2026-08-16"),
            ),
            dates,
        )
    }

    @Test
    fun `uses elapsed time rather than subtracting local days across spring DST`() {
        val dates =
            RollingWindow.datesTouchedBy(
                now = Instant.parse("2026-03-10T04:30:00Z"),
                zoneId = ZoneId.of("America/New_York"),
                duration = Duration.ofHours(72),
            )

        assertEquals(
            listOf(
                LocalDate.parse("2026-03-06"),
                LocalDate.parse("2026-03-07"),
                LocalDate.parse("2026-03-08"),
                LocalDate.parse("2026-03-09"),
                LocalDate.parse("2026-03-10"),
            ),
            dates,
        )
    }

    @Test
    fun `adds one preceding context day without expanding written dates`() {
        val dates =
            listOf(
                LocalDate.parse("2026-08-17"),
                LocalDate.parse("2026-08-18"),
                LocalDate.parse("2026-08-19"),
                LocalDate.parse("2026-08-20"),
            )

        val query = RollingWindow.queryWindowFor(dates)

        assertEquals(LocalDate.parse("2026-08-16"), query.startDate)
        assertEquals(LocalDate.parse("2026-08-21"), query.endDateExclusive)
        assertEquals(dates, query.outputDates)
    }

    @Test
    fun `builds an inclusive one-time history date range`() {
        val dates =
            RollingWindow.previousDays(
                now = Instant.parse("2026-08-16T02:00:00Z"),
                zoneId = ZoneId.of("Asia/Shanghai"),
                days = 90,
            )

        assertEquals(90, dates.size)
        assertEquals(LocalDate.parse("2026-05-19"), dates.first())
        assertEquals(LocalDate.parse("2026-08-16"), dates.last())
    }
}
