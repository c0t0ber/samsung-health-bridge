package com.roktober.samsunghealthbridge.storage

/** Selects the configured Sheet without overwriting a deliberate user-selected target. */
class SpreadsheetTargetResolver(
    private val canonicalId: String?,
    private val legacyIds: Set<String>,
) {
    fun resolve(storedId: String?): String? {
        val canonical = canonicalId?.takeIf(String::isNotBlank) ?: return storedId
        return if (storedId == null || storedId in legacyIds) canonical else storedId
    }
}
