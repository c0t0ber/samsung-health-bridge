package com.roktober.samsunghealthbridge.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.roktober.samsunghealthbridge.sync.DailySyncWorker
import java.util.concurrent.TimeUnit

class DebugSyncProbeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_ENQUEUE) return

        val delaySeconds =
            intent
                .getLongExtra(EXTRA_DELAY_SECONDS, DEFAULT_DELAY_SECONDS)
                .coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS)
        val request =
            OneTimeWorkRequestBuilder<DailySyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .addTag(PROBE_TAG)
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val ACTION_ENQUEUE =
            "com.roktober.samsunghealthbridge.debug.ENQUEUE_BACKGROUND_SYNC_PROBE"
        const val EXTRA_DELAY_SECONDS = "delay_seconds"
        const val UNIQUE_WORK_NAME = "debug-background-sync-probe"
        const val PROBE_TAG = "debug-background-sync-probe"
        private const val DEFAULT_DELAY_SECONDS = 90L
        private const val MIN_DELAY_SECONDS = 30L
        private const val MAX_DELAY_SECONDS = 600L
    }
}
