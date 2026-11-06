package com.bettermifitness.sync.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bettermifitness.sync.i18n.L10n
import com.bettermifitness.sync.AutoSyncPlatform
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.preferences.BodySyncLedger
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.preferences.TokenStore
import com.bettermifitness.sync.data.preferences.UserPrefsSnapshot
import com.bettermifitness.sync.health.HealthAvailability
import com.bettermifitness.sync.health.HealthPermissionRequester
import com.bettermifitness.sync.health.HealthReadiness
import com.bettermifitness.sync.sync.SyncOutcomeLabels
import com.bettermifitness.sync.ui.SyncSelection
import com.bettermifitness.sync.util.RelativeTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class SettingsUiState(
    /**
     * Real enabled set from DataStore once [prefsReady] is true.
     * Do **not** default to all-on — that flashes wrong toggles before prefs load.
     */
    val enabledMetrics: Set<String> = emptySet(),
    /** False until the first DataStore emission for metrics / range / auto-sync. */
    val prefsReady: Boolean = false,
    val rangeDays: Int = 7,
    val autoSync: Boolean = false,
    val lastBackgroundSyncLabel: String = L10n.text(L10n.homeNever),
    val lastBackgroundStatusTitle: String = L10n.text(L10n.outcomeNotSynced),
    val lastBackgroundDetail: String = L10n.text(L10n.outcomeIdleDetail),
    val lastBackgroundIsError: Boolean = false,
    val lastSyncLabel: String = L10n.text(L10n.homeNever),
    val lastSyncStatusTitle: String = L10n.text(L10n.outcomeNotSynced),
    val lastSyncDetail: String = L10n.text(L10n.outcomeIdleDetail),
    val lastSyncIsError: Boolean = false,
    val lastSyncIsWarning: Boolean = false,
    val canTestBgRefresh: Boolean = false,
    val bgTestStatus: String? = null,
    val bgTestRunning: Boolean = false,
    val showShortcutsHelp: Boolean = false,
    val showBackgroundRefreshDetails: Boolean = true,
    val healthServiceName: String = "",
    val healthStatusTitle: String = "",
    val healthStatusDetail: String = "",
    val healthNeedsAction: Boolean = false,
    val loggedOut: Boolean = false,
    val bodyRetryReady: Boolean = false,
    val inboundPermissionMessage: String? = null,
    val inboundPermissionNeedsAction: Boolean = false,
    val inboundTestMode: Boolean = false,
    val allowLegacySleepHrvEnrichment: Boolean = false,
    val allowOwnedSleepRevision: Boolean = false,
)

