package com.bettermifitness.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.MiSessionManager
import kotlinx.coroutines.CancellationException
import com.bettermifitness.sync.sync.SyncCoordinator
import com.bettermifitness.sync.sync.SyncOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Mirrors iOS scenePhase.active: when Auto-sync is on, run a full-range sync
 * as the app comes to foreground (user-configured range).
 */
object AndroidForegroundAutoSync {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch { runIfEnabled() }
                }
            },
        )
    }

    private suspend fun runIfEnabled() {
        try {
            val coordinator = KoinPlatform.getKoin().get<SyncCoordinator>()
            val session = KoinPlatform.getKoin().get<MiSessionManager>()
            coordinator.runSessionMaintenance {
                if (session.ensureActive()) session.refreshSessionIfStale()
            }
            val prefs = KoinPlatform.getKoin().get<SyncPreferences>()
            if (!prefs.autoSync.first() || coordinator.isRunning.value) return
            val outcome = com.bettermifitness.sync.sync.UserSyncService.startAndAwait(
                com.bettermifitness.sync.di.androidAppContext(), automatic = true)
            // Permission UI is not available from ProcessLifecycle; user must grant on Sync screen once.
            if (outcome is SyncOutcome.Failed) {
                android.util.Log.w("AndroidForegroundAutoSync", "sync failed: ${outcome.message}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AndroidForegroundAutoSync", "sync error", e)
        }
    }
}
