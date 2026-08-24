package com.roktober.samsunghealthbridge

import android.content.Context
import com.roktober.samsunghealthbridge.google.GoogleAuthorizationManager
import com.roktober.samsunghealthbridge.health.HealthConnectRepository
import com.roktober.samsunghealthbridge.sheets.SheetsApi
import com.roktober.samsunghealthbridge.storage.AppPreferences
import com.roktober.samsunghealthbridge.sync.SyncCoordinator

class AppContainer(context: Context) {
    val preferences = AppPreferences(context)
    val healthRepository = HealthConnectRepository(context)
    val authorizationManager = GoogleAuthorizationManager(context)
    val sheetsApi = SheetsApi()
    val syncCoordinator =
        SyncCoordinator(
            healthRepository = healthRepository,
            authorizationManager = authorizationManager,
            sheetsApi = sheetsApi,
            preferences = preferences,
        )
}
