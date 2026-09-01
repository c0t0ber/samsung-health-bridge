package com.roktober.samsunghealthbridge.sync

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class SyncSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        val synchronousExecutor = SynchronousExecutor()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(synchronousExecutor)
                .setTaskExecutor(synchronousExecutor)
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `cold start registration preserves pending worker generation`() {
        SyncScheduler.register(context)
        val beforeColdStart =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME).get().single()

        SyncScheduler.register(context)
        val afterColdStart =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME).get().single()

        assertEquals(beforeColdStart.id, afterColdStart.id)
        assertEquals(
            "Application cold start must not invalidate the JobScheduler generation that launched it",
            beforeColdStart.generation,
            afterColdStart.generation,
        )
    }

    @Test
    fun `periodic worker starts without a network constraint`() {
        SyncScheduler.register(context)
        val work =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME).get().single()
        val workManagerImpl = WorkManagerImpl.getInstance(context)
        val workDatabase =
            workManagerImpl.javaClass.getMethod("getWorkDatabase").invoke(workManagerImpl)
        val workSpecDao =
            workDatabase.javaClass.getMethod("workSpecDao").invoke(workDatabase)
        val workSpec =
            workSpecDao.javaClass
                .getMethod("getWorkSpec", String::class.java)
                .invoke(workSpecDao, work.id.toString())
        val constraints =
            requireNotNull(workSpec).javaClass.getField("constraints").get(workSpec)
        val requiredNetworkType =
            constraints.javaClass.getMethod("getRequiredNetworkType").invoke(constraints)

        assertEquals(NetworkType.NOT_REQUIRED, requiredNetworkType)
    }

    @Test
    fun `registration replaces the legacy connectivity constrained worker`() {
        val legacyRequest =
            PeriodicWorkRequestBuilder<DailySyncWorker>(24, TimeUnit.HOURS).build()
        workManager.enqueueUniquePeriodicWork(
            "daily-health-sheet-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            legacyRequest,
        ).result.get()

        SyncScheduler.register(context)

        val replacement =
            workManager.getWorkInfosForUniqueWork("daily-health-sheet-sync-v2").get()
        val legacy =
            workManager.getWorkInfosForUniqueWork("daily-health-sheet-sync").get()
        assertEquals(1, replacement.size)
        assertTrue(legacy.all { it.state == WorkInfo.State.CANCELLED })
    }
}
