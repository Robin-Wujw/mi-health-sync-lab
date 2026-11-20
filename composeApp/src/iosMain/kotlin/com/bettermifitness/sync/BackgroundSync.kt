package com.bettermifitness.sync

import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.sync.SyncCoordinator
import com.bettermifitness.sync.sync.SyncOutcome
import com.bettermifitness.sync.sync.recordIosSyncTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform

/**
 * iOS bridge for BGTaskScheduler and App Intents.
 * Delegates policy to common [SyncCoordinator]; keeps single-flight + status strings stable for Swift.
 */
object BackgroundSync {
    const val STATUS_SUCCESS = SyncOutcome.STATUS_SUCCESS
    const val STATUS_COMPLETED_WITH_SKIPS = SyncOutcome.STATUS_COMPLETED_WITH_SKIPS
    const val STATUS_PARTIAL_SUCCESS = SyncOutcome.STATUS_PARTIAL_SUCCESS
    const val STATUS_SKIPPED = SyncOutcome.STATUS_SKIPPED
    const val STATUS_NOT_LOGGED_IN = SyncOutcome.STATUS_NOT_LOGGED_IN
    const val STATUS_HEALTH_UNAVAILABLE = SyncOutcome.STATUS_HEALTH_UNAVAILABLE
    const val STATUS_CANCELLED = SyncOutcome.STATUS_CANCELLED
    const val STATUS_FAILED = SyncOutcome.STATUS_FAILED
    const val STATUS_ALREADY_RUNNING = SyncOutcome.STATUS_ALREADY_RUNNING

    private const val AUTOMATIC_OWNER = "ios-bg-processing-v1"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mutex = Mutex()
    private var automaticJob: Job? = null
    private var inFlight: CompletableDeferred<String>? = null

    fun cancel() {
        scope.launch { automaticJob?.cancel() }
    }

    fun automaticScheduleDelay(completion: (Double) -> Unit) {
        scope.launch {
            val delay = try {
                doInitKoin()
                KoinPlatform.getKoin().get<SyncCoordinator>().automaticScheduleDelay(AUTOMATIC_OWNER)?.toDouble() ?: -1.0
            } catch (_: Exception) {
                900.0
            }
            completion(delay)
        }
    }

    fun runOpportunisticBackgroundSync(completion: (String) -> Unit) {
        scope.launch {
            if (automaticJob?.isActive == true) {
                completion(STATUS_ALREADY_RUNNING)
                return@launch
            }
            automaticJob = scope.launch {
                val status = try {
                    doSyncWork(0, requireAutoSync = true, requestHealthPermissions = false,
                        recordAsBackground = true, userInitiated = false)
                } catch (_: CancellationException) {
                    STATUS_CANCELLED
                } catch (_: Exception) {
                    STATUS_FAILED
                }
                completion(status)
            }
        }
    }

    fun runUserInitiatedSync(completion: (String) -> Unit) {
        enqueue(
            rangeDaysOverride = 0,
            requireAutoSync = false,
            requestHealthPermissions = false,
            recordAsBackground = false,
            userInitiated = true,
            completion = completion,
        )
    }

    /**
     * Session keepalive for BGTaskScheduler ticks: rolls the passport session forward
     * when stale and syncs nothing. Called from Swift handlers before the data sync so
     * the login survives weeks without a sync, even with auto-sync off.
     */
    fun runSessionRefresh(completion: (String) -> Unit) {
        scope.launch {
            val status = try {
                doInitKoin()
                val koin = KoinPlatform.getKoin()
                val session = koin.get<MiSessionManager>()
                val coordinator = koin.get<SyncCoordinator>()
                var signedIn = false
                val acquired = coordinator.runSessionMaintenance {
                    signedIn = session.ensureActive()
                    if (signedIn) session.refreshSessionIfStale()
                }
                when {
                    !acquired -> STATUS_ALREADY_RUNNING
                    !signedIn || session.requiresReauthentication -> STATUS_NOT_LOGGED_IN
                    else -> STATUS_SUCCESS
                }
            } catch (_: CancellationException) {
                STATUS_CANCELLED
            } catch (_: Exception) {
                STATUS_FAILED
            }
            completion(status)
        }
    }

    private fun enqueue(
        rangeDaysOverride: Int,
        requireAutoSync: Boolean,
        requestHealthPermissions: Boolean,
        recordAsBackground: Boolean,
        userInitiated: Boolean,
        completion: (String) -> Unit,
    ) {
        scope.launch {
            val status = try {
                joinOrStart(
                    rangeDaysOverride = rangeDaysOverride,
                    requireAutoSync = requireAutoSync,
                    requestHealthPermissions = requestHealthPermissions,
                    recordAsBackground = recordAsBackground,
                    userInitiated = userInitiated,
                )
            } catch (_: CancellationException) {
                STATUS_CANCELLED
            } catch (_: Exception) {
                STATUS_FAILED
            }
            completion(status)
        }
    }

    private suspend fun joinOrStart(
        rangeDaysOverride: Int,
        requireAutoSync: Boolean,
        requestHealthPermissions: Boolean,
        recordAsBackground: Boolean,
        userInitiated: Boolean,
    ): String {
        val deferred: CompletableDeferred<String> = mutex.withLock {
            val existing = inFlight
            if (existing != null) {
                return@withLock existing
            }
            val created = CompletableDeferred<String>()
            inFlight = created
            scope.launch {
                val result = try {
                    doSyncWork(
                        rangeDaysOverride = rangeDaysOverride,
                        requireAutoSync = requireAutoSync,
                        requestHealthPermissions = requestHealthPermissions,
                        recordAsBackground = recordAsBackground,
                        userInitiated = userInitiated,
                    )
                } catch (_: CancellationException) {
                    STATUS_CANCELLED
                } catch (_: Exception) {
                    STATUS_FAILED
                }
                created.complete(result)
                mutex.withLock {
                    if (inFlight === created) {
                        inFlight = null
                    }
                }
            }
            created
        }
        return deferred.await()
    }

    private suspend fun doSyncWork(
        rangeDaysOverride: Int,
        requireAutoSync: Boolean,
        requestHealthPermissions: Boolean,
        recordAsBackground: Boolean,
        userInitiated: Boolean,
    ): String = withContext(Dispatchers.Default) {
        doInitKoin()
        val coordinator = KoinPlatform.getKoin().get<SyncCoordinator>()
        coordinator.run(
            rangeDaysOverride = rangeDaysOverride,
            requireAutoSync = requireAutoSync,
            requestHealthPermissions = requestHealthPermissions,
            recordAsBackground = recordAsBackground,
            resetProgress = false,
            userInitiated = userInitiated,
            request = if (userInitiated && !requireAutoSync && rangeDaysOverride == 0) coordinator.captureUserSyncRequest() else null,
            persistedAutomaticOwner = AUTOMATIC_OWNER.takeIf { requireAutoSync },
            onTaskFinished = ::recordIosSyncTask,
        ).toStatusCode()
    }
}
