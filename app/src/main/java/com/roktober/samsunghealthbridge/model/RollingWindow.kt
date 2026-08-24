package com.roktober.samsunghealthbridge.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DailyQueryWindow(
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
    val outputDates: List<LocalDate>,
)

object RollingWindow {
    fun queryWindowFor(
        dates: List<LocalDate>,
        precedingContextDays: Long = 1,
    ): DailyQueryWindow {
        require(dates.isNotEmpty()) { "dates must not be empty" }
        require(precedingContextDays >= 0) { "precedingContextDays must not be negative" }
        val outputDates = dates.distinct().sorted()
        return DailyQueryWindow(
            startDate = outputDates.first().minusDays(precedingContextDays),
            endDateExclusive = outputDates.last().plusDays(1),
            outputDates = outputDates,
        )
    }

    fun datesTouchedBy(
        now: Instant,
        zoneId: ZoneId,
        duration: Duration = Duration.ofHours(72),
    ): List<LocalDate> {
        require(!duration.isNegative && !duration.isZero) { "duration must be positive" }
        val firstDate = now.minus(duration).atZone(zoneId).toLocalDate()
        val lastDate = now.atZone(zoneId).toLocalDate()
        return generateSequence(firstDate) { current ->
            current.plusDays(1).takeUnless { it.isAfter(lastDate) }
        }.toList()
    }

    fun previousDays(
        now: Instant,
        zoneId: ZoneId,
        days: Long,
    ): List<LocalDate> {
        require(days > 0) { "days must be positive" }
        val lastDate = now.atZone(zoneId).toLocalDate()
        val firstDate = lastDate.minusDays(days - 1)
        return generateSequence(firstDate) { current ->
            current.plusDays(1).takeUnless { it.isAfter(lastDate) }
        }.toList()
    }
}
