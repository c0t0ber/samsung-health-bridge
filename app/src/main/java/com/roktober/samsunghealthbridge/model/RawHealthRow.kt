package com.roktober.samsunghealthbridge.model

import java.time.Instant

/** A stable, normalized representation of one raw Health Connect record. */
data class RawHealthRecord(
    val recordKey: String,
    val recordType: String,
    val startTime: Instant,
    val endTime: Instant?,
    val zoneOffset: String?,
    val value: Number?,
    val unit: String?,
    val subtype: String?,
    val dataOrigin: String,
    val clientRecordId: String?,
    val lastModifiedTime: Instant,
    val detailsJson: String?,
    val exportedAt: Instant,
) {
    init {
        require(recordKey.isNotBlank()) { "recordKey must not be blank" }
        require(recordType.isNotBlank()) { "recordType must not be blank" }
        require(dataOrigin.isNotBlank()) { "dataOrigin must not be blank" }
    }

    /**
     * Sheets values intentionally use an empty string for missing optional fields. Updating an
     * existing row therefore clears values that disappeared instead of leaving stale cell data.
     */
    fun toCells(): List<Any> =
        listOf(
            recordKey,
            recordType,
            startTime.toString(),
            endTime?.toString().orEmpty(),
            zoneOffset.orEmpty(),
            value ?: "",
            unit.orEmpty(),
            subtype.orEmpty(),
            dataOrigin,
            clientRecordId.orEmpty(),
            lastModifiedTime.toString(),
            detailsJson.orEmpty(),
            exportedAt.toString(),
        )

    companion object {
        val HEADER: List<String> =
            listOf(
                "record_key",
                "type",
                "start_time",
                "end_time",
                "timezone_offset",
                "value",
                "unit",
                "subtype",
                "data_origin",
                "client_record_id",
                "last_modified_time",
                "details_json",
                "exported_at",
            )
    }
}
