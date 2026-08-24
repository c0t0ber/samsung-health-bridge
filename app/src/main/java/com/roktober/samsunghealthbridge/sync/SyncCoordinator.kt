package com.roktober.samsunghealthbridge.sync

import com.google.android.gms.common.api.ApiException
import com.roktober.samsunghealthbridge.google.GoogleAuthorizationManager
import com.roktober.samsunghealthbridge.google.GoogleAuthorizationState
import com.roktober.samsunghealthbridge.health.HealthConnectRepository
import com.roktober.samsunghealthbridge.health.HealthConnectSdkStatus
import com.roktober.samsunghealthbridge.model.RollingWindow
import com.roktober.samsunghealthbridge.model.SheetRow
import com.roktober.samsunghealthbridge.sheets.SheetsApi
import com.roktober.samsunghealthbridge.sheets.SheetsApiException
import com.roktober.samsunghealthbridge.storage.AppPreferences
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncMode {
    Rolling72Hours,
    InitialHistory90Days,
}

data class SyncSuccess(
    val spreadsheetId: String,
    val rows: List<SheetRow>,
    val syncedAt: Instant,
)

data class CompleteExportSuccess(
    val spreadsheetId: String,
    val rawRecordCount: Int,
    val aggregateRowCount: Int,
    val dailyRowCount: Int,
    val verifiedRawRowCount: Int,
    val exportedAt: Instant,
    val completeHistory: Boolean,
)

sealed interface BackgroundSyncResult {
    data class Completed(val rowCount: Int) : BackgroundSyncResult

    data object NeedsUserAction : BackgroundSyncResult

    data object RetryLater : BackgroundSyncResult

    data object PermanentFailure : BackgroundSyncResult
}

