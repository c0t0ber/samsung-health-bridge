package com.roktober.samsunghealthbridge.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val UNIQUE_WORK_NAME = "daily-health-sheet-sync-v2"
    private const val LEGACY_UNIQUE_WORK_NAME = "daily-health-sheet-sync"

    fun register(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val request =
            PeriodicWorkRequestBuilder<DailySyncWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 2,
                flexTimeIntervalUnit = TimeUnit.HOURS,
            ).addTag(UNIQUE_WORK_NAME)
                .build()

        // Samsung can block a background UID's network until its JobScheduler job actually starts.
        // A CONNECTED constraint then deadlocks: the job waits for UID connectivity, while UID
        // connectivity waits for the job to run. The worker already retries transient auth/Sheets
        // failures, so let it start and perform the network attempt inside the execution window.
        workManager.cancelUniqueWork(LEGACY_UNIQUE_WORK_NAME)
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
