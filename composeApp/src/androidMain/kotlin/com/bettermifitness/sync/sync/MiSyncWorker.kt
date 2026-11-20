package com.bettermifitness.sync.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.SessionRefreshResult
import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.bettermifitness.sync.di.initKoin
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import org.koin.mp.KoinPlatform

/**
 * Opportunistic background sync (WorkManager).
 * Uses the incremental plan, requires Auto-sync ON, and never presents permission UI.
 *
 * Retry policy:
 * - Success / partial success / skipped / not logged in / health unavailable → [Result.success]
 * - A durable retry owned by this worker → [Result.retry] (WorkManager exponential backoff)
 * - Non-retryable failures (auth without refresh, permanent) → [Result.failure]
 */
class MiSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Includes refresh-only jobs, which do not pass through SyncCoordinator.
        if (LiveTestSessionGate.isActive) return completed("test_gate")
        if (inputData.getBoolean(KEY_REFRESH_ONLY, false)) {
            return doSessionRefresh()
        }
        return try {
            ensureKoin()
            val koin = KoinPlatform.getKoin()
            val coordinator = koin.get<SyncCoordinator>()
            val attempt = AutomaticWorkAttempt(id.toString(), runAttemptCount)
            val journal = koin.get<UserSyncJournal>()
            journal.reconcileAutomatic(androidSyncProcessId)
            val queued = journal.recoverable.first()
            if (queued != null && queued.accountGeneration == koin.get<CredentialsStore>().accountGeneration()) {
                coordinator.recordAutomaticSkip(AutomaticSkipReason.MANUAL_BUSY, queued.accountGeneration)
                return if (coordinator.hasPendingAutomaticRetry(attempt.owner)) Result.retry()
                else completed(SyncOutcome.STATUS_ALREADY_RUNNING, "queued_user_sync")
            }
            val outcome = coordinator.run(
                rangeDaysOverride = 0,
                requireAutoSync = true,
                requestHealthPermissions = false,
                recordAsBackground = true,
                resetProgress = false,
                userInitiated = false,
                automaticAttempt = attempt,
                onTaskAccepted = { task -> journal.beginAutomatic(task, androidSyncProcessId) },
                onTaskFinished = { summary ->
                    try {
                        journal.recordAutomatic(summary)
                    } catch (_: Exception) {
                        Log.w(TAG, "background sync history unavailable")
                    }
                },
            )
            Log.i(TAG, "background sync finished: ${outcome.toStatusCode()}")
            if (coordinator.hasPendingAutomaticRetry(attempt.owner)) return Result.retry()
            when (outcome) {
                SyncOutcome.Success,
                is SyncOutcome.CompletedWithSkips,
                is SyncOutcome.NoData,
                is SyncOutcome.PartialSuccess,
                SyncOutcome.Skipped,
                SyncOutcome.Cancelled,
                SyncOutcome.HealthUnavailable,
                // Another path owns the run; do not queue retries that stack more work.
                SyncOutcome.AlreadyRunning,
                -> completed(outcome.toStatusCode(), if (outcome is SyncOutcome.AlreadyRunning) {
                    if (coordinator.isRunning.value) "sync" else "maintenance"
                } else "none")
                // A dead session is a real failure, not a healthy no-op: report it so
                // WorkManager bookkeeping (and any observers) see it instead of success.
                // The next periodic tick still runs, so a later sign-in recovers on its own.
                SyncOutcome.NotLoggedIn,
                is SyncOutcome.Failed -> Result.failure()
            }
        } catch (_: UserSyncStopped) {
            val coordinator = KoinPlatform.getKoin().get<SyncCoordinator>()
            if (coordinator.hasPendingAutomaticRetry(id.toString())) Result.retry()
            else completed(SyncOutcome.STATUS_CANCELLED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "background sync error (${e::class.simpleName})")
            Result.failure()
        }
    }

    private fun completed(outcome: String, busyOwner: String = "none"): Result =
        Result.success(workDataOf(KEY_OUTCOME to outcome, KEY_BUSY_OWNER to busyOwner))

    /**
     * Session keepalive tick: rolls the passport session forward when stale and does
     * nothing else. Scheduled independently of the auto-sync toggle so the passToken
     * rotation chain can continue without a data sync. Transient failures and a busy
     * sync gate retry with backoff; rejected credentials await a fresh sign-in.
     */
    private suspend fun doSessionRefresh(): Result {
        return try {
            ensureKoin()
            val session = KoinPlatform.getKoin().get<MiSessionManager>()
            val coordinator = KoinPlatform.getKoin().get<SyncCoordinator>()
            var signedIn = false
            var refresh: SessionRefreshResult? = null
            val acquired = coordinator.runSessionMaintenance {
                signedIn = session.ensureActive()
                if (signedIn) refresh = session.refreshSessionIfDue()
            }
            when {
                !acquired -> Result.retry()
                !signedIn -> completed("session_not_signed_in")
                session.requiresReauthentication -> completed("session_reauthentication_required")
                refresh is SessionRefreshResult.TransientFailure -> Result.retry()
                else -> completed("session_refresh")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "background session refresh error", e)
            Result.retry()
        }
    }

    private fun ensureKoin() {
        if (GlobalContext.getOrNull() == null) {
            // Application should have started Koin; defensive for edge process starts.
            com.bettermifitness.sync.di.provideAndroidContext(applicationContext)
            initKoin()
        }
    }

    companion object {
        private const val TAG = "MiSyncWorker"
        const val UNIQUE_WORK_NAME = "mi_fitness_auto_sync"
        const val UNIQUE_IMMEDIATE_NAME = "mi_fitness_auto_sync_immediate"
        const val UNIQUE_REFRESH_NAME = "mi_fitness_session_refresh"
        const val KEY_REFRESH_ONLY = "refresh_only"
        const val KEY_OUTCOME = "sync_outcome"
        const val KEY_BUSY_OWNER = "sync_busy_owner"
    }
}