class SettingsViewModel(
    private val syncPreferences: SyncPreferences,
    private val healthAvailability: HealthAvailability,
    private val healthPermissions: HealthPermissionRequester,
    private val tokenStore: TokenStore,
    private val session: MiSessionManager,
    private val bodyLedger: BodySyncLedger,
    private val importLedger: HealthImportLedger,
) : ViewModel() {

    /** Hot snapshot: single DataStore subscription, shared with all screens. */
    private val prefsSnapshot: StateFlow<UserPrefsSnapshot> = syncPreferences.snapshot

    private val showShortcutsHelp = AutoSyncPlatform.supportsShortcutsHelp()

    private val localState = MutableStateFlow(
        LocalSettingsState(
            canTestBgRefresh = AutoSyncPlatform.supportsOpportunisticRefreshTest(),
            loggedOut = false,
        ),
    )
    private val healthState = MutableStateFlow(
        HealthReadiness(
            available = true,
            permissionsGranted = true,
            serviceName = healthAvailability.healthServiceName(),
            hint = null,
        ),
    )

    private val lastSyncBundle = combine(
        syncPreferences.lastSyncTime,
        syncPreferences.lastSyncStatus,
        syncPreferences.lastSyncMessage,
    ) { t, s, m -> Triple(t, s, m) }

    private val lastBgBundle = combine(
        syncPreferences.lastBackgroundSyncTime,
        syncPreferences.lastBackgroundSyncStatus,
        syncPreferences.lastBackgroundSyncMessage,
    ) { t, s, m -> Triple(t, s, m) }

    val uiState: StateFlow<SettingsUiState> = combine(
        prefsSnapshot,
        lastSyncBundle,
        lastBgBundle,
        localState,
        healthState,
    ) { config, lastSync, lastBg, local, health ->
        SettingsUiState(
            enabledMetrics = config.enabledMetrics,
            prefsReady = config.ready,
            rangeDays = config.syncRangeDays,
            autoSync = config.autoSync,
            inboundTestMode = config.inboundTestMode,
            allowLegacySleepHrvEnrichment = config.allowLegacySleepHrvEnrichment,
            allowOwnedSleepRevision = config.allowOwnedSleepRevision,
            lastBackgroundSyncLabel = RelativeTime.format(lastBg.first),
            lastBackgroundStatusTitle = SyncOutcomeLabels.title(lastBg.second),
            lastBackgroundDetail = SyncOutcomeLabels.detail(lastBg.second, lastBg.third),
            lastBackgroundIsError = SyncOutcomeLabels.isError(lastBg.second),
            lastSyncLabel = RelativeTime.format(lastSync.first),
            lastSyncStatusTitle = SyncOutcomeLabels.title(lastSync.second),
            lastSyncDetail = SyncOutcomeLabels.detail(lastSync.second, lastSync.third),
            lastSyncIsError = SyncOutcomeLabels.isError(lastSync.second),
            lastSyncIsWarning = SyncOutcomeLabels.isWarning(lastSync.second),
            canTestBgRefresh = local.canTestBgRefresh,
            bgTestStatus = local.bgTestStatus,
            bgTestRunning = local.bgTestRunning,
            showShortcutsHelp = showShortcutsHelp,
            showBackgroundRefreshDetails = true,
            healthServiceName = health.serviceName,
            healthStatusTitle = health.statusTitle,
            healthStatusDetail = health.statusDetail,
            healthNeedsAction = !health.isReady,
            loggedOut = local.loggedOut,
            bodyRetryReady = local.bodyRetryReady,
            inboundPermissionMessage = local.inboundPermissionMessage,
            inboundPermissionNeedsAction = local.inboundPermissionNeedsAction,
        )
    }.stateIn(
        scope = viewModelScope,
        // Eager so prefs load as soon as Settings opens (avoids all-on placeholder flash).
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(
            prefsReady = false,
            canTestBgRefresh = localState.value.canTestBgRefresh,
            showShortcutsHelp = showShortcutsHelp,
            healthServiceName = healthAvailability.healthServiceName(),
        ),
    )

    init {
        refreshHealth()
    }

    fun refreshHealth() {
        viewModelScope.launch {
            try {
                healthState.value = healthAvailability.readiness()
            } catch (_: Exception) {
                healthState.value = HealthReadiness(
                    available = false,
                    permissionsGranted = false,
                    serviceName = healthAvailability.healthServiceName(),
                    hint = L10n.text(L10n.healthStatusCheckFailed),
                )
            }
        }
    }

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
            refreshHealth()
        }
    }

    fun setMetricEnabled(key: String, enabled: Boolean) {
        viewModelScope.launch {
            syncPreferences.setMetricEnabled(key, enabled)
            if (key in SyncPreferences.INBOUND_METRIC_KEYS) {
                localState.update { it.copy(inboundPermissionMessage = null, inboundPermissionNeedsAction = false) }
            }
        }
    }

    fun openInboundPermissions() {
        viewModelScope.launch {
            requestSelectedInboundPermissions()
            refreshHealth()
        }
    }

    private suspend fun requestSelectedInboundPermissions() {
        val snapshot = prefsSnapshot.value
        val requested = SyncSelection.inboundMetrics(snapshot.enabledMetrics)
        if (requested.isEmpty()) return
        try {
            val granted = healthPermissions.requestReadPermissions(requested, includeBackground = snapshot.autoSync, includeHistory = snapshot.syncRangeDays > 27)
            val missing = requested - granted
            localState.update {
                it.copy(
                    inboundPermissionMessage = if (missing.isEmpty()) {
                        L10n.text(L10n.settingsInboundPermissionsReady)
                    } else {
                        L10n.textFmt(L10n.settingsInboundPermissionsMissing, missing.joinToString(", ") { metric -> L10n.metric(metric.metricKey) })
                    },
                    inboundPermissionNeedsAction = missing.isNotEmpty(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            localState.update {
                it.copy(
                    inboundPermissionMessage = L10n.text(L10n.settingsInboundPermissionsFailed),
                    inboundPermissionNeedsAction = true,
                )
            }
        }
    }

    fun retryBodyImports() {
        viewModelScope.launch {
            bodyLedger.resetPendingImports()
            // Other health metrics retain their uncertain-write journal. Their
            // explicit single-row recovery action records a durable retry budget.
            localState.update { it.copy(bodyRetryReady = true) }
        }
    }

    fun setSyncRangeDays(days: Int) {
        viewModelScope.launch {
            syncPreferences.setSyncRangeDays(days)
        }
    }

    fun setInboundTestMode(enabled: Boolean) {
        viewModelScope.launch { syncPreferences.setInboundTestMode(enabled) }
    }

    fun setAllowLegacySleepHrvEnrichment(enabled: Boolean) {
        viewModelScope.launch { syncPreferences.setAllowLegacySleepHrvEnrichment(enabled) }
    }

    fun setAllowOwnedSleepRevision(enabled: Boolean) {
        viewModelScope.launch { syncPreferences.setAllowOwnedSleepRevision(enabled) }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            syncPreferences.setAutoSync(enabled)
            if (enabled) {
                AutoSyncPlatform.scheduleBackgroundRefresh()
            } else {
                AutoSyncPlatform.cancelBackgroundRefresh()
                // Staying logged in must not depend on the data-sync toggle.
                AutoSyncPlatform.scheduleSessionRefresh()
            }
        }
    }

    fun runBackgroundRefreshTest() {
        if (localState.value.bgTestRunning) return
        if (!uiState.value.autoSync) return

        localState.update {
            it.copy(bgTestRunning = true, bgTestStatus = L10n.text(L10n.settingsRunningRefresh))
        }
        AutoSyncPlatform.runOpportunisticRefreshTest { status ->
            viewModelScope.launch {
                localState.update {
                    it.copy(
                        bgTestRunning = false,
                        bgTestStatus = mapBgTestStatus(status),
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            tokenStore.clear()
            session.clear()
            localState.update { it.copy(loggedOut = true) }
        }
    }

    fun consumeLoggedOut() {
        localState.update { it.copy(loggedOut = false) }
    }

    private fun mapBgTestStatus(status: String): String = when (status) {
        "success" -> L10n.text(L10n.settingsTestOk)
        "completed_with_skips" -> L10n.text(L10n.settingsTestOk)
        "partial_success" -> L10n.text(L10n.settingsPartialOk)
        "skipped" -> L10n.text(L10n.settingsSkipped)
        "not_logged_in" -> L10n.text(L10n.settingsNotSignedIn)
        "cancelled" -> L10n.text(L10n.settingsCancelled)
        else -> L10n.textFmt(L10n.settingsFailedStatus, status)
    }

    private data class LocalSettingsState(
        val canTestBgRefresh: Boolean = false,
        val bgTestStatus: String? = null,
        val bgTestRunning: Boolean = false,
        val loggedOut: Boolean = false,
        val bodyRetryReady: Boolean = false,
        val inboundPermissionMessage: String? = null,
        val inboundPermissionNeedsAction: Boolean = false,
    )
}
