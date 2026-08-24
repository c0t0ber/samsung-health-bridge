package com.roktober.samsunghealthbridge.health

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.roktober.samsunghealthbridge.model.RawHealthRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Converts the permitted Health Connect records into stable, Sheets-friendly rows. */
internal object RawHealthRecordNormalizer {
    fun normalize(record: StepsRecord, exportedAt: Instant): RawHealthRecord =
        RawHealthRecord(
            recordKey = key("steps", record.metadata),
            recordType = "steps",
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset?.id,
            value = record.count,
            unit = "count",
            subtype = null,
            dataOrigin = record.metadata.dataOrigin.packageName,
            clientRecordId = record.metadata.clientRecordId,
            lastModifiedTime = record.metadata.lastModifiedTime,
            detailsJson =
                baseDetails(record.metadata)
                    .putNullable("end_zone_offset", record.endZoneOffset?.id)
                    .toString(),
            exportedAt = exportedAt,
        )

    fun normalize(record: SleepSessionRecord, exportedAt: Instant): RawHealthRecord =
        RawHealthRecord(
            recordKey = key("sleep_session", record.metadata),
            recordType = "sleep_session",
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset?.id,
            value = null,
            unit = null,
            subtype = null,
            dataOrigin = record.metadata.dataOrigin.packageName,
            clientRecordId = record.metadata.clientRecordId,
            lastModifiedTime = record.metadata.lastModifiedTime,
            detailsJson =
                baseDetails(record.metadata)
                    .putNullable("end_zone_offset", record.endZoneOffset?.id)
                    .putNullable("title", record.title)
                    .putNullable("notes", record.notes)
                    .put(
                        "stages",
                        JSONArray().apply {
                            record.stages.forEach { stage ->
                                put(
                                    JSONObject()
                                        .put("start_time", stage.startTime.toString())
                                        .put("end_time", stage.endTime.toString())
                                        .put("stage_type", stage.stage),
                                )
                            }
                        },
                    ).toString(),
            exportedAt = exportedAt,
        )

    fun normalize(record: ExerciseSessionRecord, exportedAt: Instant): RawHealthRecord =
        RawHealthRecord(
            recordKey = key("exercise_session", record.metadata),
            recordType = "exercise_session",
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset?.id,
            value = null,
            unit = null,
            subtype = record.exerciseType.toString(),
            dataOrigin = record.metadata.dataOrigin.packageName,
            clientRecordId = record.metadata.clientRecordId,
            lastModifiedTime = record.metadata.lastModifiedTime,
            detailsJson =
                baseDetails(record.metadata)
                    .putNullable("end_zone_offset", record.endZoneOffset?.id)
                    .put("exercise_type", record.exerciseType)
                    .putNullable("title", record.title)
                    .putNullable("notes", record.notes)
                    .putNullable("planned_exercise_session_id", record.plannedExerciseSessionId)
                    .put("route_exported", false)
                    .put(
                        "segments",
                        JSONArray().apply {
                            record.segments.forEach { segment ->
                                put(
                                    JSONObject()
                                        .put("start_time", segment.startTime.toString())
                                        .put("end_time", segment.endTime.toString())
                                        .put("segment_type", segment.segmentType)
                                        .put("repetitions", segment.repetitions),
                                )
                            }
                        },
                    ).put(
                        "laps",
                        JSONArray().apply {
                            record.laps.forEach { lap ->
                                put(
                                    JSONObject()
                                        .put("start_time", lap.startTime.toString())
                                        .put("end_time", lap.endTime.toString())
                                        .putNullable("length_meters", lap.length?.inMeters),
                                )
                            }
                        },
                    ).toString(),
            exportedAt = exportedAt,
        )

    fun normalize(record: WeightRecord, exportedAt: Instant): RawHealthRecord =
        RawHealthRecord(
            recordKey = key("weight", record.metadata),
            recordType = "weight",
            startTime = record.time,
            endTime = null,
            zoneOffset = record.zoneOffset?.id,
            value = record.weight.inKilograms,
            unit = "kg",
            subtype = null,
            dataOrigin = record.metadata.dataOrigin.packageName,
            clientRecordId = record.metadata.clientRecordId,
            lastModifiedTime = record.metadata.lastModifiedTime,
            detailsJson = baseDetails(record.metadata).toString(),
            exportedAt = exportedAt,
        )

    fun normalize(record: BodyFatRecord, exportedAt: Instant): RawHealthRecord =
        RawHealthRecord(
            recordKey = key("body_fat", record.metadata),
            recordType = "body_fat",
            startTime = record.time,
            endTime = null,
            zoneOffset = record.zoneOffset?.id,
            value = record.percentage.value,
            unit = "percent",
            subtype = null,
            dataOrigin = record.metadata.dataOrigin.packageName,
            clientRecordId = record.metadata.clientRecordId,
            lastModifiedTime = record.metadata.lastModifiedTime,
            detailsJson = baseDetails(record.metadata).toString(),
            exportedAt = exportedAt,
        )

    private fun key(type: String, metadata: Metadata): String = "$type:${metadata.id}"

    private fun baseDetails(metadata: Metadata): JSONObject =
        JSONObject()
            .put("recording_method", metadata.recordingMethod)
            .put("client_record_version", metadata.clientRecordVersion)
            .put(
                "device",
                metadata.device?.let { device ->
                    JSONObject()
                        .put("type", device.type)
                        .putNullable("manufacturer", device.manufacturer)
                        .putNullable("model", device.model)
                } ?: JSONObject.NULL,
            )

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)
}
