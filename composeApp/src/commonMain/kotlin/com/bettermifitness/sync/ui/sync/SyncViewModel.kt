package com.bettermifitness.sync.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.repository.HealthRepository
import com.bettermifitness.sync.data.repository.SyncProgress
import com.bettermifitness.sync.data.repository.SyncState
import com.bettermifitness.sync.health.HealthAvailability
import com.bettermifitness.sync.health.HealthAccessBlock
import com.bettermifitness.sync.health.HealthPermissionRequester
import com.bettermifitness.sync.i18n.L10n
import com.bettermifitness.sync.sync.SyncCoordinator
import com.bettermifitness.sync.sync.ActiveSyncTask
import com.bettermifitness.sync.sync.SyncTaskSummary
import com.bettermifitness.sync.sync.AutomaticSkip
import com.bettermifitness.sync.sync.SyncOutcome
import com.bettermifitness.sync.sync.UserSyncRequest
import com.bettermifitness.sync.sync.observeRecoverableUserSync
import com.bettermifitness.sync.sync.observeSyncTaskHistory
import com.bettermifitness.sync.sync.recoverUserSync
import com.bettermifitness.sync.sync.dispatchUserSync
import com.bettermifitness.sync.sync.stopUserSync
import com.bettermifitness.sync.sync.supportsUserSyncReplacement
import com.bettermifitness.sync.ui.SyncMetric
import com.bettermifitness.sync.ui.SyncSelection
import com.bettermifitness.sync.zepp.ZeppSyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncUiState(
    val requiresSignIn: Boolean = false,
    val isSyncing: Boolean = false,
    val isStopping: Boolean = false,
    val wasStopped: Boolean = false,
    val healthAvailable: Boolean = true,
    val availabilityHint: String? = null,
    val permissionError: String? = null,
    val accessBlocks: Map<String, HealthAccessBlock> = emptyMap(),
    /** Non-fatal banner after a run (success / partial). */
    val outcomeMessage: String? = null,
    val outcomeIsWarning: Boolean = false,
    val healthServiceName: String = "",
    val rangeDays: Int = 7,
    val activeRangeDays: Int? = null,
    val activeTask: ActiveSyncTask? = null,
    val pendingRangeDays: Int? = null,
    val recoverableRangeDays: Int? = null,
    /** Empty until prefs load — rows render only once. */
    val enabledMetrics: Set<String> = emptySet(),
    /** False until the first hot-snapshot emission — screens hold skeleton. */
    val prefsReady: Boolean = false,
    val progress: SyncProgress = SyncProgress(),
    val readinessChecked: Boolean = false,
) {
    val visibleMetrics: List<SyncMetric>
        get() = SyncMetric.entries.filter { it.key in enabledMetrics }

    val visibleInboundMetrics: List<String>
        get() = (SyncPreferences.INBOUND_METRIC_KEYS + SyncPreferences.ZEPP_CLOUD_METRIC_KEYS).filter { it in enabledMetrics }

    val hasSelectedSyncTasks: Boolean
        get() = SyncSelection.selectedTaskKeys(enabledMetrics).isNotEmpty()

    val canReplaceRange: Boolean
        get() = supportsUserSyncReplacement && !isStopping &&
            (pendingRangeDays ?: activeRangeDays)?.let { it != rangeDays } == true
}

private data class SyncActivity(
    val running: Boolean,
    val active: ActiveSyncTask?,
    val queued: UserSyncRequest?,
    val stopping: Boolean,
    val recovery: UserSyncRequest?,
)

private data class SyncSummaryInput(
    val outcome: SyncOutcome?,
    val accessBlocks: Map<String, HealthAccessBlock>,
    val latestTask: ActiveSyncTask?,
    val auth: Pair<Boolean, Boolean>,
)

internal data class SyncScreenFeedback(
    val task: ActiveSyncTask?,
    val error: String? = null,
    val message: String? = null,
    val warning: Boolean = false,
) {
    fun forTask(current: ActiveSyncTask?): SyncScreenFeedback? = takeIf { task === current }
}

internal fun ownsSyncCompletion(before: ActiveSyncTask?, current: ActiveSyncTask?, request: UserSyncRequest): Boolean =
    current === before || (current?.id == request.runId && current.accountGeneration == request.accountGeneration)

