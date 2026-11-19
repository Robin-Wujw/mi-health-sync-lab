package com.bettermifitness.sync.sync

import com.bettermifitness.sync.data.preferences.CredentialsPort
import com.bettermifitness.sync.data.preferences.AutoSyncTiming
import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.preferences.SyncPreferencesPort
import com.bettermifitness.sync.data.preferences.SyncSessionPort
import com.bettermifitness.sync.data.repository.HealthSyncRunner
import com.bettermifitness.sync.data.repository.SyncRunResult
import com.bettermifitness.sync.data.repository.SyncPageScope
import com.bettermifitness.sync.health.HealthAvailability
import com.bettermifitness.sync.health.HealthPermissionRequester
import com.bettermifitness.sync.health.HealthStore
import com.bettermifitness.sync.health.HealthAccessBlock
import com.bettermifitness.sync.health.HealthRecordIds
import com.bettermifitness.sync.i18n.L10n
import com.bettermifitness.sync.ui.SyncSelection
import com.bettermifitness.sync.zepp.ZeppSyncService
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val automaticRunner = AutomaticSyncRunner(syncPreferences, repository)
    private val handoffMutex = Mutex()
    private var replacementWork: Job? = null
    private val activeWork = MutableStateFlow<Job?>(null)
    private val activeAutomaticWork = MutableStateFlow<Job?>(null)
    val replacementRequest: StateFlow<UserSyncRequest?>
        field = MutableStateFlow(null)
    /** True while a [run] holds the single-flight lock (including permission prompts). */
    val isRunning: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val activeSelection: StateFlow<ActiveSyncSelection?>
        field = MutableStateFlow(null)
    val activeTask: StateFlow<ActiveSyncTask?>
        field = MutableStateFlow(null)
    internal val latestStartedTask: StateFlow<ActiveSyncTask?>
        field = MutableStateFlow(null)
    private var activeTaskResult: SyncRunResult? = null

    val isStopping: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Last finished outcome (not [SyncOutcome.AlreadyRunning]). */
    val lastOutcome: StateFlow<SyncOutcome?>
        field = MutableStateFlow(null)

    val lastHealthAccessBlocks: StateFlow<Map<String, HealthAccessBlock>>
        field = MutableStateFlow(emptyMap())

    /** A bounded user action shares the normal sync lock without changing saved sync settings/results. */
    suspend fun <T> runExclusiveUserAction(action: suspend () -> T): ExclusiveSyncActionResult<T> = coroutineScope {
        if (LiveTestSessionGate.isActive) return@coroutineScope ExclusiveSyncActionResult.Unavailable
        if (!runMutex.tryLock()) return@coroutineScope ExclusiveSyncActionResult.Busy
        activeWork.value = coroutineContext.job
        isRunning.value = true
        try {
            if (isStopping.value || replacementRequest.value != null) return@coroutineScope ExclusiveSyncActionResult.Busy
            ExclusiveSyncActionResult.Completed(coroutineScope { trackSyncActivity(action) })
        } finally {
            activeWork.value = null
            isRunning.value = false
            runMutex.unlock()
        }
    }

    suspend fun cancelActiveRunAndAwait() {
        val active = activeWork.value ?: return
        active.cancel(UserSyncStopped())
        active.join()
    }

    suspend fun cancelAutomaticRunAndAwait() {
        val active = activeAutomaticWork.value ?: return
        active.cancel(UserSyncStopped())
        active.join()
    }

    suspend fun hasPendingAutomaticRetry(owner: String): Boolean {
        if (!syncPreferences.autoSync.first() || session.requiresReauthentication || credentials.token.first().isNullOrEmpty()) return false
        val retry = syncPreferences.readAutoSyncTiming().retry ?: return false
        return retry.owner == owner && retry.valid(now().epochSeconds, credentials.accountGeneration())
    }

    internal suspend fun automaticScheduleDelay(owner: String): Long? {
        if (!syncPreferences.autoSync.first()) return null
        return scheduledAutomaticDelay(syncPreferences.readAutoSyncTiming(), owner, credentials.accountGeneration(),
            now().epochSeconds)
    }

    internal suspend fun recordAutomaticSkip(reason: AutomaticSkipReason, generation: Long? = null) {
        try {
            syncPreferences.storeAutomaticSkip(AutomaticSkip(reason, now().toString(), generation ?: credentials.accountGeneration()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
    }

    suspend fun clearAfterSignOut() = withContext(NonCancellable) {
        cancelActiveRunAndAwait()
        lastHealthAccessBlocks.value = emptyMap()
        healthPermissions.clearCachedWorkoutRoutes()
    }

    suspend fun replaceUserSync(
        request: UserSyncRequest,
        markPreviousStopping: suspend () -> Unit = {},
        persistReplacement: suspend () -> Boolean = { true },
        start: suspend () -> SyncOutcome,
    ): SyncOutcome = coroutineScope {
        val owner = coroutineContext.job
        val superseded = handoffMutex.withLock {
            val queued = replacementRequest.value
            if (isStopping.value || queued?.runId == request.runId) {
                return@coroutineScope SyncOutcome.AlreadyRunning
            }
            val previous = if (queued != null) replacementWork else null
            withContext(NonCancellable) {
                if (!persistReplacement()) return@withContext false
                replacementRequest.value = request
                replacementWork = owner
                true
            }.also { if (!it) return@coroutineScope SyncOutcome.AlreadyRunning }
            previous
        }
        try {
            withContext(NonCancellable) {
                superseded?.cancel(UserSyncStopped())
                superseded?.join()
                markPreviousStopping()
                activeWork.value?.cancel(UserSyncStopped())
            }
            coroutineContext.ensureActive()
            cancelActiveRunAndAwait()
            coroutineContext.ensureActive()
            start()
        } finally {
            withContext(NonCancellable) {
                handoffMutex.withLock {
                    if (replacementWork === owner) replacementWork = null
                    replacementRequest.compareAndSet(request, null)
                }
            }
        }
    }

    suspend fun stopByUser(
        persistStop: suspend () -> Unit = {},
        stopDispatchedRun: suspend () -> Unit = {},
    ): Boolean = withContext(NonCancellable) {
        val replacement = handoffMutex.withLock {
            if (isStopping.value || LiveTestSessionGate.isActive) return@withContext false
            isStopping.value = true
            replacementWork
        }
        try {
            persistStop()
            syncPreferences.pauseAutomaticUntil((now() + 1.hours).epochSeconds)
            replacement?.cancel(UserSyncStopped())
            replacement?.join()
            stopDispatchedRun()
            cancelActiveRunAndAwait()
            repository.clearPageCheckpoints()
            lastOutcome.value = SyncOutcome.Cancelled
            true
        } finally {
            handoffMutex.withLock { isStopping.value = false }
        }
    }

    /** Profile/keepalive refresh must not replace the API client during a health write or readback. */
    suspend fun runSessionMaintenance(action: suspend () -> Unit): Boolean {
        if (LiveTestSessionGate.isActive || !runMutex.tryLock()) return false
        return try {
            if (isStopping.value || replacementRequest.value != null) return false
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
    @OptIn(ExperimentalUuidApi::class)
    suspend fun run(
        rangeDaysOverride: Int = 0,
        requireAutoSync: Boolean = false,
        requestHealthPermissions: Boolean = true,
        recordAsBackground: Boolean = false,
        resetProgress: Boolean = false,
        userInitiated: Boolean = true,
        request: UserSyncRequest? = null,
        source: SyncTaskSource = if (requireAutoSync) SyncTaskSource.AUTOMATIC else SyncTaskSource.MANUAL,
        automaticAttempt: AutomaticWorkAttempt? = null,
        persistedAutomaticOwner: String? = null,
        onTaskAccepted: suspend (ActiveSyncTask) -> Unit = {},
        onTaskFinished: suspend (SyncTaskSummary) -> Unit = {},
    ): SyncOutcome = coroutineScope {
        // Check before credentials, session refresh, permissions, or preferences.
        // Instrumentation invokes a separate bounded repository entry point.
        if (LiveTestSessionGate.isActive) return@coroutineScope SyncOutcome.Skipped
        if (!runMutex.tryLock()) {
            if (requireAutoSync) recordAutomaticSkip(when {
                activeAutomaticWork.value != null -> AutomaticSkipReason.SYNC_BUSY
                activeWork.value == null && replacementRequest.value == null -> AutomaticSkipReason.MAINTENANCE_BUSY
                else -> AutomaticSkipReason.MANUAL_BUSY
            })
            return@coroutineScope SyncOutcome.AlreadyRunning
        }
        activeWork.value = coroutineContext.job
        if (requireAutoSync) activeAutomaticWork.value = coroutineContext.job
        isRunning.value = true
        var terminalStatus = "interrupted"
        try {
            if (isStopping.value || replacementRequest.value?.let { it != request } == true) {
                if (requireAutoSync) recordAutomaticSkip(AutomaticSkipReason.MANUAL_BUSY)
                return@coroutineScope SyncOutcome.AlreadyRunning
            }
            val generation = request?.accountGeneration ?: credentials.accountGeneration()
            if (credentials.accountGeneration() != generation) return@coroutineScope SyncOutcome.Skipped
            if (request != null) {
                handoffMutex.withLock {
                    if (isStopping.value || replacementRequest.value?.let { it != request } == true) {
                        return@coroutineScope SyncOutcome.AlreadyRunning
                    }
                    replacementRequest.compareAndSet(request, null)
                }
            }
            val outcome = coroutineScope {
                runInternal(
                    rangeDaysOverride = rangeDaysOverride,
                    requireAutoSync = requireAutoSync,
                    requestHealthPermissions = requestHealthPermissions,
                    recordAsBackground = recordAsBackground,
                    resetProgress = resetProgress,
                    userInitiated = userInitiated,
                    generation = generation,
                    request = request,
                    taskId = request?.runId ?: Uuid.random().toString(),
                    source = source,
                    suppliedAutomaticAttempt = automaticAttempt,
                    persistedAutomaticOwner = persistedAutomaticOwner,
                    onTaskAccepted = onTaskAccepted,
                )
            }
            // Do not clobber last sync prefs when we never started work.
            if (outcome !is SyncOutcome.AlreadyRunning && credentials.accountGeneration() == generation) {
                recordOutcome(outcome, recordAsBackground, userInitiated)
                if (!requireAutoSync || outcome != SyncOutcome.Skipped) lastOutcome.value = outcome
            }
            terminalStatus = outcome.toStatusCode()
            outcome
        } catch (stopped: UserSyncStopped) {
            terminalStatus = SyncOutcome.STATUS_CANCELLED
            throw stopped
        } finally {
            try {
                if (request != null && terminalStatus != "interrupted") {
                    withContext(NonCancellable) {
                        repository.discardPageCheckpoints(SyncPageScope(request.runId, request.accountGeneration))
                    }
                }
                activeTask.value?.let { task ->
                    val summary = task.summarize(terminalStatus, now(), activeTaskResult)
                    withContext(NonCancellable) {
                        if (userInitiated && !requireAutoSync && credentials.accountGeneration() == task.accountGeneration) {
                            syncPreferences.recordManualFinish(now().epochSeconds)
                        }
                        onTaskFinished(summary)
                    }
                }
            } finally {
                activeTask.value = null
                activeTaskResult = null
                activeSelection.value = null
                activeWork.value = null
                activeAutomaticWork.value = null
                isRunning.value = false
                runMutex.unlock()
            }
        }
    }

    private suspend fun runInternal(
        rangeDaysOverride: Int,
        requireAutoSync: Boolean,
        requestHealthPermissions: Boolean,
        recordAsBackground: Boolean,
        resetProgress: Boolean,
        userInitiated: Boolean,
        generation: Long,
        request: UserSyncRequest?,
        taskId: String,
        source: SyncTaskSource,
        suppliedAutomaticAttempt: AutomaticWorkAttempt?,
        persistedAutomaticOwner: String?,
        onTaskAccepted: suspend (ActiveSyncTask) -> Unit,
    ): SyncOutcome {
        if (credentials.token.first().isNullOrEmpty() || !session.ensureActive() || session.requiresReauthentication) {
            if (requireAutoSync) recordAutomaticSkip(AutomaticSkipReason.SIGN_IN_REQUIRED, generation)
            return notLoggedInOrSkipped(userInitiated, requireAutoSync)
        }

        // Roll the passport session forward if it has gone stale, before the auto-sync
        // gate: even a run that ends up Skipped (background tick racing a disabled toggle)
        // keeps the passToken rotation chain alive for the next real sync. Successful
        // renewal is limited to the six-hour cadence. A hard rejection pauses fan-out;
        // transient renewal failures still allow the lazy 401 recovery path.
        session.refreshSessionIfStale()
        if (session.requiresReauthentication) {
            if (requireAutoSync) recordAutomaticSkip(AutomaticSkipReason.SIGN_IN_REQUIRED, generation)
            return notLoggedInOrSkipped(userInitiated, requireAutoSync)
        }

        if (requireAutoSync && !syncPreferences.autoSync.first()) {
            recordAutomaticSkip(AutomaticSkipReason.DISABLED, generation)
            return SyncOutcome.Skipped
        }

        // A return from the background must not restart a recent manual run.
        // The durable start/end marker also covers cancellation and process recreation.
        // Scheduled workers retain their own retry/backoff policy.
        if (requireAutoSync && !recordAsBackground && !userInitiated && hasRecentSyncActivity()) {
            recordAutomaticSkip(AutomaticSkipReason.MANUAL_COOLDOWN, generation)
            return SyncOutcome.Skipped
        }

        val selection = request?.selection ?: captureSelection(if (requireAutoSync) 1 else rangeDaysOverride)
        val enabled = selection.enabled
        if (enabled.none { it in SyncPreferences.ALL_METRIC_KEYS || it in SyncPreferences.INBOUND_METRIC_KEYS ||
                it in SyncPreferences.ZEPP_CLOUD_METRIC_KEYS }) {
            if (requireAutoSync) recordAutomaticSkip(AutomaticSkipReason.NO_SELECTION, generation)
            return SyncOutcome.Skipped
        }

        if (requiresUnavailableHealthStore(enabled)) {
            if (requireAutoSync) recordAutomaticSkip(AutomaticSkipReason.HEALTH_UNAVAILABLE, generation)
            return SyncOutcome.HealthUnavailable
        }

        val timing = if (requireAutoSync) syncPreferences.readAutoSyncTiming() else null
        val automaticAttempt = suppliedAutomaticAttempt ?: if (timing != null && persistedAutomaticOwner != null) {
            resumedAutomaticAttempt(timing, persistedAutomaticOwner, generation, now().epochSeconds)
        } else null
        if (timing != null) {
            automaticSkipBeforeStart(timing, recordAsBackground, generation, automaticAttempt)?.let {
                recordAutomaticSkip(it, generation)
                return SyncOutcome.Skipped
            }
        }
        lastHealthAccessBlocks.value = emptyMap()
        if (resetProgress || requireAutoSync) repository.resetProgress()
        activeSelection.value = selection
        val task = ActiveSyncTask(taskId, source, generation, selection, now())
        latestStartedTask.value = task
        activeTask.value = task
        return trackSyncActivity {
            if (requestHealthPermissions) {
                requestSelectedPermissions(selection.enabled, selection.rangeDays)?.let { return@trackSyncActivity it }
            }

            try {
                if (credentials.accountGeneration() != generation) {
                    if (requireAutoSync) recordAutomaticSkip(AutomaticSkipReason.ACCOUNT_CHANGED, generation)
                    return@trackSyncActivity SyncOutcome.Skipped
                }
                if (requireAutoSync && !syncPreferences.autoSync.first()) {
                    recordAutomaticSkip(AutomaticSkipReason.DISABLED, generation)
                    return@trackSyncActivity SyncOutcome.Skipped
                }
                onTaskAccepted(task)
                if (credentials.accountGeneration() != generation) return@trackSyncActivity SyncOutcome.Skipped
                lastOutcome.value = null
                val sourceFailure = manualSourceFailure(request)
                if (sourceFailure != null) return@trackSyncActivity sourceFailure
                val result = runSelectedWork(selection, request, requireAutoSync, taskId, generation)
                activeTaskResult = result
                if (credentials.accountGeneration() != generation) return@trackSyncActivity SyncOutcome.Skipped
                if (requireAutoSync && automaticAttempt != null) {
                    val previous = timing?.retry?.takeIf { it.owner == automaticAttempt.owner && automaticAttempt.number > 0 }
                    syncPreferences.storeAutomaticRetry(nextAutomaticRetry(automaticAttempt, generation, now().epochSeconds,
                        previous, result.hadRetryableFailure && !result.hadAuthFailure))
                }
                lastHealthAccessBlocks.value = result.healthAccessBlocks
                if (requireAutoSync && !result.hasVerifiedProgress && result.noData == 0 && result.healthAccessBlocks.isNotEmpty()) {
                    recordAutomaticSkip(AutomaticSkipReason.MISSING_PERMISSION, generation)
                }
                mapRunResult(result, selection.to.toString(), recordAsBackground, userInitiated, requireAutoSync)
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

    private suspend fun runSelectedWork(selection: ActiveSyncSelection, request: UserSyncRequest?, requireAutoSync: Boolean,
        taskId: String, generation: Long): SyncRunResult {
        if (!requireAutoSync) return if (request != null) repository.syncUserRequest(request)
            else repository.syncAll(selection.from.toString(), selection.to.toString(), selection.enabled)
        val zeppIdentity = zeppSync?.automaticSourceIdentity()
        return automaticRunner.run(selection, automaticCheckpointKeys(selection.enabled, zeppIdentity), taskId, generation,
            zeppSourceIdentity = zeppIdentity) {
            credentials.accountGeneration() == generation && !session.requiresReauthentication &&
                zeppSync?.automaticSourceIdentity() == zeppIdentity
        }
    }

    private suspend fun manualSourceFailure(request: UserSyncRequest?): SyncOutcome.Failed? {
        if (request == null || zeppSync == null) return null
        val expected = request.sources?.zeppIdentity
        val changed = if (expected != null) expected != zeppSync.automaticSourceIdentity()
            else SyncPreferences.ZEPP_CLOUD_IMPORT_KEY in request.selection.enabled
        return if (changed) SyncOutcome.Failed(L10n.text(L10n.syncZeppSourceChanged), retryable = false) else null
    }

    private suspend fun automaticCheckpointKeys(enabled: Set<String>, zeppIdentity: Long?): Map<String, String> {
        val account = credentials.loadCredentials() ?: return emptyMap()
        val calendarZone = syncPreferences.readSourceCalendarZoneId()
        return (enabled - AutomaticSyncRunner.LATEST_ONLY).mapNotNull { metric ->
            val source = when (metric) {
                SyncPreferences.ZEPP_CLOUD_IMPORT_KEY -> zeppIdentity?.let { "zepp-cloud-$it" } ?: return@mapNotNull null
                in SyncPreferences.INBOUND_METRIC_KEYS -> "platform-health-zepp-${zeppIdentity ?: "local"}" +
                    calendarZone?.let { "-calendar-$it" }.orEmpty()
                else -> "mi-cloud-export"
            }
            metric to HealthRecordIds.version(account.userId, account.region, source, metric).toString()
        }.toMap()
    }

    private suspend fun automaticSkipBeforeStart(timing: AutoSyncTiming, scheduled: Boolean, generation: Long,
        attempt: AutomaticWorkAttempt?): AutomaticSkipReason? {
        val requestedAt = now().epochSeconds
        if (credentials.accountGeneration() != generation) return AutomaticSkipReason.ACCOUNT_CHANGED
        if (!syncPreferences.autoSync.first()) return AutomaticSkipReason.DISABLED
        if (timing.isPaused(requestedAt)) return AutomaticSkipReason.PAUSED
        if (!scheduled) return null
        if (timing.isInManualCooldown(requestedAt)) return AutomaticSkipReason.MANUAL_COOLDOWN
        return when (automaticDue(timing, requestedAt, generation, attempt)) {
            AutomaticDue.NORMAL -> if (syncPreferences.acceptAutomaticStart(requestedAt)) null else AutomaticSkipReason.DISABLED
            AutomaticDue.RETRY -> null
            AutomaticDue.RETRY_DEFERRED -> AutomaticSkipReason.RETRY_DEFERRED
            AutomaticDue.SKIP -> if ((attempt?.number ?: 0) > 0) AutomaticSkipReason.STALE_RETRY
                else if (timing.retry?.valid(requestedAt, generation) == true) AutomaticSkipReason.RETRY_DEFERRED
                else AutomaticSkipReason.NOT_DUE
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun captureUserSyncRequest(): UserSyncRequest {
        val generation = credentials.accountGeneration()
        val config = syncPreferences.captureSyncConfig()
        val sources = UserSyncSources(zeppSync?.automaticSourceIdentity(), config.sourceCalendarZoneId)
        val connected = zeppSync?.let { sources.zeppIdentity != null }
        val selection = selectionFor(config.rangeDays, config.enabledMetrics, connected, now())
        return UserSyncRequest(Uuid.random().toString(), generation, selection, sources)
    }

    private suspend fun captureSelection(rangeDaysOverride: Int = 0): ActiveSyncSelection {
        val end = now()
        val config = syncPreferences.captureSyncConfig()
        val days = rangeDaysOverride.takeIf { it > 0 } ?: config.rangeDays
        return selectionFor(days, config.enabledMetrics, zeppSync?.isConnectedNow(), end)
    }

    private fun selectionFor(days: Int, enabled: Set<String>, zeppConnected: Boolean?, end: Instant) =
        ActiveSyncSelection(days, effectiveEnabledMetrics(enabled, zeppConnected).toSet(), end - days.days, end)

    /** Foreground permission UI only; the Android service rechecks all run gates itself. */
    suspend fun prepareUserSync(): SyncOutcome? = prepareUserSync(captureUserSyncRequest())

    suspend fun prepareUserSync(request: UserSyncRequest): SyncOutcome? = coroutineScope {
        if (LiveTestSessionGate.isActive) return@coroutineScope SyncOutcome.Skipped
        if (replacementRequest.value == request) runMutex.lock()
        else if (!runMutex.tryLock()) return@coroutineScope SyncOutcome.AlreadyRunning
        activeWork.value = coroutineContext.job
        isRunning.value = true
        try {
            if (isStopping.value || replacementRequest.value?.let { it != request } == true) {
                return@coroutineScope SyncOutcome.AlreadyRunning
            }
            if (credentials.accountGeneration() != request.accountGeneration) return@coroutineScope SyncOutcome.Skipped
            if (credentials.token.first().isNullOrEmpty() || !session.ensureActive() || session.requiresReauthentication) {
                return@coroutineScope publishPreparationOutcome(SyncOutcome.NotLoggedIn)
            }
            manualSourceFailure(request)?.let { return@coroutineScope publishPreparationOutcome(it) }
            val enabled = request.selection.enabled
            if (enabled.none { it in SyncPreferences.ALL_METRIC_KEYS || it in SyncPreferences.INBOUND_METRIC_KEYS ||
                    it in SyncPreferences.ZEPP_CLOUD_METRIC_KEYS })
                return@coroutineScope publishPreparationOutcome(SyncOutcome.Skipped)
            if (requiresUnavailableHealthStore(enabled)) return@coroutineScope publishPreparationOutcome(SyncOutcome.HealthUnavailable)
            val permissionFailure = requestSelectedPermissions(enabled, request.selection.rangeDays)
            if (credentials.accountGeneration() != request.accountGeneration) return@coroutineScope SyncOutcome.Skipped
            (manualSourceFailure(request) ?: permissionFailure)?.let {
                publishPreparationOutcome(it)
            }
        } finally {
            activeWork.value = null
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
        lastHealthAccessBlocks.value = emptyMap()
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
            if (enabled.any { it in SyncPreferences.ZEPP_CLOUD_METRIC_KEYS }) return null
            return SyncOutcome.Failed(
                message = e.message ?: "Health permissions denied",
                retryable = false,
            )
        }
        return null
    }

    private suspend fun requiresUnavailableHealthStore(enabled: Set<String>): Boolean =
        enabled.none { it in SyncPreferences.ZEPP_CLOUD_METRIC_KEYS } &&
            SyncSelection.requiresHealthConnect(enabled) && !healthAvailability.isAvailable()

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
    private fun effectiveEnabledMetrics(configured: Set<String>, zeppConnected: Boolean?): Set<String> {
        return when (zeppConnected) {
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

data class ActiveSyncSelection(val rangeDays: Int, val enabled: Set<String>, val from: Instant, val to: Instant)

data class UserSyncRequest(val runId: String, val accountGeneration: Long, val selection: ActiveSyncSelection,
    val sources: UserSyncSources? = null)

internal class UserSyncStopped : CancellationException("Sync cancelled after explicit request")

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
    data object Cancelled : SyncOutcome()
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
        Cancelled -> STATUS_CANCELLED
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
