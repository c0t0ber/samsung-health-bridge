package com.roktober.samsunghealthbridge.storage

import android.annotation.SuppressLint
import android.content.Context
import java.time.Instant

@SuppressLint("UseKtx") // spreadsheetId requires checking synchronous commit() success.
class AppPreferences(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var spreadsheetId: String?
        get() = preferences.getString(KEY_SPREADSHEET_ID, null)
        set(value) {
            val stored = preferences.edit().apply {
                if (value == null) remove(KEY_SPREADSHEET_ID) else putString(KEY_SPREADSHEET_ID, value)
            }.commit()
            check(stored) { "Could not persist spreadsheet ID" }
        }

    var lastSyncAt: Instant?
        get() =
            preferences.getString(KEY_LAST_SYNC_AT, null)?.let { stored ->
                runCatching { Instant.parse(stored) }.getOrNull()
            }
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_LAST_SYNC_AT) else putString(KEY_LAST_SYNC_AT, value.toString())
            }.apply()
        }

    var lastStatus: String
        get() = preferences.getString(KEY_LAST_STATUS, STATUS_NEVER) ?: STATUS_NEVER
        set(value) {
            preferences.edit().putString(KEY_LAST_STATUS, value).apply()
        }

    var historyImportedAt: Instant?
        get() =
            preferences.getString(KEY_HISTORY_IMPORTED_AT, null)?.let { stored ->
                runCatching { Instant.parse(stored) }.getOrNull()
            }
        set(value) {
            preferences.edit().apply {
                if (value == null) {
                    remove(KEY_HISTORY_IMPORTED_AT)
                } else {
                    putString(KEY_HISTORY_IMPORTED_AT, value.toString())
                }
            }.apply()
        }

    companion object {
        const val STATUS_NEVER = "never"
        const val STATUS_OK = "ok"
        const val STATUS_HEALTH_PERMISSION_REQUIRED = "health_permission_required"
        const val STATUS_GOOGLE_ACTION_REQUIRED = "google_action_required"
        const val STATUS_ERROR = "error"

        private const val PREFERENCES_NAME = "bridge_state"
        private const val KEY_SPREADSHEET_ID = "spreadsheet_id"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_HISTORY_IMPORTED_AT = "history_imported_at"
    }
}