data class SyncHistorySnapshot(val tasks: List<SyncTaskSummary> = emptyList(), val automaticSkip: AutomaticSkip? = null)

/**
 * Foreground sync UI: observes shared [SyncCoordinator] + repository progress.
 * Does **not** own single-flight — normal sync and explicit recovery share the coordinator gate.
 */
class SyncViewModel(
    private val repository: HealthRepository,
    private val healthAvailability: HealthAvailability,
    private val healthPermissions: HealthPermissionRequester,
    private val syncPreferences: SyncPreferences,
    private val syncCoordinator: SyncCoordinator,
    private val session: MiSessionManager,
    private val zeppSync: ZeppSyncService,
) : ViewModel() {

    private val local = MutableStateFlow(
        LocalSyncState(healthServiceName = healthAvailability.healthServiceName()),
    )
    private var resultOwner = 0L
    private var permissionOwner = 0L
    private var readinessOwner = 0L
    private val recovery = observeRecoverableUserSync().catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history: StateFlow<SyncHistorySnapshot> = combine(
        observeSyncTaskHistory().catch { emit(emptyList()) },
        syncPreferences.lastAutomaticSkip.catch { emit(null) },
        ::SyncHistorySnapshot,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncHistorySnapshot())

    val uiState: StateFlow<SyncUiState> = combine(
        local,
        repository.syncProgress,
        syncPreferences.snapshot,
        combine(syncCoordinator.isRunning, syncCoordinator.activeTask, syncCoordinator.replacementRequest,
            syncCoordinator.isStopping, recovery, ::SyncActivity),
        combine(
            syncCoordinator.lastOutcome,
            syncCoordinator.lastHealthAccessBlocks,
            syncCoordinator.latestStartedTask,
            combine(session.reauthenticationRequired, zeppSync.connected) { requiresSignIn, zeppConnected ->
                requiresSignIn to zeppConnected
            },
            ::SyncSummaryInput,
        ),
    ) { local, progress, snap, activity, outcomeAndAuth ->
        val running = activity.running
        val active = activity.active?.selection
        val showingTerminal = !running && !activity.stopping && activity.queued == null
        val lastOutcome = outcomeAndAuth.outcome.takeIf { showingTerminal }
        val feedback = local.feedback?.forTask(outcomeAndAuth.latestTask)?.takeIf { showingTerminal }
        val auth = outcomeAndAuth.auth
        val requiresSignIn = auth.first
        val zeppConnected = auth.second
        val enabled = if (zeppConnected) {
            snap.enabledMetrics + SyncPreferences.ZEPP_CLOUD_IMPORT_KEY
        } else {
            snap.enabledMetrics - SyncPreferences.ZEPP_CLOUD_IMPORT_KEY
        }
        SyncUiState(
            requiresSignIn = requiresSignIn,
            // App-wide: still true after leaving and re-entering Sync.
            isSyncing = running,
            isStopping = activity.stopping,
            wasStopped = !running && !activity.stopping && lastOutcome == SyncOutcome.Cancelled,
            healthAvailable = local.healthAvailable || zeppConnected || !SyncSelection.requiresHealthConnect(enabled),
            availabilityHint = local.availabilityHint,
            accessBlocks = outcomeAndAuth.accessBlocks.takeIf { !running && !activity.stopping && activity.queued == null }.orEmpty(),
            permissionError = if (!showingTerminal) null else when (lastOutcome) {
                is SyncOutcome.Failed -> lastOutcome.message ?: L10n.text(L10n.syncFailed)
                SyncOutcome.HealthUnavailable -> L10n.textFmt(L10n.syncHealthUnavailable, local.healthServiceName)
                SyncOutcome.NotLoggedIn -> L10n.text(L10n.syncNotLoggedIn)
                else -> feedback?.error
            },
            outcomeMessage = feedback?.message,
            outcomeIsWarning = feedback?.warning == true,
            healthServiceName = local.healthServiceName,
            rangeDays = snap.syncRangeDays,
            activeRangeDays = active?.rangeDays,
            activeTask = activity.active,
            pendingRangeDays = activity.queued?.selection?.rangeDays,
            recoverableRangeDays = activity.recovery?.selection?.rangeDays
                ?.takeIf { !running && !activity.stopping && activity.queued == null },
            enabledMetrics = active?.enabled ?: enabled,
            prefsReady = snap.ready,
            progress = if (running && activity.active == null) SyncProgress() else progress,
            readinessChecked = local.readinessChecked,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SyncUiState(
            healthServiceName = healthAvailability.healthServiceName(),
            isSyncing = syncCoordinator.isRunning.value,
        ),
    )

    init {
        viewModelScope.launch {
            val task = syncCoordinator.latestStartedTask.value
            val attempt = resultOwner
            checkHealthReadiness { attempt == resultOwner && task === syncCoordinator.latestStartedTask.value }
        }
    }

    fun startSync() {
        viewModelScope.launch {
            // Fast path — coordinator still enforces single-flight.
            if (syncCoordinator.isRunning.value) return@launch
            runSync()
        }
    }

    /** Compatibility entry point: normal sync now handles every selected import. */
    fun processUnfinishedData() = startSync()

    fun selectRange(days: Int) {
        if (days !in setOf(1, 7, 14, 30)) return
        viewModelScope.launch { syncPreferences.setSyncRangeDays(days) }
    }

    fun replaceSync() {
        if (!uiState.value.canReplaceRange) return
        viewModelScope.launch { runSync(replace = true) }
    }

    fun stopSync() {
        viewModelScope.launch {
            if (syncCoordinator.isStopping.value) return@launch
            val attempt = ++resultOwner
            val task = syncCoordinator.latestStartedTask.value
            try {
                if (stopUserSync(syncCoordinator) && attempt == resultOwner && task === syncCoordinator.latestStartedTask.value) {
                    local.update { it.copy(feedback = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (attempt == resultOwner && task === syncCoordinator.latestStartedTask.value) {
                    local.update { it.copy(feedback = SyncScreenFeedback(task, error = L10n.text(L10n.syncStopFailed))) }
                }
            }
        }
    }

    fun resumeQueuedSync() {
        if (uiState.value.recoverableRangeDays == null) return
        val request = recovery.value ?: return
        viewModelScope.launch { runSync(recovering = request) }
    }

    fun openHealthService() {
        viewModelScope.launch {
            if (syncCoordinator.isRunning.value) return@launch
            val task = syncCoordinator.latestStartedTask.value
            val attempt = resultOwner
            val prompt = ++permissionOwner
            val snapshot = syncPreferences.snapshot.value
            fun current() = prompt == permissionOwner && attempt == resultOwner &&
                task === syncCoordinator.latestStartedTask.value && !syncCoordinator.isRunning.value
            try {
                healthPermissions.requestPermissions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Denied / failed — open Settings or Health Connect below.
            }
            if (!current()) return@launch
            val feedback = requestSelectedInboundPermissions(snapshot.enabledMetrics, snapshot.syncRangeDays)
            if (!current()) return@launch
            local.update { state ->
                val previous = state.feedback?.forTask(task) ?: SyncScreenFeedback(task)
                state.copy(feedback = previous.copy(error = feedback))
            }
            if (!healthAvailability.hasWritePermissions() && current()) {
                healthAvailability.openHealthService()
            }
            if (current()) checkHealthReadiness(::current)
        }
    }

    fun stateFor(progress: SyncProgress, key: String): SyncState = when (key) {
        "heart_rate" -> progress.heartRate
        "resting_heart_rate" -> progress.restingHeartRate
        "sleep" -> progress.sleep
        "hrv" -> progress.hrv
        "steps" -> progress.steps
        "distance" -> progress.distance
        "active_calories" -> progress.activeCalories
        "spo2" -> progress.spo2
        "weight" -> progress.weight
        "workouts" -> progress.workouts
        "blood_pressure" -> progress.bloodPressure
        "temperature" -> progress.temperature
        "vo2_max" -> progress.vo2Max
        SyncPreferences.HEART_RATE_IMPORT_KEY -> progress.heartRateImport
        SyncPreferences.RESTING_HEART_RATE_IMPORT_KEY -> progress.restingHeartRateImport
        SyncPreferences.SLEEP_IMPORT_KEY -> progress.sleepImport
        SyncPreferences.HRV_IMPORT_KEY -> progress.hrvImport
        SyncPreferences.SPO2_IMPORT_KEY -> progress.spo2Import
        SyncPreferences.WORKOUTS_IMPORT_KEY -> progress.workoutsImport
        SyncPreferences.VO2_MAX_IMPORT_KEY -> progress.vo2MaxImport
        SyncPreferences.ZEPP_CLOUD_IMPORT_KEY -> progress.zeppCloudImport
        else -> SyncState.Idle
    }

    private suspend fun requestSelectedInboundPermissions(enabled: Set<String>, rangeDays: Int): String? {
        val requested = SyncSelection.inboundMetrics(enabled)
        if (requested.isEmpty()) return null
        return try {
            val granted = healthPermissions.requestReadPermissions(
                requested, includeBackground = true, includeHistory = rangeDays > 27)
            val missing = requested - granted
            if (missing.isEmpty()) null else L10n.textFmt(L10n.settingsInboundPermissionsMissing,
                missing.joinToString(", ") { metric -> L10n.metric(metric.metricKey) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            L10n.text(L10n.settingsInboundPermissionsFailed)
        }
    }

    /** Health check only — never auto-starts sync on screen enter (spam-safe). */
    private suspend fun checkHealthReadiness(current: () -> Boolean) {
        val check = ++readinessOwner
        try {
            val available = healthAvailability.isAvailable()
            val hint = healthAvailability.availabilityHint()
            val perms = if (available) {
                try {
                    healthAvailability.hasWritePermissions()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    true
                }
            } else {
                false
            }
            if (check == readinessOwner && current()) local.update {
                it.copy(
                    healthAvailable = available,
                    availabilityHint = when {
                        !available -> hint
                        !perms -> hint
                            ?: L10n.text(L10n.healthPermissionsIncomplete)
                        else -> null
                    },
                    readinessChecked = true,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (check == readinessOwner && current()) local.update {
                it.copy(
                    healthAvailable = false,
                    availabilityHint = L10n.text(L10n.healthNotAvailable),
                    readinessChecked = true,
                )
            }
        }
    }

    private suspend fun runSync(replace: Boolean = false, recovering: UserSyncRequest? = null) {
        val attempt = ++resultOwner
        val before = syncCoordinator.latestStartedTask.value
        var feedbackTask = before
        local.update { it.copy(feedback = null) }
        try {
            val request = recovering ?: syncCoordinator.captureUserSyncRequest()
            if (attempt != resultOwner) return
            val outcome = if (recovering != null) recoverUserSync(syncCoordinator, request)
                else dispatchUserSync(syncCoordinator, request, replace)
            val current = syncCoordinator.latestStartedTask.value
            if (attempt != resultOwner || !ownsSyncCompletion(before, current, request)) return
            feedbackTask = current
            local.update { it.copy(feedback = outcomeFeedback(outcome, current, it.healthServiceName)) }
        } finally {
            fun current() = attempt == resultOwner && feedbackTask === syncCoordinator.latestStartedTask.value
            if (current()) checkHealthReadiness(::current)
        }
    }

    private fun outcomeFeedback(outcome: SyncOutcome, task: ActiveSyncTask?, healthServiceName: String): SyncScreenFeedback? =
        when (outcome) {
            is SyncOutcome.Failed -> SyncScreenFeedback(task, error = outcome.message ?: L10n.text(L10n.syncFailed))
            SyncOutcome.HealthUnavailable -> SyncScreenFeedback(task, error = L10n.textFmt(L10n.syncHealthUnavailable, healthServiceName))
            SyncOutcome.NotLoggedIn -> SyncScreenFeedback(task, error = L10n.text(L10n.syncNotLoggedIn))
            SyncOutcome.Skipped -> SyncScreenFeedback(task, message = L10n.text(L10n.syncNothingToSync))
            SyncOutcome.Success -> SyncScreenFeedback(task, message = L10n.text(L10n.syncAllMetricsSynced))
            is SyncOutcome.PartialSuccess -> SyncScreenFeedback(task, message = outcome.summary, warning = true)
            else -> null
        }

    private data class LocalSyncState(
        val healthAvailable: Boolean = true,
        val availabilityHint: String? = null,
        val feedback: SyncScreenFeedback? = null,
        val healthServiceName: String = "",
        val readinessChecked: Boolean = false,
    )
}
