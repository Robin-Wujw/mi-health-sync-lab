package com.bettermifitness.sync.sync

import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import androidx.work.workDataOf
import com.bettermifitness.sync.AutoSyncPlatform
import com.bettermifitness.sync.LocalizedTest
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real WorkManager database tests; disconnected constraints prevent any health or auth work. */
class AutoSyncScheduleTest : LocalizedTest() {
    private lateinit var manager: WorkManagerImpl

    @Before fun prepareScheduler() {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.net.ConnectivityManager::class.java))
            .setActiveNetworkInfo(null)
        LiveTestSessionGate.activateForInstrumentation()
        WorkManagerImpl.initialize(context, Configuration.Builder().build())
        manager = WorkManagerImpl.getInstance(context)
        AutoSyncPlatform.init(context)
    }

    @After fun closeScheduler() {
        if (::manager.isInitialized) {
            manager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
            manager.closeDatabase()
        }
        WorkManagerImpl.setDelegate(null)
        LiveTestSessionGate.resetForUnitTests()
    }

    @Test fun existingTwelveHourKeepaliveMigratesWithoutReplacingItsIdentity() {
        val legacy = PeriodicWorkRequestBuilder<MiSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(MiSyncWorker.KEY_REFRESH_ONLY to true))
            .build()
        manager.enqueueUniquePeriodicWork(MiSyncWorker.UNIQUE_REFRESH_NAME,
            ExistingPeriodicWorkPolicy.KEEP, legacy).result.get(20, TimeUnit.SECONDS)
        AutoSyncPlatform.scheduleSessionRefresh()
        drainScheduler()
        val after = work(MiSyncWorker.UNIQUE_REFRESH_NAME).single()
        assertEquals(TimeUnit.HOURS.toMillis(3), after.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, after.constraints.requiredNetworkType)
        assertEquals(legacy.id, after.id)
        assertFalse(after.state.isFinished)
    }

    @Test fun restoringScheduleNeverCreatesAnImmediateSyncButExplicitEnableDoes() {
        AutoSyncPlatform.scheduleBackgroundRefresh()
        drainScheduler()
        assertTrue(work(MiSyncWorker.UNIQUE_IMMEDIATE_NAME).isEmpty())
        val periodicId = work(MiSyncWorker.UNIQUE_WORK_NAME).single().id
        AutoSyncPlatform.scheduleBackgroundRefresh(runImmediately = true)
        drainScheduler()
        val immediateId = work(MiSyncWorker.UNIQUE_IMMEDIATE_NAME).single().id
        AutoSyncPlatform.scheduleBackgroundRefresh()
        drainScheduler()
        assertEquals(periodicId, work(MiSyncWorker.UNIQUE_WORK_NAME).single().id)
        assertEquals(immediateId, work(MiSyncWorker.UNIQUE_IMMEDIATE_NAME).single().id)
    }

    @Test fun disablingAutoSyncCancelsBothDataJobsButPreservesTheIndependentKeepalive() {
        AutoSyncPlatform.scheduleBackgroundRefresh(runImmediately = true)
        drainScheduler()
        AutoSyncPlatform.cancelBackgroundRefresh()
        drainScheduler()
        assertTrue(work(MiSyncWorker.UNIQUE_WORK_NAME).all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(work(MiSyncWorker.UNIQUE_IMMEDIATE_NAME).all { it.state == WorkInfo.State.CANCELLED })
        assertFalse(work(MiSyncWorker.UNIQUE_REFRESH_NAME).single().state.isFinished)
    }

    private fun work(name: String) = manager.getWorkInfosForUniqueWork(name).get(20, TimeUnit.SECONDS)

    private fun drainScheduler() {
        val drained = java.util.concurrent.CompletableFuture<Unit>()
        manager.workTaskExecutor.executeOnTaskThread { drained.complete(Unit) }
        drained.get(20, TimeUnit.SECONDS)
    }
}
