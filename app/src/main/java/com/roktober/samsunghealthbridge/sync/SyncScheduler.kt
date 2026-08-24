package com.roktober.samsunghealthbridge.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val UNIQUE_WORK_NAME = "daily-health-sheet-sync"

    fun register(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        val request =
            PeriodicWorkRequestBuilder<DailySyncWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 2,
                flexTimeIntervalUnit = TimeUnit.HOURS,
            ).setConstraints(constraints)
                .addTag(UNIQUE_WORK_NAME)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
