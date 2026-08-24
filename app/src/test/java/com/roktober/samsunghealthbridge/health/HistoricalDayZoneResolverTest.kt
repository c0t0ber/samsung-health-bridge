package com.roktober.samsunghealthbridge.health

import com.roktober.samsunghealthbridge.model.RawHealthRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricalDayZoneResolverTest {
    @Test
    fun `uses each records offset when travel changes the experienced local day`() {
        val resolved =
            HistoricalDayZoneResolver.resolve(
                records =
                    listOf(
                        record("sofia", "2026-08-10T12:00:00Z", "+03:00"),
                        record("shanghai", "2026-08-10T20:00:00Z", "+08:00"),
                    ),
                fallbackZone = ZoneId.of("Asia/Shanghai"),
            )

        assertEquals(ZoneOffset.of("+03:00"), resolved.getValue(LocalDate.parse("2026-08-10")).zoneId)
        assertEquals(ZoneOffset.of("+08:00"), resolved.getValue(LocalDate.parse("2026-08-11")).zoneId)
        assertEquals(
            setOf("timezone_record_offset"),
            resolved.getValue(LocalDate.parse("2026-08-10")).qualityFlags,
        )
    }

    @Test
    fun `marks a travel day with multiple offsets and uses the dominant offset`() {
        val resolved =
            HistoricalDayZoneResolver.resolve(
                records =
                    listOf(
                        record("sofia-1", "2026-08-10T10:00:00Z", "+03:00"),
                        record("sofia-2", "2026-08-10T12:00:00Z", "+03:00"),
                        record("shanghai", "2026-08-10T14:00:00Z", "+08:00"),
                    ),
                fallbackZone = ZoneId.of("Asia/Shanghai"),
            ).getValue(LocalDate.parse("2026-08-10"))

        assertEquals(ZoneOffset.of("+03:00"), resolved.zoneId)
        assertEquals(setOf("timezone_mixed_offsets"), resolved.qualityFlags)
    }

    @Test
    fun `resolves lightweight step observations for rolling sync`() {
        val resolved =
            HistoricalDayZoneResolver.resolveObservations(
                observations =
                    listOf(
                        DayZoneObservation(
                            startTime = Instant.parse("2026-08-20T09:00:00Z"),
                            zoneOffset = ZoneOffset.of("+03:00"),
                        ),
                    ),
                fallbackZone = ZoneId.of("Asia/Shanghai"),
            ).getValue(LocalDate.parse("2026-08-20"))

        assertEquals(ZoneOffset.of("+03:00"), resolved.zoneId)
        assertEquals(setOf("timezone_record_offset"), resolved.qualityFlags)
    }

    private fun record(key: String, startTime: String, offset: String?) =
        RawHealthRecord(
            recordKey = key,
            recordType = "steps",
            startTime = Instant.parse(startTime),
            endTime = Instant.parse(startTime).plusSeconds(60),
            zoneOffset = offset,
            value = 100,
            unit = "count",
            subtype = null,
            dataOrigin = "android",
            clientRecordId = null,
            lastModifiedTime = Instant.EPOCH,
            detailsJson = null,
            exportedAt = Instant.EPOCH,
        )
}
