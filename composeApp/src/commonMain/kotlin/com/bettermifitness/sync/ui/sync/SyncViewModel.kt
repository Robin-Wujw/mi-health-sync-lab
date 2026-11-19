package com.bettermifitness.sync.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.repository.HealthRepository
import com.bettermifitness.sync.data.repository.SyncProgress
import com.bettermifitness.sync.data.repository.SyncState
import com.bettermifitness.sync.health.HealthAvailability
import com.bettermifitness.sync.health.HealthPermissionRequester
import com.bettermifitness.sync.i18n.L10n
import com.bettermifitness.sync.sync.SyncCoordinator
import com.bettermifitness.sync.sync.SyncOutcome
import com.bettermifitness.sync.ui.SyncMetric
import com.bettermifitness.sync.ui.SyncSelection
import com.bettermifitness.sync.zepp.ZeppSyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncUiState(
    val requiresSignIn: Boolean = false,
    val isSyncing: Boolean = false,
    val healthAvailable: Boolean = true,
    val availabilityHint: String? = null,
    val permissionError: String? = null,
    /** Non-fatal banner after a run (success / partial). */
    val outcomeMessage: String? = null,
    val outcomeIsWarning: Boolean = false,
    val healthServiceName: String = "",
    val rangeDays: Int = 7,
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
}

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

    val uiState: StateFlow<SyncUiState> = combine(
        local,
        repository.syncProgress,
        syncPreferences.snapshot,
        syncCoordinator.isRunning,
        combine(
            syncCoordinator.lastOutcome,
            combine(session.reauthenticationRequired, zeppSync.connected) { requiresSignIn, zeppConnected ->
                requiresSignIn to zeppConnected
            },
        ) { lastOutcome, auth ->
            lastOutcome to auth
        },
    ) { local, progress, snap, running, outcomeAndAuth ->
        val lastOutcome = outcomeAndAuth.first
        val auth = outcomeAndAuth.second
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
            healthAvailable = local.healthAvailable || !SyncSelection.requiresHealthConnect(enabled),
            availabilityHint = local.availabilityHint,
            permissionError = local.permissionError ?: when (lastOutcome) {
                is SyncOutcome.Failed -> lastOutcome.message ?: L10n.text(L10n.syncFailed)
                SyncOutcome.HealthUnavailable -> L10n.textFmt(L10n.syncHealthUnavailable, local.healthServiceName)
                SyncOutcome.NotLoggedIn -> L10n.text(L10n.syncNotLoggedIn)
                else -> null
            },
            outcomeMessage = local.outcomeMessage,
            outcomeIsWarning = local.outcomeIsWarning,
            healthServiceName = local.healthServiceName,
            rangeDays = snap.syncRangeDays,
            enabledMetrics = enabled,
            prefsReady = snap.ready,
            progress = progress,
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
            checkHealthReadiness()
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

    fun openHealthService() {
        viewModelScope.launch {
            try {
                healthPermissions.requestPermissions()
            } catch (_: Exception) {
                // Denied / failed — open Settings or Health Connect below.
            }
            requestSelectedInboundPermissions()
            if (!healthAvailability.hasWritePermissions()) {
                healthAvailability.openHealthService()
            }
            checkHealthReadiness()
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

    private suspend fun requestSelectedInboundPermissions() {
        val snapshot = syncPreferences.snapshot.value
        val requested = SyncSelection.inboundMetrics(snapshot.enabledMetrics)
        if (requested.isEmpty()) return
        try {
            val granted = healthPermissions.requestReadPermissions(
                requested, includeBackground = true, includeHistory = snapshot.syncRangeDays > 27)
            val missing = requested - granted
            local.update {
                it.copy(
                    permissionError = if (missing.isEmpty()) null else L10n.textFmt(
                        L10n.settingsInboundPermissionsMissing,
                        missing.joinToString(", ") { metric -> L10n.metric(metric.metricKey) },
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            local.update { it.copy(permissionError = L10n.text(L10n.settingsInboundPermissionsFailed)) }
        }
    }

    /** Health check only — never auto-starts sync on screen enter (spam-safe). */
    private suspend fun checkHealthReadiness() {
        try {
            val available = healthAvailability.isAvailable()
            val hint = healthAvailability.availabilityHint()
            val perms = if (available) {
                try {
                    healthAvailability.hasWritePermissions()
                } catch (_: Exception) {
                    true
                }
            } else {
                false
            }
            local.update {
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
        } catch (_: Exception) {
            local.update {
                it.copy(
                    healthAvailable = false,
                    availabilityHint = L10n.text(L10n.healthNotAvailable),
                    readinessChecked = true,
                )
            }
        }
    }

    private suspend fun runSync() {
        local.update {
            it.copy(
                permissionError = null,
                outcomeMessage = null,
                outcomeIsWarning = false,
            )
        }
        try {
            when (
                val outcome = com.bettermifitness.sync.sync.runUserSync(syncCoordinator)
            ) {
                SyncOutcome.AlreadyRunning -> {
                    // Another caller owns the run; UI already shows isSyncing via coordinator.
                }
                is SyncOutcome.Failed ->
                    local.update {
                        it.copy(
                            permissionError = outcome.message ?: L10n.text(L10n.syncFailed),
                            outcomeMessage = null,
                        )
                    }
                SyncOutcome.HealthUnavailable ->
                    local.update {
                        it.copy(
                            healthAvailable = false,
                            permissionError = L10n.textFmt(L10n.syncHealthUnavailable, it.healthServiceName),
                        )
                    }
                SyncOutcome.NotLoggedIn ->
                    local.update { it.copy(permissionError = L10n.text(L10n.syncNotLoggedIn)) }
                SyncOutcome.Skipped ->
                    local.update {
                        it.copy(outcomeMessage = L10n.text(L10n.syncNothingToSync))
                    }
                is SyncOutcome.NoData -> local.update {
                    it.copy(outcomeMessage = null, outcomeIsWarning = false)
                }
                is SyncOutcome.CompletedWithSkips -> local.update {
                    it.copy(outcomeMessage = null, outcomeIsWarning = false)
                }
                SyncOutcome.Success ->
                    local.update {
                        it.copy(
                            outcomeMessage = L10n.text(L10n.syncAllMetricsSynced),
                            outcomeIsWarning = false,
                        )
                    }
                is SyncOutcome.PartialSuccess ->
                    local.update {
                        it.copy(
                            outcomeMessage = outcome.summary,
                            outcomeIsWarning = true,
                        )
                    }
            }
        } finally {
            // Refresh Health Connect / HealthKit readiness after a permission prompt.
            try {
                val available = healthAvailability.isAvailable()
                val hint = healthAvailability.availabilityHint()
                val perms = healthAvailability.hasWritePermissions()
                local.update {
                    it.copy(
                        healthAvailable = available,
                        availabilityHint = when {
                            !available -> hint
                            !perms -> hint
                                ?: L10n.text(L10n.healthPermissionsIncomplete)
                            else -> null
                        },
                    )
                }
            } catch (_: Exception) {
                // Keep previous readiness.
            }
        }
    }

    private data class LocalSyncState(
        val healthAvailable: Boolean = true,
        val availabilityHint: String? = null,
        val permissionError: String? = null,
        val outcomeMessage: String? = null,
        val outcomeIsWarning: Boolean = false,
        val healthServiceName: String = "",
        val readinessChecked: Boolean = false,
    )
}
