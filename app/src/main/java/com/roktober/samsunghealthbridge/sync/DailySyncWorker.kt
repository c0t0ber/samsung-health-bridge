package com.roktober.samsunghealthbridge.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.roktober.samsunghealthbridge.BridgeApplication

class DailySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val application = applicationContext as BridgeApplication
        return when (application.container.syncCoordinator.runBackgroundSync()) {
            is BackgroundSyncResult.Completed -> Result.success()
            BackgroundSyncResult.NeedsUserAction -> Result.success()
            BackgroundSyncResult.RetryLater -> {
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            }
            BackgroundSyncResult.PermanentFailure -> Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
