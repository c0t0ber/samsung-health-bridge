package com.roktober.samsunghealthbridge.health

import com.roktober.samsunghealthbridge.model.RawHealthRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class HistoricalDayZone(
    val zoneId: ZoneId,
    val qualityFlags: Set<String>,
)

data class DayZoneObservation(
    val startTime: Instant,
    val zoneOffset: ZoneOffset?,
)

object HistoricalDayZoneResolver {
    fun resolve(
        records: List<RawHealthRecord>,
        fallbackZone: ZoneId,
    ): Map<LocalDate, HistoricalDayZone> =
        resolveObservations(
            observations =
                records.map { record ->
                    DayZoneObservation(
                        startTime = record.startTime,
                        zoneOffset =
                            record.zoneOffset?.let { value ->
                                runCatching { ZoneOffset.of(value) }.getOrNull()
                            },
                    )
                },
            fallbackZone = fallbackZone,
        )

    fun resolveObservations(
        observations: List<DayZoneObservation>,
        fallbackZone: ZoneId,
    ): Map<LocalDate, HistoricalDayZone> =
        observations
            .groupBy { observation -> observation.localDate(fallbackZone) }
            .mapValues { (_, dayObservations) ->
                val observedOffsets = dayObservations.mapNotNull(DayZoneObservation::zoneOffset)
                val dominantOffset =
                    observedOffsets.groupingBy { it }.eachCount().entries
                        .maxWithOrNull(
                            compareBy<Map.Entry<ZoneOffset, Int>> { it.value }
                                .thenBy { it.key.totalSeconds },
                        )?.key
                if (dominantOffset == null) {
                    HistoricalDayZone(
                        zoneId = fallbackZone,
                        qualityFlags = setOf("timezone_fallback"),
                    )
                } else {
                    HistoricalDayZone(
                        zoneId = dominantOffset,
                        qualityFlags =
                            if (observedOffsets.distinct().size == 1) {
                                setOf("timezone_record_offset")
                            } else {
                                setOf("timezone_mixed_offsets")
                            },
                    )
                }
            }

    private fun DayZoneObservation.localDate(fallbackZone: ZoneId): LocalDate =
        zoneOffset?.let(startTime::atOffset)?.toLocalDate()
            ?: startTime.atZone(fallbackZone).toLocalDate()
}
