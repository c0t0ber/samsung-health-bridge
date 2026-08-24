package com.roktober.samsunghealthbridge

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.setPadding
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ApiException
import com.roktober.samsunghealthbridge.google.GoogleAuthorizationState
import com.roktober.samsunghealthbridge.health.HealthConnectPermissionStatus
import com.roktober.samsunghealthbridge.health.HealthConnectRepository
import com.roktober.samsunghealthbridge.health.HealthConnectSdkStatus
import com.roktober.samsunghealthbridge.sheets.DuplicateRawRecordKeyException
import com.roktober.samsunghealthbridge.sheets.DuplicateSheetDateException
import com.roktober.samsunghealthbridge.sheets.SheetsApiException
import com.roktober.samsunghealthbridge.storage.AppPreferences
import com.roktober.samsunghealthbridge.sync.SyncBlockedException
import com.roktober.samsunghealthbridge.sync.SyncMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@SuppressLint("SetTextI18n") // This personal MVP intentionally ships a single English UI.
class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as BridgeApplication).container

    private lateinit var healthStatusText: TextView
    private lateinit var googleStatusText: TextView
    private lateinit var lastSyncText: TextView
    private lateinit var previewText: TextView
    private lateinit var messageText: TextView
    private lateinit var healthPermissionButton: Button
    private lateinit var backgroundPermissionButton: Button
    private lateinit var connectGoogleButton: Button
    private lateinit var openSheetButton: Button
    private lateinit var syncButton: Button
    private lateinit var importHistoryButton: Button
    private lateinit var exportAllButton: Button
    private var busy = false
    private var pendingHealthAction: PendingHealthAction? = null
    private var pendingGoogleAction: PendingGoogleAction? = null

    private val healthPermissionLauncher =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(
                HealthConnectRepository.PROVIDER_PACKAGE_NAME,
            ),
        ) {
            val pending = pendingHealthAction
            pendingHealthAction = null
            lifecycleScope.launch {
                setBusy(false)
                val status = runCatching { container.healthRepository.getPermissionStatus() }.getOrNull()
                refreshStatus()
                when (pending) {
                    is PendingHealthAction.SyncAfterGrant -> {
                        val ready =
                            status?.coreReadGranted == true &&
                                (pending.mode != SyncMode.InitialHistory90Days ||
                                    status.historyReadGranted)
                        if (ready) startSync(pending.mode) else showMessage("Required Health Connect permission was not granted.")
                    }
                    PendingHealthAction.ExportAllAfterGrant -> {
                        val ready =
                            status?.coreReadGranted == true &&
                                (!status.historyReadAvailable || status.historyReadGranted)
                        if (ready) {
                            startCompleteExport()
                        } else {
                            showMessage("Required Health Connect permission was not granted.")
                        }
                    }
                    PendingHealthAction.Background -> {
                        if (status?.backgroundReadGranted == true) {
                            showMessage("Background health access is ready.", isError = false)
                        } else {
                            showMessage("Background health access was not granted.")
                        }
                    }
                    PendingHealthAction.RefreshOnly,
                    null,
                    -> Unit
                }
            }
        }

    private val googleResolutionLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val action = pendingGoogleAction
            pendingGoogleAction = null
            if (action == null) {
                setBusy(false)
                showMessage("Google authorization result was interrupted. Tap Connect Google Sheet again.")
                return@registerForActivityResult
            }
            val resultData = result.data
            if (resultData == null) {
                setBusy(false)
                showMessage(
                    if (result.resultCode == Activity.RESULT_CANCELED) {
                        "Google did not authorize this app. If you did not press Back, complete the one-time Android OAuth setup from the README."
                    } else {
                        "Google authorization returned no result. Complete the one-time Android OAuth setup from the README."
                    },
                )
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                try {
                    // Google Play services can return an API error in the Intent even when the
                    // activity result code is not RESULT_OK. Parse every non-null result so a
                    // configuration failure is not mislabeled as a user cancellation.
                    when (val authorization = container.authorizationManager.parseAuthorizationResult(resultData)) {
                        is GoogleAuthorizationState.Authorized ->
                            executeGoogleAction(action, authorization.accessToken)
                        is GoogleAuthorizationState.NeedsResolution -> {
                            setBusy(false)
                            showMessage("Google authorization needs another confirmation. Tap again.")
                        }
                    }
                } catch (error: Exception) {
                    setBusy(false)
                    showMessage(friendlyMessage(error))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        bindActions()
        lifecycleScope.launch { refreshStatus() }
    }

    override fun onResume() {
        super.onResume()
        if (::healthStatusText.isInitialized) {
            lifecycleScope.launch { refreshStatus() }
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24))
            }
        content.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
            },
            rowParams(bottom = dp(8)),
        )
        content.addView(
            TextView(this).apply {
                text = "Health Connect → one private Google Sheet"
                textSize = 16f
            },
            rowParams(bottom = dp(24)),
        )

        content.addView(sectionTitle("Health Connect"), rowParams(bottom = dp(6)))
        healthStatusText = statusText()
        content.addView(healthStatusText, rowParams(bottom = dp(10)))
        healthPermissionButton = actionButton("Grant health permissions")
        content.addView(healthPermissionButton, rowParams(bottom = dp(8)))
        backgroundPermissionButton = actionButton("Allow background sync")
        content.addView(backgroundPermissionButton, rowParams(bottom = dp(20)))

        content.addView(sectionTitle("Google Sheet"), rowParams(bottom = dp(6)))
        googleStatusText = statusText()
        content.addView(googleStatusText, rowParams(bottom = dp(10)))
        connectGoogleButton = actionButton(getString(R.string.connect_google_sheet))
        content.addView(connectGoogleButton, rowParams(bottom = dp(8)))
        openSheetButton = actionButton("Open Google Sheet")
        content.addView(openSheetButton, rowParams(bottom = dp(20)))

        content.addView(sectionTitle("Last sync"), rowParams(bottom = dp(6)))
        lastSyncText = statusText()
        content.addView(lastSyncText, rowParams(bottom = dp(14)))
        syncButton = actionButton(getString(R.string.sync_now))
        content.addView(syncButton, rowParams(bottom = dp(8)))
        importHistoryButton = actionButton(getString(R.string.import_history))
        content.addView(importHistoryButton, rowParams(bottom = dp(8)))
        exportAllButton = actionButton("Export all raw data + aggregates")
        content.addView(exportAllButton, rowParams(bottom = dp(20)))

        content.addView(sectionTitle(getString(R.string.daily_preview)), rowParams(bottom = dp(6)))
        previewText =
            statusText().apply {
                text = "No in-memory preview yet."
            }
        content.addView(previewText, rowParams(bottom = dp(14)))
        messageText =
            statusText().apply {
                visibility = View.GONE
                setTypeface(typeface, Typeface.BOLD)
            }
        content.addView(messageText, rowParams(bottom = dp(24)))

        setContentView(
            ScrollView(this).apply {
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }

    private fun bindActions() {
        healthPermissionButton.setOnClickListener {
            lifecycleScope.launch {
                val status = container.healthRepository.getPermissionStatus()
                if (status.sdkStatus == HealthConnectSdkStatus.Available) {
                    requestHealthPermissions(
                        container.healthRepository.requestedReadPermissions(
                            includeBackground = false,
                            includeHistory = false,
                        ),
                        PendingHealthAction.RefreshOnly,
                    )
                } else {
                    openHealthConnectStore()
                }
            }
        }
        backgroundPermissionButton.setOnClickListener {
            requestHealthPermissions(
                setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND),
                PendingHealthAction.Background,
            )
        }
        connectGoogleButton.setOnClickListener {
            startGoogleAction(PendingGoogleAction.ConnectSheet)
        }
        openSheetButton.setOnClickListener { openSpreadsheet() }
        syncButton.setOnClickListener { startSync(SyncMode.Rolling72Hours) }
        importHistoryButton.setOnClickListener { startSync(SyncMode.InitialHistory90Days) }
        exportAllButton.setOnClickListener { startCompleteExport() }
    }

    private fun startSync(mode: SyncMode) {
        lifecycleScope.launch {
            setBusy(true)
            try {
                val status = container.healthRepository.getPermissionStatus()
                when (status.sdkStatus) {
                    HealthConnectSdkStatus.Available -> Unit
                    HealthConnectSdkStatus.ProviderUpdateRequired -> {
                        setBusy(false)
                        showMessage("Install or update Health Connect, then return to the app.")
                        return@launch
                    }
                    HealthConnectSdkStatus.Unavailable -> {
                        setBusy(false)
                        showMessage("Health Connect is unavailable on this device.")
                        return@launch
                    }
                }
                if (!status.coreReadGranted) {
                    setBusy(false)
                    requestHealthPermissions(
                        container.healthRepository.requestedReadPermissions(
                            includeBackground = false,
                            includeHistory = false,
                        ),
                        PendingHealthAction.SyncAfterGrant(mode),
                    )
                    return@launch
                }
                if (mode == SyncMode.InitialHistory90Days) {
                    if (!status.historyReadAvailable) {
                        setBusy(false)
                        showMessage("This Health Connect version does not support history access.")
                        return@launch
                    }
                    if (!status.historyReadGranted) {
                        setBusy(false)
                        requestHealthPermissions(
                            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY),
                            PendingHealthAction.SyncAfterGrant(mode),
                        )
                        return@launch
                    }
                }
                startGoogleAction(PendingGoogleAction.Sync(mode), alreadyBusy = true)
            } catch (error: Exception) {
                setBusy(false)
                showMessage(friendlyMessage(error))
            }
        }
    }

    private fun startCompleteExport() {
        lifecycleScope.launch {
            setBusy(true)
            try {
                val status = container.healthRepository.getPermissionStatus()
                when (status.sdkStatus) {
                    HealthConnectSdkStatus.Available -> Unit
                    HealthConnectSdkStatus.ProviderUpdateRequired -> {
                        setBusy(false)
                        showMessage("Install or update Health Connect, then return to the app.")
                        return@launch
                    }
                    HealthConnectSdkStatus.Unavailable -> {
                        setBusy(false)
                        showMessage("Health Connect is unavailable on this device.")
                        return@launch
                    }
                }
                if (!status.coreReadGranted) {
                    setBusy(false)
                    requestHealthPermissions(
                        container.healthRepository.requestedReadPermissions(
                            includeBackground = false,
                            includeHistory = true,
                        ),
                        PendingHealthAction.ExportAllAfterGrant,
                    )
                    return@launch
                }
                if (status.historyReadAvailable && !status.historyReadGranted) {
                    setBusy(false)
                    requestHealthPermissions(
                        setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY),
                        PendingHealthAction.ExportAllAfterGrant,
                    )
                    return@launch
                }
                startGoogleAction(PendingGoogleAction.ExportAll, alreadyBusy = true)
            } catch (error: Exception) {
                setBusy(false)
                showMessage(friendlyMessage(error))
            }
        }
    }

    private fun startGoogleAction(
        action: PendingGoogleAction,
        alreadyBusy: Boolean = false,
    ) {
        lifecycleScope.launch {
            if (!alreadyBusy) setBusy(true)
            try {
                when (val authorization = container.syncCoordinator.authorizeGoogle()) {
                    is GoogleAuthorizationState.Authorized ->
                        executeGoogleAction(action, authorization.accessToken)
                    is GoogleAuthorizationState.NeedsResolution -> {
                        pendingGoogleAction = action
                        googleResolutionLauncher.launch(
                            IntentSenderRequest.Builder(authorization.pendingIntent).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                setBusy(false)
                showMessage(friendlyMessage(error))
            }
        }
    }

    private suspend fun executeGoogleAction(
        action: PendingGoogleAction,
        accessToken: String,
    ) {
        try {
            when (action) {
                PendingGoogleAction.ConnectSheet -> {
                    container.syncCoordinator.connectGoogleSheet(accessToken)
                    showMessage("Google Sheet is ready.", isError = false)
                }
                is PendingGoogleAction.Sync -> {
                    val success = container.syncCoordinator.sync(accessToken, action.mode)
                    previewText.text = success.rows.maxByOrNull { it.date }?.preview()
                        ?: "Sync completed; no daily rows were returned."
                    showMessage(
                        "Sync complete: ${success.rows.size} daily row(s) written and read back.",
                        isError = false,
                    )
                }
                PendingGoogleAction.ExportAll -> {
                    val success = container.syncCoordinator.exportAllHealthData(accessToken)
                    val scope =
                        if (success.completeHistory) {
                            "complete accessible history"
                        } else {
                            "accessible 30-day history"
                        }
                    showMessage(
                        "Export complete ($scope): ${success.rawRecordCount} raw record(s), " +
                            "${success.aggregateRowCount} aggregate row(s), and " +
                            "${success.dailyRowCount} Daily row(s) written and read back. " +
                            "Verified ${success.verifiedRawRowCount} row(s) in Raw.",
                        isError = false,
                    )
                }
            }
        } catch (error: SheetsApiException) {
            if (error.isUnauthorized) {
                runCatching { container.authorizationManager.clearInvalidToken(accessToken) }
            }
            showMessage(friendlyMessage(error))
        } catch (error: Exception) {
            showMessage(friendlyMessage(error))
        } finally {
            setBusy(false)
            refreshStatus()
        }
    }

    private fun requestHealthPermissions(
        permissions: Set<String>,
        after: PendingHealthAction,
    ) {
        pendingHealthAction = after
        setBusy(true)
        runCatching { healthPermissionLauncher.launch(permissions) }
            .onFailure { error ->
                pendingHealthAction = null
                setBusy(false)
                showMessage(friendlyMessage(error))
            }
    }

    private suspend fun refreshStatus() {
        val healthStatus = runCatching { container.healthRepository.getPermissionStatus() }.getOrNull()
        if (healthStatus == null) {
            healthStatusText.text = "Could not read Health Connect status."
            healthPermissionButton.visibility = View.VISIBLE
            backgroundPermissionButton.visibility = View.GONE
        } else {
            renderHealthStatus(healthStatus)
        }

        val spreadsheetId = container.preferences.spreadsheetId
        googleStatusText.text =
            if (spreadsheetId == null) {
                "Not connected. The app will create one spreadsheet after Google consent."
            } else {
                "Connected to Samsung Health Bridge / Daily."
            }
        connectGoogleButton.text =
            if (spreadsheetId == null) "Connect Google Sheet" else "Check Google access"
        openSheetButton.visibility = if (spreadsheetId == null) View.GONE else View.VISIBLE

        val lastSync = container.preferences.lastSyncAt
        val lastStatus = container.preferences.lastStatus.toDisplayStatus()
        lastSyncText.text =
            if (lastSync == null) {
                "Never · $lastStatus"
            } else {
                "${LAST_SYNC_FORMATTER.format(lastSync.atZone(ZoneId.systemDefault()))} · $lastStatus"
            }
        importHistoryButton.visibility =
            if (container.preferences.historyImportedAt == null) View.VISIBLE else View.GONE
        updateEnabledState()
    }

    private fun renderHealthStatus(status: HealthConnectPermissionStatus) {
        when (status.sdkStatus) {
            HealthConnectSdkStatus.Available -> {
                val core = if (status.coreReadGranted) "read permissions ready" else "read permissions needed"
                val background =
                    when {
                        !status.backgroundReadAvailable -> "background read unavailable"
                        status.backgroundReadGranted -> "background read ready"
                        else -> "background read permission needed"
                    }
                healthStatusText.text = "$core · $background"
                healthPermissionButton.visibility =
                    if (status.coreReadGranted) View.GONE else View.VISIBLE
                backgroundPermissionButton.visibility =
                    if (status.backgroundReadAvailable && !status.backgroundReadGranted) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }
            HealthConnectSdkStatus.ProviderUpdateRequired -> {
                healthStatusText.text = "Health Connect must be installed or updated."
                healthPermissionButton.text = "Install / update Health Connect"
                healthPermissionButton.visibility = View.VISIBLE
                backgroundPermissionButton.visibility = View.GONE
            }
            HealthConnectSdkStatus.Unavailable -> {
                healthStatusText.text = "Health Connect is unavailable on this device."
                healthPermissionButton.visibility = View.GONE
                backgroundPermissionButton.visibility = View.GONE
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateEnabledState()
        if (value) showMessage("Working…", isError = false)
    }

    private fun updateEnabledState() {
        if (!::syncButton.isInitialized) return
        listOf(
            healthPermissionButton,
            backgroundPermissionButton,
            connectGoogleButton,
            openSheetButton,
            syncButton,
            importHistoryButton,
            exportAllButton,
        ).forEach { it.isEnabled = !busy }
    }

    private fun showMessage(
        message: String,
        isError: Boolean = true,
    ) {
        messageText.text = message
        messageText.setTextColor(
            getColor(if (isError) android.R.color.holo_red_dark else android.R.color.holo_green_dark),
        )
        messageText.visibility = View.VISIBLE
    }

    private fun friendlyMessage(error: Throwable): String =
        when (error) {
            is SyncBlockedException -> error.message ?: "Health Connect action is required."
            is DuplicateSheetDateException ->
                "The Google Sheet contains duplicate date ${error.duplicateDate}. Remove one duplicate row, then retry."
            is DuplicateRawRecordKeyException ->
                "The Raw sheet contains duplicate key ${error.duplicateRecordKey}. Remove one duplicate row, then retry."
            is SheetsApiException ->
                if (error.isUnauthorized) {
                    "Google access expired. Tap Sync now and authorize again."
                } else {
                    error.message ?: "Google Sheets request failed."
                }
            is ApiException ->
                if (error.statusCode == 10) {
                    "Google OAuth is not configured for this app signature. Complete the one-time README setup."
                } else {
                    "Google authorization failed (code ${error.statusCode}). Try again."
                }
            is SecurityException -> "A required permission was revoked. Grant it and retry."
            else -> "Unexpected error. Try again."
        }

    private fun openHealthConnectStore() {
        val marketIntent =
            Intent(
                Intent.ACTION_VIEW,
                "market://details?id=${HealthConnectRepository.PROVIDER_PACKAGE_NAME}".toUri(),
            )
        try {
            startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://play.google.com/store/apps/details?id=${HealthConnectRepository.PROVIDER_PACKAGE_NAME}".toUri(),
                ),
            )
        }
    }

    private fun openSpreadsheet() {
        val spreadsheetId = container.preferences.spreadsheetId ?: return
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                "https://docs.google.com/spreadsheets/d/$spreadsheetId/edit".toUri(),
            ),
        )
    }

    private fun sectionTitle(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }

    private fun statusText() =
        TextView(this).apply {
            textSize = 16f
        }

    private fun actionButton(text: String) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
        }

    private fun rowParams(bottom: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = bottom }

    private fun String.toDisplayStatus(): String =
        when (this) {
            AppPreferences.STATUS_OK -> "successful"
            AppPreferences.STATUS_HEALTH_PERMISSION_REQUIRED -> "health permission action needed"
            AppPreferences.STATUS_GOOGLE_ACTION_REQUIRED -> "Google authorization action needed"
            AppPreferences.STATUS_ERROR -> "retry needed"
            else -> "not yet synced"
        }

    private sealed interface PendingHealthAction {
        data object RefreshOnly : PendingHealthAction

        data object Background : PendingHealthAction

        data class SyncAfterGrant(val mode: SyncMode) : PendingHealthAction

        data object ExportAllAfterGrant : PendingHealthAction
    }

    private sealed interface PendingGoogleAction {
        data object ConnectSheet : PendingGoogleAction

        data class Sync(val mode: SyncMode) : PendingGoogleAction

        data object ExportAll : PendingGoogleAction
    }

    private companion object {
        val LAST_SYNC_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
    }
}
