package com.bettermifitness.sync

import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.preferences.TokenStore
import com.bettermifitness.sync.data.repository.SyncState
import com.bettermifitness.sync.i18n.L10n
import com.bettermifitness.sync.platform.FlowWatcher
import com.bettermifitness.sync.platform.watchStateFlow
import com.bettermifitness.sync.sync.SyncCoordinator
import com.bettermifitness.sync.ui.SyncMetric
import com.bettermifitness.sync.ui.home.HomeUiState
import com.bettermifitness.sync.ui.home.HomeViewModel
import com.bettermifitness.sync.ui.login.LoginUiState
import com.bettermifitness.sync.ui.login.LoginViewModel
import com.bettermifitness.sync.ui.settings.SettingsUiState
import com.bettermifitness.sync.ui.settings.SettingsViewModel
import com.bettermifitness.sync.ui.sync.SyncUiState
import com.bettermifitness.sync.ui.sync.SyncViewModel
import com.bettermifitness.sync.sync.SyncOutcome
import com.bettermifitness.sync.sync.SyncOutcomeLabels
import com.bettermifitness.sync.sync.displayTaskTime
import com.bettermifitness.sync.sync.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

/**
 * Single entry point for SwiftUI to create ViewModels and restore session.
 * Call [doInitKoin] before using this object.
 */
object IosAppBridge : KoinComponent {

    private val sessionManager: MiSessionManager by inject()
    private val tokenStore: TokenStore by inject()
    private val syncCoordinator: SyncCoordinator by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun createLoginViewModel(): LoginViewModel = get()
    fun createHomeViewModel(): HomeViewModel = get()
    fun createSettingsViewModel(): SettingsViewModel = get()
    fun createSyncViewModel(): SyncViewModel = get()

    fun isSessionActive(): Boolean = sessionManager.isActive

    fun isSyncRunning(): Boolean = syncCoordinator.isRunning.value

    fun logout() {
        scope.launch {
            sessionManager.signOut()
            syncCoordinator.clearAfterSignOut()
            tokenStore.sync.clearSyncHistory()
        }
    }

    /**
     * Starts a user sync if idle (single-flight). Safe from Home before opening Sync.
     */
    fun startUserSyncIfIdle() {
        if (syncCoordinator.isRunning.value) return
        scope.launch {
            syncCoordinator.run(
                requestHealthPermissions = true,
                resetProgress = true,
                userInitiated = true,
            )
        }
    }

    /**
     * Restores credentials from disk if needed.
     * [onResult] is invoked on main with "loading" already done → "logged_in" | "logged_out".
     */
    fun restoreAuth(onResult: (String) -> Unit) {
        if (sessionManager.isActive) {
            onResult("logged_in")
            return
        }
        scope.launch {
            if (sessionManager.ensureActive()) {
                onResult("logged_in")
            } else {
                onResult("logged_out")
            }
        }
    }

    fun watchLogin(vm: LoginViewModel, onEach: (LoginUiState) -> Unit): FlowWatcher =
        watchStateFlow(vm.uiState, onEach)

    fun watchHome(vm: HomeViewModel, onEach: (HomeUiState) -> Unit): FlowWatcher =
        watchStateFlow(vm.uiState, onEach)

    fun watchSettings(vm: SettingsViewModel, onEach: (SettingsUiState) -> Unit): FlowWatcher =
        watchStateFlow(vm.uiState, onEach)

    fun watchSync(vm: SyncViewModel, onEach: (SyncUiState) -> Unit): FlowWatcher =
        watchStateFlow(vm.uiState, onEach)

    fun activeSyncDescription(state: SyncUiState): String? = state.activeTask?.let { task ->
        task.source.label() + "\n" + L10n.textFmt(L10n.syncTaskWindow,
            displayTaskTime(task.selection.from.toString()), displayTaskTime(task.selection.to.toString()))
    }

    fun watchSyncHistory(vm: SyncViewModel, onEach: (List<String>) -> Unit): FlowWatcher = watchStateFlow(vm.history) { snapshot ->
        val history = snapshot.tasks
        onEach(buildList {
            snapshot.automaticSkip?.let { add(it.label()) }
            history.firstOrNull()?.let { add(L10n.textFmt(L10n.syncHistoryLastAttempt, displayTaskTime(it.finishedAt))) }
            history.firstOrNull { it.status == SyncOutcome.STATUS_SUCCESS || it.status == SyncOutcome.STATUS_COMPLETED_WITH_SKIPS }
                ?.let { add(L10n.textFmt(L10n.syncHistoryLastSuccess, displayTaskTime(it.finishedAt))) }
            history.firstOrNull { it.wasSkipped }?.let { add(L10n.textFmt(L10n.syncHistoryLastSkip, displayTaskTime(it.finishedAt))) }
            history.forEach { summary ->
                add("${summary.source.label()} · ${SyncOutcomeLabels.title(summary.status)} · ${displayTaskTime(summary.finishedAt)}")
            }
        })
    }

    /** All metrics for Settings toggles (key + label). */
    fun allMetricKeys(): List<String> = SyncMetric.entries.map { it.key }

    fun allMetricLabels(): List<String> = SyncMetric.entries.map { L10n.metric(it.key) }

    fun metricStatusKind(vm: SyncViewModel, key: String): String =
        when (val s = vm.stateFor(vm.uiState.value.progress, key)) {
            is SyncState.Idle -> "idle"
            is SyncState.InProgress -> "progress"
            is SyncState.Success -> "success"
            is SyncState.NoData -> "no_data"
            is SyncState.Error -> "error"
        }

    fun metricStatusText(vm: SyncViewModel, key: String): String =
        when (val s = vm.stateFor(vm.uiState.value.progress, key)) {
            is SyncState.Idle -> L10n.text(L10n.syncStatusWaiting)
            is SyncState.InProgress -> L10n.text(L10n.syncStatusSyncing)
            is SyncState.Success -> if (s.count > 0) L10n.textFmt(L10n.syncRecords, s.count) else L10n.text(L10n.syncStatusDone)
            is SyncState.NoData -> s.message
            is SyncState.Error -> s.message
        }

    fun profileName(state: HomeUiState): String =
        state.profile?.result?.name?.takeIf { it.isNotBlank() } ?: L10n.text(L10n.homeAccount)

    fun visibleMetricKeys(state: SyncUiState): List<String> =
        state.visibleMetrics.map { it.key }

    fun visibleMetricLabels(state: SyncUiState): List<String> = state.visibleMetrics.map { L10n.metric(it.key) }
}
