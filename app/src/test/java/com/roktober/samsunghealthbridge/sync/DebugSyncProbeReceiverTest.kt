package com.roktober.samsunghealthbridge.sync

import android.app.Application
import android.content.Context
import android.content.Intent

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.roktober.samsunghealthbridge.debug.DebugSyncProbeReceiver
import org.junit.After
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
class DebugSyncProbeReceiverTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val configuration =
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `probe broadcast enqueues a distinct delayed background worker`() {
        val intent =
            Intent(DebugSyncProbeReceiver.ACTION_ENQUEUE)
                .putExtra(DebugSyncProbeReceiver.EXTRA_DELAY_SECONDS, 90L)

        DebugSyncProbeReceiver().onReceive(context, intent)

        val work =
            workManager
                .getWorkInfosForUniqueWork(DebugSyncProbeReceiver.UNIQUE_WORK_NAME)
                .get()
                .single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertTrue(work.tags.contains(DebugSyncProbeReceiver.PROBE_TAG))
        assertTrue(work.tags.contains(DailySyncWorker::class.java.name))
        assertTrue(work.id.toString().isNotBlank())

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
}
