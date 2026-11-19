package com.bettermifitness.sync.zepp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.i18n.L10n
import com.bettermifitness.sync.sync.ExclusiveSyncActionResult
import com.bettermifitness.sync.sync.SyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ZeppSettingsState(
    val connected: Boolean = false,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val selectedMetrics: Set<ZeppSyncMetric> = ZeppSyncMetric.defaultSelection,
)

class ZeppSettingsViewModel(
    private val service: ZeppSyncService,
    private val preferences: SyncPreferences,
    private val coordinator: SyncCoordinator,
) : ViewModel() {
    private val local = MutableStateFlow(ZeppSettingsState())
    val state: StateFlow<ZeppSettingsState> = combine(local, service.connected,
        combine(preferences.enabledMetrics, preferences.zeppMetrics) { keys, metrics -> keys to metrics },
        coordinator.isRunning) { local, connected, selection, running ->
        local.copy(connected = connected,
            enabled = SyncPreferences.ZEPP_CLOUD_IMPORT_KEY in selection.first,
            selectedMetrics = selection.second, busy = local.busy || running)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ZeppSettingsState())

    fun connect(account: String, password: String, country: String) = action {
        service.connect(account, password, country)
        local.update { it.copy(message = L10n.text(L10n.zeppConnected)) }
    }
    fun disconnect() = action { service.disconnect(); local.update { it.copy(message = null) } }
    fun enable(enabled: Boolean) = action {
        if (enabled && !state.value.connected) throw ZeppException(ZeppFailure.AUTH)
        require(!enabled || state.value.selectedMetrics.isNotEmpty())
        preferences.setMetricEnabled(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY, enabled)
    }
    fun selectMetric(metric: ZeppSyncMetric, enabled: Boolean) = action {
        preferences.setZeppMetricEnabled(metric, enabled)
    }

    private fun action(block: suspend () -> Unit) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val outcome = coordinator.runExclusiveUserAction(block)
                if (outcome !is ExclusiveSyncActionResult.Completed) local.update { it.copy(message = L10n.text(L10n.zeppBusy)) }
            } catch (e: CancellationException) { throw e }
            catch (e: ZeppException) { local.update { it.copy(message = zeppErrorText(e.failure)) } }
            catch (_: IllegalArgumentException) { local.update { it.copy(message = L10n.text(L10n.zeppInvalidLogin)) } }
            catch (_: Exception) { local.update { it.copy(message = L10n.text(L10n.zeppSyncFailed)) } }
            finally { local.update { it.copy(busy = false) } }
        }
    }
}

fun zeppErrorText(failure: ZeppFailure): String = L10n.text(when (failure) {
    ZeppFailure.AUTH -> L10n.zeppAuthError
    ZeppFailure.CAPTCHA -> L10n.zeppCaptchaError
    ZeppFailure.RATE_LIMIT -> L10n.zeppRateError
    ZeppFailure.NETWORK -> L10n.zeppNetworkError
    ZeppFailure.INCOMPLETE -> L10n.zeppIncompleteError
    ZeppFailure.RESPONSE, ZeppFailure.UNAVAILABLE -> L10n.zeppResponseError
})