class SyncCoordinator(
    private val healthRepository: HealthConnectRepository,
    private val authorizationManager: GoogleAuthorizationManager,
    private val sheetsApi: SheetsApi,
    private val preferences: AppPreferences,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
) {
    private val syncMutex = Mutex()

    suspend fun authorizeGoogle(): GoogleAuthorizationState = authorizationManager.authorize()

    suspend fun connectGoogleSheet(accessToken: String): String =
        syncMutex.withLock {
            try {
                ensureSpreadsheet(accessToken)
            } finally {
                authorizationManager.forgetInMemoryToken()
            }
        }

    suspend fun sync(
        accessToken: String,
        mode: SyncMode,
        requireBackgroundPermission: Boolean = false,
    ): SyncSuccess =
        syncMutex.withLock {
            try {
                val permissionStatus = healthRepository.getPermissionStatus()
                when (permissionStatus.sdkStatus) {
                    HealthConnectSdkStatus.Available -> Unit
                    HealthConnectSdkStatus.ProviderUpdateRequired ->
                        throw SyncBlockedException.HealthConnectUpdateRequired
                    HealthConnectSdkStatus.Unavailable ->
                        throw SyncBlockedException.HealthConnectUnavailable
                }
                if (!permissionStatus.coreReadGranted) {
                    throw SyncBlockedException.HealthPermissionsRequired
                }
                if (requireBackgroundPermission &&
                    (!permissionStatus.backgroundReadAvailable || !permissionStatus.backgroundReadGranted)
                ) {
                    throw SyncBlockedException.BackgroundPermissionRequired
                }
                if (mode == SyncMode.InitialHistory90Days &&
                    (!permissionStatus.historyReadAvailable || !permissionStatus.historyReadGranted)
                ) {
                    throw SyncBlockedException.HistoryPermissionRequired
                }

                val now = clock.instant()
                val zoneId = zoneIdProvider()
                val dates =
                    when (mode) {
                        SyncMode.Rolling72Hours -> RollingWindow.datesTouchedBy(now, zoneId)
                        SyncMode.InitialHistory90Days -> RollingWindow.previousDays(now, zoneId, 90)
                    }
                val spreadsheetId = ensureSpreadsheet(accessToken)
                val rows =
                    healthRepository.readDailyMetrics(dates, zoneId)
                        .map { it.toSheetRow(now) }
                val result = sheetsApi.upsertRows(accessToken, spreadsheetId, rows)

                preferences.lastSyncAt = now
                preferences.lastStatus = AppPreferences.STATUS_OK
                if (mode == SyncMode.InitialHistory90Days) {
                    preferences.historyImportedAt = now
                }
                SyncSuccess(
                    spreadsheetId = result.spreadsheetId,
                    rows = result.readbackRows,
                    syncedAt = now,
                )
            } finally {
                authorizationManager.forgetInMemoryToken()
            }
        }

    /**
     * Foreground-only diagnostic export of every accessible record for the five permitted Health
     * Connect types, plus every aggregate metric exposed for those types by connect-client 1.1.0.
     */
    suspend fun exportAllHealthData(accessToken: String): CompleteExportSuccess =
        syncMutex.withLock {
            try {
                val permissionStatus = healthRepository.getPermissionStatus()
                when (permissionStatus.sdkStatus) {
                    HealthConnectSdkStatus.Available -> Unit
                    HealthConnectSdkStatus.ProviderUpdateRequired ->
                        throw SyncBlockedException.HealthConnectUpdateRequired
                    HealthConnectSdkStatus.Unavailable ->
                        throw SyncBlockedException.HealthConnectUnavailable
                }
                if (!permissionStatus.coreReadGranted) {
                    throw SyncBlockedException.HealthPermissionsRequired
                }
                if (permissionStatus.historyReadAvailable && !permissionStatus.historyReadGranted) {
                    throw SyncBlockedException.HistoryPermissionRequired
                }

                val exportedAt = clock.instant()
                val spreadsheetId = ensureSpreadsheet(accessToken)
                sheetsApi.initializeRawSheet(accessToken, spreadsheetId)

                val export =
                    healthRepository.readAllHealthData(
                        exportedAt = exportedAt,
                        zoneId = zoneIdProvider(),
                    )
                val dailyRows = export.dailyMetrics.map { it.toSheetRow(exportedAt) }
                val dailyResult = sheetsApi.upsertRows(accessToken, spreadsheetId, dailyRows)
                val rawResult = sheetsApi.upsertRawRows(accessToken, spreadsheetId, export.rows)

                preferences.lastSyncAt = exportedAt
                preferences.lastStatus = AppPreferences.STATUS_OK
                if (permissionStatus.historyReadGranted) {
                    preferences.historyImportedAt = exportedAt
                }

                CompleteExportSuccess(
                    spreadsheetId = spreadsheetId,
                    rawRecordCount = export.rawRecordCount,
                    aggregateRowCount = export.aggregateRecordCount,
                    dailyRowCount = dailyResult.readbackRows.size,
                    verifiedRawRowCount = rawResult.readbackRows.size,
                    exportedAt = exportedAt,
                    completeHistory =
                        permissionStatus.historyReadAvailable && permissionStatus.historyReadGranted,
                )
            } finally {
                authorizationManager.forgetInMemoryToken()
            }
        }

    suspend fun runBackgroundSync(): BackgroundSyncResult {
        val permissionStatus = runCatching { healthRepository.getPermissionStatus() }.getOrElse {
            preferences.lastStatus = AppPreferences.STATUS_ERROR
            return BackgroundSyncResult.RetryLater
        }
        if (permissionStatus.sdkStatus != HealthConnectSdkStatus.Available ||
            !permissionStatus.coreReadGranted ||
            !permissionStatus.backgroundReadAvailable ||
            !permissionStatus.backgroundReadGranted
        ) {
            preferences.lastStatus = AppPreferences.STATUS_HEALTH_PERMISSION_REQUIRED
            return BackgroundSyncResult.NeedsUserAction
        }

        val authorization =
            try {
                authorizationManager.authorize()
            } catch (_: ApiException) {
                preferences.lastStatus = AppPreferences.STATUS_GOOGLE_ACTION_REQUIRED
                return BackgroundSyncResult.NeedsUserAction
            } catch (_: Exception) {
                preferences.lastStatus = AppPreferences.STATUS_ERROR
                return BackgroundSyncResult.RetryLater
            }
        if (authorization is GoogleAuthorizationState.NeedsResolution) {
            preferences.lastStatus = AppPreferences.STATUS_GOOGLE_ACTION_REQUIRED
            return BackgroundSyncResult.NeedsUserAction
        }

        val token = (authorization as GoogleAuthorizationState.Authorized).accessToken
        return try {
            val success = sync(token, SyncMode.Rolling72Hours, requireBackgroundPermission = true)
            BackgroundSyncResult.Completed(success.rows.size)
        } catch (error: SheetsApiException) {
            if (error.isUnauthorized) {
                runCatching { authorizationManager.clearInvalidToken(token) }
                preferences.lastStatus = AppPreferences.STATUS_GOOGLE_ACTION_REQUIRED
                BackgroundSyncResult.NeedsUserAction
            } else if (error.isTransient) {
                preferences.lastStatus = AppPreferences.STATUS_ERROR
                BackgroundSyncResult.RetryLater
            } else {
                preferences.lastStatus = AppPreferences.STATUS_ERROR
                BackgroundSyncResult.NeedsUserAction
            }
        } catch (_: SyncBlockedException) {
            preferences.lastStatus = AppPreferences.STATUS_HEALTH_PERMISSION_REQUIRED
            BackgroundSyncResult.NeedsUserAction
        } catch (_: SecurityException) {
            preferences.lastStatus = AppPreferences.STATUS_HEALTH_PERMISSION_REQUIRED
            BackgroundSyncResult.NeedsUserAction
        } catch (_: Exception) {
            preferences.lastStatus = AppPreferences.STATUS_ERROR
            BackgroundSyncResult.PermanentFailure
        }
    }

    private suspend fun ensureSpreadsheet(accessToken: String): String {
        val existingId = preferences.spreadsheetId
        if (existingId != null) {
            sheetsApi.initializeSpreadsheet(accessToken, existingId)
            return existingId
        }

        val created = sheetsApi.createSpreadsheet(accessToken)
        // Persist immediately after create so a later header/network failure cannot orphan a file
        // and cause a second spreadsheet to be created on the next attempt.
        preferences.spreadsheetId = created.spreadsheetId
        sheetsApi.initializeSpreadsheet(accessToken, created.spreadsheetId)
        return created.spreadsheetId
    }

    private val SheetsApiException.isTransient: Boolean
        get() = httpStatus == null || httpStatus == 408 || httpStatus == 429 || (httpStatus ?: 0) >= 500
}

sealed class SyncBlockedException(message: String) : IllegalStateException(message) {
    data object HealthConnectUnavailable : SyncBlockedException("Health Connect is unavailable")

    data object HealthConnectUpdateRequired :
        SyncBlockedException("Health Connect must be installed or updated")

    data object HealthPermissionsRequired :
        SyncBlockedException("Health Connect read permissions are required")

    data object BackgroundPermissionRequired :
        SyncBlockedException("Background Health Connect permission is required")

    data object HistoryPermissionRequired :
        SyncBlockedException("Health Connect history permission is required")
}
