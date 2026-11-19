package com.bettermifitness.sync.sync

import com.bettermifitness.sync.data.preferences.CredentialsPort
import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.preferences.SyncPreferencesPort
import com.bettermifitness.sync.data.preferences.SyncSessionPort
import com.bettermifitness.sync.data.repository.HealthSyncRunner
import com.bettermifitness.sync.data.repository.SyncRunResult
import com.bettermifitness.sync.health.HealthAvailability
import com.bettermifitness.sync.health.HealthPermissionRequester
import com.bettermifitness.sync.health.HealthStore
import com.bettermifitness.sync.ui.SyncSelection
import com.bettermifitness.sync.zepp.ZeppSyncService
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * Shared sync policy for UI, background tasks, and Shortcuts.
 * Depends on ISP ports so unit tests inject fakes without DataStore / Health SDKs.
 *
 * **Single-flight:** only one [run] executes at a time. Concurrent callers get
 * [SyncOutcome.AlreadyRunning] immediately (no second Mi API / Health write storm).
 */
class SyncCoordinator(
    private val session: SyncSessionPort,
    private val credentials: CredentialsPort,
    private val syncPreferences: SyncPreferencesPort,
    private val healthAvailability: HealthAvailability,
    private val healthPermissions: HealthPermissionRequester,
    private val repository: HealthSyncRunner,
    /** Optional so pure coordinator tests and non-Zepp hosts keep their old constructor. */
    private val zeppSync: ZeppSyncService? = null,
    private val now: () -> Instant = { Clock.System.now() },
) {
    constructor(
        session: SyncSessionPort,
        credentials: CredentialsStore,
        syncPreferences: SyncPreferences,
        healthStore: HealthStore,
        repository: HealthSyncRunner,
    ) : this(
        session = session,
        credentials = credentials,
        syncPreferences = syncPreferences,
        healthAvailability = healthStore,
        healthPermissions = healthStore,
        repository = repository,
    )

    private val runMutex = Mutex()
    /** True while a [run] holds the single-flight lock (including permission prompts). */
    val isRunning: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Last finished outcome (not [SyncOutcome.AlreadyRunning]). */
    val lastOutcome: StateFlow<SyncOutcome?>
        field = MutableStateFlow(null)

    /** A bounded user action shares the normal sync lock without changing saved sync settings/results. */
    suspend fun <T> runExclusiveUserAction(action: suspend () -> T): ExclusiveSyncActionResult<T> {
        if (LiveTestSessionGate.isActive) return ExclusiveSyncActionResult.Unavailable
        if (!runMutex.tryLock()) return ExclusiveSyncActionResult.Busy
        isRunning.value = true
        return try {
            ExclusiveSyncActionResult.Completed(trackSyncActivity(action))
        } finally {
            isRunning.value = false
            runMutex.unlock()
        }
    }

    /** Profile/keepalive refresh must not replace the API client during a health write or readback. */
    suspend fun runSessionMaintenance(action: suspend () -> Unit): Boolean {
        if (LiveTestSessionGate.isActive || !runMutex.tryLock()) return false
        return try {
            action()
            true
        } finally {
            runMutex.unlock()
        }
    }

    /**
     * @param rangeDaysOverride when > 0, overrides user range (e.g. BG 1-day refresh)
     * @param requireAutoSync when true, no-ops unless auto-sync is enabled
     * @param requestHealthPermissions when true, may show permission UI (foreground)
     * @param recordAsBackground when true, updates last background sync timestamp
     * @param resetProgress when true, clears per-metric progress (UI path)
     * @param userInitiated when true, missing login → [SyncOutcome.NotLoggedIn] instead of Skipped
     */
    suspend fun run(
        rangeDaysOverride: Int = 0,
        requireAutoSync: Boolean = false,
        requestHealthPermissions: Boolean = true,
        recordAsBackground: Boolean = false,
        resetProgress: Boolean = false,
        userInitiated: Boolean = true,
    ): SyncOutcome {
        // Check before credentials, session refresh, permissions, or preferences.
        // Instrumentation invokes a separate bounded repository entry point.
        if (LiveTestSessionGate.isActive) return SyncOutcome.Skipped
        if (!runMutex.tryLock()) {
            return SyncOutcome.AlreadyRunning
        }
        isRunning.value = true
        try {
            val outcome = runInternal(
                rangeDaysOverride = rangeDaysOverride,
                requireAutoSync = requireAutoSync,
                requestHealthPermissions = requestHealthPermissions,
                recordAsBackground = recordAsBackground,
                resetProgress = resetProgress,
                userInitiated = userInitiated,
            )
            // Do not clobber last sync prefs when we never started work.
            if (outcome !is SyncOutcome.AlreadyRunning) {
                recordOutcome(outcome, recordAsBackground, userInitiated)
                lastOutcome.value = outcome
            }
            return outcome
        } finally {
            isRunning.value = false
            runMutex.unlock()
        }
    }

    private suspend fun runInternal(
        rangeDaysOverride: Int,
        requireAutoSync: Boolean,
        requestHealthPermissions: Boolean,
        recordAsBackground: Boolean,
        resetProgress: Boolean,
        userInitiated: Boolean,
    ): SyncOutcome {
        val token = credentials.token.first()
        if (token.isNullOrEmpty()) {
            return notLoggedInOrSkipped(userInitiated, requireAutoSync)
        }

        if (!session.ensureActive() || session.requiresReauthentication) {
            return notLoggedInOrSkipped(userInitiated, requireAutoSync)
        }

        // Roll the passport session forward if it has gone stale, before the auto-sync
        // gate: even a run that ends up Skipped (background tick racing a disabled toggle)
        // keeps the passToken rotation chain alive for the next real sync. Successful
        // renewal is limited to the six-hour cadence. A hard rejection pauses fan-out;
        // transient renewal failures still allow the lazy 401 recovery path.
        session.refreshSessionIfStale()
        if (session.requiresReauthentication) return notLoggedInOrSkipped(userInitiated, requireAutoSync)

        if (requireAutoSync && !syncPreferences.autoSync.first()) {
            return SyncOutcome.Skipped
        }

        // A return from the background must not restart a recent manual run.
        // The durable start/end marker also covers cancellation and process recreation.
        // Scheduled workers retain their own retry/backoff policy.
        if (requireAutoSync && !recordAsBackground && !userInitiated && hasRecentSyncActivity()) {
            return SyncOutcome.Skipped
        }

        val enabled = effectiveEnabledMetrics()
        if (enabled.none { it in SyncPreferences.ALL_METRIC_KEYS || it in SyncPreferences.INBOUND_METRIC_KEYS ||
                it in SyncPreferences.ZEPP_CLOUD_METRIC_KEYS }) {
            return SyncOutcome.Skipped
        }

        if (enabled.any { it in SyncPreferences.ALL_METRIC_KEYS || it in SyncPreferences.INBOUND_METRIC_KEYS } &&
            !healthAvailability.isAvailable()) {
            return SyncOutcome.HealthUnavailable
        }

        val days = if (rangeDaysOverride > 0) rangeDaysOverride else syncPreferences.syncRangeDays.first()
        return trackSyncActivity {
            if (requestHealthPermissions) {
                requestSelectedPermissions(enabled, days)?.let { return@trackSyncActivity it }
            }

            try {
                if (resetProgress) repository.resetProgress()
                val now = now()
                val result = repository.syncAll(
                    from = now.minus(days.days).toString(),
                    to = now.toString(),
                    enabled = enabled,
                )
                mapRunResult(result, now.toString(), recordAsBackground, userInitiated, requireAutoSync)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncOutcome.Failed(
                    message = e.message,
                    retryable = true,
                )
            }
        }
    }

    /** Foreground permission UI only; the Android service rechecks all run gates itself. */
    suspend fun prepareUserSync(): SyncOutcome? {
        if (LiveTestSessionGate.isActive) return SyncOutcome.Skipped
        if (!runMutex.tryLock()) return SyncOutcome.AlreadyRunning
        isRunning.value = true
        try {
            if (credentials.token.first().isNullOrEmpty() || !session.ensureActive() || session.requiresReauthentication) {
                return publishPreparationOutcome(SyncOutcome.NotLoggedIn)
            }
            val enabled = effectiveEnabledMetrics()
            if (enabled.none { it in SyncPreferences.ALL_METRIC_KEYS || it in SyncPreferences.INBOUND_METRIC_KEYS ||
                    it in SyncPreferences.ZEPP_CLOUD_METRIC_KEYS }) return publishPreparationOutcome(SyncOutcome.Skipped)
            if (enabled.any { it in SyncPreferences.ALL_METRIC_KEYS || it in SyncPreferences.INBOUND_METRIC_KEYS } &&
                !healthAvailability.isAvailable()) return publishPreparationOutcome(SyncOutcome.HealthUnavailable)
            return requestSelectedPermissions(enabled, syncPreferences.syncRangeDays.first())?.let {
                publishPreparationOutcome(it)
            }
        } finally {
            isRunning.value = false
            runMutex.unlock()
        }
    }

    /**
     * Android performs permission preparation before handing the run to its service.
     * Persist that terminal result here so a preparation failure has the same
     * user-visible status as a failure returned by [run].
     */
    private suspend fun publishPreparationOutcome(outcome: SyncOutcome): SyncOutcome {
        recordOutcome(outcome, recordAsBackground = false, userInitiated = true)
        lastOutcome.value = outcome
        return outcome
    }

    private suspend fun requestSelectedPermissions(enabled: Set<String>, days: Int): SyncOutcome? {
        try {
            // Inbound-only runs must not request unrelated write access.
            if (enabled.any { it in SyncPreferences.ALL_METRIC_KEYS }) {
                healthPermissions.requestPermissions()
            }
            // Share the same dependency selection as Settings and the Sync screen:
            // sleep imports also read same-source heart-rate and oxygen details.
            val inbound = SyncSelection.inboundMetrics(enabled)
            if (inbound.isNotEmpty()) {
                healthPermissions.requestReadPermissions(
                    inbound, includeBackground = true, includeHistory = days > 27)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncOutcome.Failed(
                message = e.message ?: "Health permissions denied",
                retryable = false,
            )
        }
        return null
    }

    private suspend fun hasRecentSyncActivity(): Boolean {
        val timestamp = syncPreferences.lastSyncActivityTime.first() ?: return false
        val previous = try { Instant.parse(timestamp) } catch (_: IllegalArgumentException) { return false }
        val elapsed = now() - previous
        return elapsed >= ZERO && elapsed < 15.minutes
    }

    /**
     * A connected Zepp account is an automatic source, not a separate user task.
     * The legacy key is retained internally so progress and journals stay stable,
     * but its old Settings toggle cannot disable a connected source anymore.
     */
    private suspend fun effectiveEnabledMetrics(): Set<String> {
        val configured = syncPreferences.enabledMetrics.first()
        return when (zeppSync?.isConnectedNow()) {
            true -> configured + SyncPreferences.ZEPP_CLOUD_IMPORT_KEY
            false -> configured - SyncPreferences.ZEPP_CLOUD_IMPORT_KEY
            null -> configured
        }
    }

    private suspend fun <T> trackSyncActivity(action: suspend () -> T): T {
        syncPreferences.updateLastSyncActivity(now().toString())
        return try {
            action()
        } finally {
            withContext(NonCancellable) {
                syncPreferences.updateLastSyncActivity(now().toString())
            }
        }
    }

    private suspend fun recordOutcome(outcome: SyncOutcome, recordAsBackground: Boolean, userInitiated: Boolean) {
        // Explicit no-op results replace an old partial result; automatic no-ops
        // (including foreground cooldown) leave the last user-visible result alone.
        if (outcome is SyncOutcome.Skipped && !userInitiated) return
        val status = outcome.toStatusCode()
        val message = outcome.userMessage()
        if (recordAsBackground) {
            syncPreferences.updateLastBackgroundSyncOutcome(status, message)
            syncPreferences.updateLastBackgroundSync(now().toString())
        } else {
            syncPreferences.updateLastSyncOutcome(status, message)
        }
    }

    private suspend fun mapRunResult(
        result: SyncRunResult,
        now: String,
        recordAsBackground: Boolean,
        userInitiated: Boolean,
        requireAutoSync: Boolean,
    ): SyncOutcome {
        if (result.isEmpty) return SyncOutcome.Skipped
        if (result.hasVerifiedProgress) {
            syncPreferences.updateLastSync(now)
            if (recordAsBackground) {
                syncPreferences.updateLastBackgroundSync(now)
            }
        }

        return when {
            result.isCompletedWithSkips -> SyncOutcome.CompletedWithSkips(result.summary())
            result.isFullSuccess -> SyncOutcome.Success
            result.isNoData -> SyncOutcome.NoData(result.summary())
            result.isPartialSuccess -> SyncOutcome.PartialSuccess(
                succeeded = result.succeeded,
                failed = result.failed,
                summary = result.summary(),
            )
            result.isTotalFailure && result.hadAuthFailure ->
                if (userInitiated && !requireAutoSync) {
                    SyncOutcome.NotLoggedIn
                } else {
                    SyncOutcome.Failed(
                        message = result.summary(),
                        retryable = false,
                    )
                }
            result.isTotalFailure -> SyncOutcome.Failed(
                message = result.summary(),
                retryable = result.hadRetryableFailure,
            )
            else -> SyncOutcome.Failed(result.summary(), retryable = result.hadRetryableFailure)
        }
    }

    private fun notLoggedInOrSkipped(userInitiated: Boolean, requireAutoSync: Boolean): SyncOutcome {
        return if (userInitiated && !requireAutoSync) {
            SyncOutcome.NotLoggedIn
        } else {
            SyncOutcome.Skipped
        }
    }
}

sealed interface ExclusiveSyncActionResult<out T> {
    data class Completed<T>(val value: T) : ExclusiveSyncActionResult<T>
    data object Busy : ExclusiveSyncActionResult<Nothing>
    data object Unavailable : ExclusiveSyncActionResult<Nothing>
}

sealed class SyncOutcome {
    data object Success : SyncOutcome()
    data class CompletedWithSkips(val summary: String) : SyncOutcome()
    data class NoData(val message: String) : SyncOutcome()

    data class PartialSuccess(
        val succeeded: Int,
        val failed: Int,
        val summary: String,
    ) : SyncOutcome()

    data object Skipped : SyncOutcome()
    data object NotLoggedIn : SyncOutcome()
    data object HealthUnavailable : SyncOutcome()
    /** Another [SyncCoordinator.run] is already in flight — do not start a second one. */
    data object AlreadyRunning : SyncOutcome()

    data class Failed(
        val message: String?,
        val retryable: Boolean = true,
    ) : SyncOutcome()

    fun shouldRetryBackground(): Boolean = when (this) {
        is Failed -> retryable
        else -> false
    }

    fun toStatusCode(): String = when (this) {
        Success -> STATUS_SUCCESS
        is CompletedWithSkips -> STATUS_COMPLETED_WITH_SKIPS
        is NoData -> STATUS_NO_DATA
        is PartialSuccess -> STATUS_PARTIAL_SUCCESS
        Skipped -> STATUS_SKIPPED
        NotLoggedIn -> STATUS_NOT_LOGGED_IN
        HealthUnavailable -> STATUS_HEALTH_UNAVAILABLE
        AlreadyRunning -> STATUS_ALREADY_RUNNING
        is Failed -> STATUS_FAILED
    }

    companion object {
        const val STATUS_SUCCESS = "success"
        const val STATUS_COMPLETED_WITH_SKIPS = "completed_with_skips"
        const val STATUS_NO_DATA = "no_data"
        const val STATUS_PARTIAL_SUCCESS = "partial_success"
        const val STATUS_SKIPPED = "skipped"
        const val STATUS_NOT_LOGGED_IN = "not_logged_in"
        const val STATUS_HEALTH_UNAVAILABLE = "health_unavailable"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_FAILED = "failed"
        const val STATUS_ALREADY_RUNNING = "already_running"
    }
}
