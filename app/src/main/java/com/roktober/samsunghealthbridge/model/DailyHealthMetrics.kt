package com.roktober.samsunghealthbridge.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DailyHealthMetrics(
    val date: LocalDate,
    val zoneId: ZoneId,
    val weightKg: Double?,
    val bodyFatPercent: Double?,
    val steps: Long?,
    val exerciseDuration: Duration?,
    val workoutCount: Int,
    val sleepDuration: Duration?,
    val contributingPackages: Set<String> = emptySet(),
    val qualityFlags: Set<String> = emptySet(),
) {
    fun toSheetRow(syncedAt: Instant): SheetRow {
        val activeMinutes = exerciseDuration?.toMinutes()
        val sleepMinutes = sleepDuration?.toMinutes()
        val hasData =
            weightKg != null ||
                bodyFatPercent != null ||
                steps != null ||
                exerciseDuration != null ||
                workoutCount > 0 ||
                sleepDuration != null
        val missingMetrics =
            buildList {
                if (weightKg == null) add("weight_kg")
                if (bodyFatPercent == null) add("body_fat_percent")
                if (steps == null) add("steps")
                if (exerciseDuration == null) add("active_minutes")
                if (sleepDuration == null) add("sleep_minutes")
            }
        val baseStatus =
            when {
                !hasData -> "no_data:health_connect"
                missingMetrics.isEmpty() -> "ok:health_connect"
                else -> "partial:health_connect;missing=${missingMetrics.joinToString(",")}"
            }
        val sourceStatus =
            if (qualityFlags.isEmpty()) {
                baseStatus
            } else {
                "$baseStatus;flags=${qualityFlags.sorted().joinToString(",")}"
            }

        return SheetRow(
            date = date,
            timezone = zoneId.id,
            weightKg = weightKg,
            bodyFatPercent = bodyFatPercent,
            steps = steps,
            activeMinutes = activeMinutes,
            workoutCount = workoutCount,
            sleepMinutes = sleepMinutes,
            syncedAt = syncedAt,
            sourceStatus = sourceStatus,
        )
    }

}
