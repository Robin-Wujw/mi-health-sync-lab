package com.bettermifitness.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.health.HealthWriter
import kotlinx.coroutines.CancellationException
import com.bettermifitness.sync.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

object AndroidForegroundAutoSync {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch { maintainSession() }
                    scope.launch { refreshRouteCache() }
                }
            },
        )
    }

    private suspend fun refreshRouteCache() {
        try {
            KoinPlatform.getKoin().get<HealthWriter>().refreshWorkoutRouteCache()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AndroidForegroundAutoSync", "route cache check failed (${e::class.simpleName})")
        }
    }

    private suspend fun maintainSession() {
        try {
            val coordinator = KoinPlatform.getKoin().get<SyncCoordinator>()
            val session = KoinPlatform.getKoin().get<MiSessionManager>()
            coordinator.runSessionMaintenance {
                if (session.ensureActive()) session.refreshSessionIfStale()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AndroidForegroundAutoSync", "session maintenance failed", e)
        }
    }
}
