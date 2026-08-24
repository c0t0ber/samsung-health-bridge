package com.roktober.samsunghealthbridge.model

import java.time.Instant
import java.time.LocalDate

data class SheetRow(
    val date: LocalDate,
    val timezone: String,
    val weightKg: Double?,
    val bodyFatPercent: Double?,
    val steps: Long?,
    val activeMinutes: Long?,
    val workoutCount: Int,
    val sleepMinutes: Long?,
    val syncedAt: Instant,
    val sourceStatus: String,
) {
    fun toCells(): List<Any?> =
        listOf(
            date.toString(),
            timezone,
            weightKg,
            bodyFatPercent,
            steps,
            activeMinutes,
            workoutCount,
            sleepMinutes,
            syncedAt.toString(),
            sourceStatus,
        )

    fun preview(): String =
        buildString {
            append(date)
            append(" · ")
            append(steps ?: "—")
            append(" steps · ")
            append(activeMinutes ?: "—")
            append(" active min · ")
            append(sleepMinutes ?: "—")
            append(" sleep min")
            weightKg?.let { append(" · %.1f kg".format(it)) }
            bodyFatPercent?.let { append(" · %.1f%% fat".format(it)) }
        }

    companion object {
        val HEADER: List<String> =
            listOf(
                "date",
                "timezone",
                "weight_kg",
                "body_fat_percent",
                "steps",
                "active_minutes",
                "workout_count",
                "sleep_minutes",
                "synced_at",
                "source_status",
            )
    }
}
