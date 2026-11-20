package com.bettermifitness.sync.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettermifitness.sync.i18n.L10n
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.repository.SyncState
import com.bettermifitness.sync.data.repository.stateForKey
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.repository.copyableText
import com.bettermifitness.sync.data.repository.userFacingDetails
import com.bettermifitness.sync.platform.appVersionLabel
import com.bettermifitness.sync.platform.setPlainText
import com.bettermifitness.sync.sync.SyncOutcome
import com.bettermifitness.sync.sync.SyncOutcomeLabels
import com.bettermifitness.sync.sync.SyncTaskSummary
import com.bettermifitness.sync.sync.displayTaskTime
import com.bettermifitness.sync.sync.label
import com.bettermifitness.sync.theme.BrandSpacing
import com.bettermifitness.sync.ui.SyncMetric
import com.bettermifitness.sync.ui.SyncSelection
import com.bettermifitness.sync.ui.components.PrimaryButton
import com.bettermifitness.sync.ui.components.StickyCtaBar
import com.bettermifitness.sync.ui.icons.AppIcon
import com.bettermifitness.sync.ui.icons.AppIcons
import kotlinx.coroutines.launch

/**
 * Sync screen (parity with iOS SyncView): metric list + sticky Sync now.
 */
@Composable
fun SyncScreen(viewModel: SyncViewModel, onBack: () -> Unit, onSignIn: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val history by viewModel.history.collectAsState()
    SyncScreenContent(state, history, onBack, onSignIn,
        SyncScreenActions(viewModel::startSync, viewModel::stopSync, viewModel::replaceSync, viewModel::resumeQueuedSync,
            viewModel::openHealthService, viewModel::selectRange))
}

internal data class SyncScreenActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val replace: () -> Unit,
    val recover: () -> Unit,
    val openHealth: () -> Unit,
    val selectRange: (Int) -> Unit,
)

@Composable
internal fun SyncScreenContent(state: SyncUiState, history: SyncHistorySnapshot, onBack: () -> Unit, onSignIn: () -> Unit,
    actions: SyncScreenActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val layout = syncLayoutPolicy(maxWidth.value, maxHeight.value, fontScale)
        val stackedMetrics = layout.stackedMetrics
        val inlineActions = layout.inlineActions
        SyncScreenLayout(state, history, onBack, onSignIn, actions, stackedMetrics, inlineActions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncScreenLayout(state: SyncUiState, history: SyncHistorySnapshot, onBack: () -> Unit, onSignIn: () -> Unit,
    actions: SyncScreenActions, stackedMetrics: Boolean, inlineActions: Boolean) {
    val healthName = state.healthServiceName.ifBlank { L10n.string(L10n.healthFallback) }
    val metrics = state.visibleMetrics
    val outboundKeys = metrics.filterNot { it == SyncMetric.WEIGHT }.map { it.key }
    val inboundKeys = state.visibleInboundMetrics
    val selectedDaysLabel = if (state.rangeDays == 1) L10n.string(L10n.syncLastOneDay)
        else L10n.stringFmt(L10n.syncLastNDays, state.rangeDays)
    val activeDays = state.activeRangeDays
    val pendingDays = state.pendingRangeDays
    val recoverableDays = state.recoverableRangeDays
    var rangeMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(L10n.string(L10n.syncTitle), Modifier.semantics { heading() }, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        AppIcon(AppIcons.ArrowBack, contentDescription = L10n.string(L10n.back))
                    }
                },
            )
        },
        bottomBar = {
            if (!inlineActions) StickyCtaBar(modifier = Modifier.navigationBarsPadding()) {
                SyncActions(state, actions, onSignIn, healthName)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("sync-scroll")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BrandSpacing.ScreenHorizontal)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box {
                TextButton(onClick = { rangeMenuOpen = true }, enabled = state.prefsReady) {
                    Text(L10n.stringFmt(L10n.syncDraftRange, selectedDaysLabel))
                }
                DropdownMenu(expanded = rangeMenuOpen, onDismissRequest = { rangeMenuOpen = false }) {
                    listOf(1, 7, 14, 30).forEach { days ->
                        DropdownMenuItem(
                            text = {
                                Text(if (days == 1) L10n.string(L10n.syncLastOneDay) else L10n.stringFmt(L10n.syncLastNDays, days))
                            },
                            onClick = { rangeMenuOpen = false; actions.selectRange(days) },
                        )
                    }
                }
            }
            if (activeDays != null) {
                Text(
                    L10n.stringFmt(L10n.syncActiveRange, activeDays),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.activeTask?.let { task ->
                Text(task.source.label(), style = MaterialTheme.typography.labelLarge)
                Text(L10n.stringFmt(L10n.syncTaskWindow, displayTaskTime(task.selection.from.toString()),
                    displayTaskTime(task.selection.to.toString())), style = MaterialTheme.typography.bodySmall)
            }
            if (pendingDays != null) Text(L10n.stringFmt(L10n.syncQueuedRange, pendingDays))
            if (recoverableDays != null) CompactBanner(L10n.string(L10n.syncResumeReplacementDetail), isError = false)
            if (inlineActions) SyncActions(state, actions, onSignIn, healthName)

            if (inboundKeys.isNotEmpty()) {
                Text(
                    L10n.string(L10n.syncDirectionSummary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.requiresSignIn) CompactBanner(L10n.string(L10n.sessionExpired), isError = true)
            state.permissionError?.takeUnless { state.requiresSignIn }?.let { err ->
                CompactBanner(err, isError = true)
            }
            state.availabilityHint?.let { hint ->
                CompactBanner(hint, isError = false)
            }
            if (state.wasStopped) CompactBanner(L10n.string(L10n.syncStopped), isError = false)
            SyncAccessWarnings(state)
            if (state.outcomeIsWarning && !state.requiresSignIn) {
                state.outcomeMessage?.let { CompactBanner(it, isError = false) }
            }
            SyncTaskHistory(history)

            if (!state.prefsReady) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (!state.hasSelectedSyncTasks) {
                Text(
                    if (state.healthAvailable) {
                        L10n.string(L10n.syncNoMetrics)
                    } else {
                        L10n.string(L10n.syncPreparing)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                )
            } else {
                if (outboundKeys.isNotEmpty()) {
                    SyncMetricsGroup(
                        title = L10n.stringFmt(L10n.syncToHealthTitle, healthName),
                        metricKeys = outboundKeys,
                        stacked = stackedMetrics, stateFor = { state.progress.stateForKey(it) ?: SyncState.Idle },
                    )
                }
                if (SyncMetric.WEIGHT in metrics) {
                    SyncMetricsGroup(
                        title = L10n.string(L10n.syncWeightTitle),
                        metricKeys = listOf(SyncMetric.WEIGHT.key),
                        stacked = stackedMetrics, stateFor = { state.progress.stateForKey(it) ?: SyncState.Idle },
                    )
                }
                val healthConnectKeys = inboundKeys.filterNot { it in SyncPreferences.ZEPP_CLOUD_METRIC_KEYS }
                if (healthConnectKeys.isNotEmpty()) {
                    SyncMetricsGroup(
                        title = L10n.string(L10n.syncInboundTitle),
                        metricKeys = healthConnectKeys,
                        stacked = stackedMetrics, stateFor = { state.progress.stateForKey(it) ?: SyncState.Idle },
                    )
                }
                val zeppKeys = inboundKeys.filter { it in SyncPreferences.ZEPP_CLOUD_METRIC_KEYS }
                if (zeppKeys.isNotEmpty()) SyncMetricsGroup(
                    title = L10n.string(L10n.zeppTitle), metricKeys = zeppKeys,
                    stacked = stackedMetrics, stateFor = { state.progress.stateForKey(it) ?: SyncState.Idle },
                )
            }
        }
    }
}

@Composable
private fun SyncTaskHistory(snapshot: SyncHistorySnapshot) {
    val history = snapshot.tasks
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(L10n.string(if (expanded) L10n.syncHistoryHide else L10n.syncHistoryTitle))
    }
    if (!expanded) return
    snapshot.automaticSkip?.let { Text(it.label(), style = MaterialTheme.typography.bodySmall) }
    if (history.isEmpty()) Text(L10n.string(L10n.syncNoTaskHistory), style = MaterialTheme.typography.bodySmall)
    history.firstOrNull()?.let { Text(L10n.stringFmt(L10n.syncHistoryLastAttempt, displayTaskTime(it.finishedAt))) }
    history.firstOrNull { it.status == SyncOutcome.STATUS_SUCCESS || it.status == SyncOutcome.STATUS_COMPLETED_WITH_SKIPS }
        ?.let { Text(L10n.stringFmt(L10n.syncHistoryLastSuccess, displayTaskTime(it.finishedAt))) }
    history.firstOrNull { it.wasSkipped }?.let { Text(L10n.stringFmt(L10n.syncHistoryLastSkip, displayTaskTime(it.finishedAt))) }
    history.forEach { TaskHistoryRow(it) }
}

@Composable
private fun TaskHistoryRow(summary: SyncTaskSummary) {
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("${summary.source.label()} · ${SyncOutcomeLabels.title(summary.status)}",
            style = MaterialTheme.typography.labelLarge)
        Text(displayTaskTime(summary.finishedAt), style = MaterialTheme.typography.bodySmall)
        if (summary.from != null && summary.until != null) {
            Text(L10n.stringFmt(L10n.syncTaskWindow, displayTaskTime(summary.from), displayTaskTime(summary.until)),
                style = MaterialTheme.typography.bodySmall)
        }
        if (summary.succeeded != null && summary.failed != null && summary.noData != null) {
            Text(L10n.stringFmt(L10n.syncHistoryCounts, summary.succeeded, summary.failed, summary.noData),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SyncAccessWarnings(state: SyncUiState) {
    if (state.requiresSignIn || state.accessBlocks.isEmpty()) return
    val lines = state.accessBlocks.map { (key, reason) ->
        val direction = if (key in SyncPreferences.INBOUND_METRIC_KEYS ||
            key == "weight" && reason != com.bettermifitness.sync.health.HealthAccessBlock.WRITE_PERMISSION) {
            L10n.string(L10n.syncInboundTitle)
        } else L10n.string(L10n.syncToHealthTitle)
        "$direction · ${L10n.metric(SyncSelection.sourceMetricKey(key))}: ${reason.message()}"
    }
    CompactBanner(lines.joinToString("\n"), isError = true)
}

@Composable
private fun SyncActions(state: SyncUiState, actions: SyncScreenActions, onSignIn: () -> Unit, healthName: String) {
    val pending = state.pendingRangeDays != null
    val recoveryDays = state.recoverableRangeDays
    val hasQueued = pending || recoveryDays != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = when {
                    state.isStopping -> L10n.string(L10n.syncStopping)
                    state.requiresSignIn -> L10n.string(L10n.sessionSignIn)
                    state.canReplaceRange -> L10n.stringFmt(L10n.syncReplaceRange, state.rangeDays)
                    pending -> L10n.string(L10n.syncStopping)
                    recoveryDays != null -> L10n.stringFmt(L10n.syncResumeReplacement, recoveryDays)
                    state.isSyncing -> L10n.string(L10n.homeSyncing)
                    else -> L10n.string(L10n.syncNow)
                },
                onClick = when {
                    state.requiresSignIn -> onSignIn
                    state.canReplaceRange -> actions.replace
                    recoveryDays != null -> actions.recover
                    else -> actions.start
                },
                modifier = Modifier.testTag("sync-primary-action"),
                enabled = !state.isStopping && when {
                    state.canReplaceRange -> !state.requiresSignIn && state.healthAvailable
                    pending || state.isSyncing -> false
                    state.requiresSignIn || recoveryDays != null -> true
                    else -> state.healthAvailable && state.prefsReady && state.hasSelectedSyncTasks
                },
                loading = state.isStopping || (!state.canReplaceRange && (pending || !state.requiresSignIn && state.isSyncing)),
                icon = AppIcons.Sync,
            )
            if (state.isSyncing || state.isStopping || hasQueued) {
                TextButton(onClick = actions.stop, enabled = !state.isStopping,
                    modifier = Modifier.fillMaxWidth().testTag("sync-stop-action")) {
                    Text(L10n.string(if (hasQueued) L10n.syncStopAndCancelReplacement else L10n.syncStop))
                }
            }
            if (!state.healthAvailable) {
                TextButton(onClick = actions.openHealth, modifier = Modifier.fillMaxWidth()) {
                    Text(L10n.textFmt(L10n.syncOpenHealth, healthName))
                }
            }
    }
}

@Composable
private fun SyncMetricsGroup(
    title: String,
    metricKeys: List<String>,
    stacked: Boolean,
    stateFor: (String) -> SyncState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                metricKeys.forEachIndexed { index, key ->
                    val metricState = stateFor(key)
                    val isImport = key in SyncPreferences.INBOUND_METRIC_KEYS || key in SyncPreferences.ZEPP_CLOUD_METRIC_KEYS
                    MetricRow(metricKey = key, syncState = metricState, stacked = stacked)
                    val detail = when (metricState) {
                        is SyncState.Error -> metricState.message.takeUnless { metricState.authenticationRequired }
                        is SyncState.NoData -> null
                        is SyncState.Success -> {
                            val summary = when {
                                isImport && metricState.count > 0 -> L10n.stringFmt(L10n.syncImportRecords, metricState.count)
                                isImport -> L10n.string(L10n.syncImportNoNewRecords)
                                metricState.count > 0 -> L10n.stringFmt(L10n.syncRecords, metricState.count)
                                else -> L10n.string(L10n.syncAlreadyMatched)
                            }
                            summary
                        }
                        else -> null
                    }
                    if (detail != null) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                metricState is SyncState.Error &&
                                    (metricState.verifiedCount > 0 || metricState.diagnostic?.hasVerifiedRows == true) ->
                                    MaterialTheme.colorScheme.tertiary
                                metricState is SyncState.Error -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(start = if (stacked) 14.dp else 48.dp, end = 14.dp, bottom = 12.dp),
                        )
                    }
                    val diagnostic = when (metricState) {
                        is SyncState.Error -> metricState.diagnostic
                        is SyncState.Success -> metricState.diagnostic
                        is SyncState.NoData -> metricState.diagnostic
                        else -> null
                    }
                    val authError = metricState is SyncState.Error && metricState.authenticationRequired
                    if (diagnostic != null && !authError && (metricState is SyncState.Error || diagnostic.testMode))
                        ImportDiagnosticPanel(key, diagnostic, stacked)
                    if (index < metricKeys.lastIndex) HorizontalDivider(Modifier.padding(start = 48.dp))
                }
            }
        }
    }
}

@Composable
private fun ImportDiagnosticPanel(metricKey: String, diagnostic: MiHealthImportDiagnostic, stacked: Boolean) {
    var expanded by remember(diagnostic) { mutableStateOf(false) }
    var copied by remember(diagnostic) { mutableStateOf(false) }
    var copyFailed by remember(diagnostic) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(start = if (stacked) 14.dp else 42.dp, end = 14.dp, bottom = 10.dp)) {
        Column {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("sync-details-$metricKey")) {
                Text(L10n.string(if (expanded) L10n.syncDiagnosticsHide else L10n.syncDiagnosticsShow))
            }
            if (expanded) TextButton(modifier = Modifier.testTag("sync-copy-$metricKey"), onClick = {
                scope.launch {
                    try {
                        clipboard.setPlainText(diagnostic.copyableText(appVersionLabel()))
                        copied = true
                        copyFailed = false
                    } catch (_: Exception) {
                        copyFailed = true
                    }
                }
            }) { Text(L10n.string(if (copied) L10n.syncDiagnosticsCopied else L10n.syncDiagnosticsCopy)) }
        }
        if (expanded) {
            Text(diagnostic.userFacingDetails(), modifier = Modifier.testTag("sync-detail-body-$metricKey"),
                style = MaterialTheme.typography.bodySmall)
            Text(L10n.string(L10n.syncDiagnosticsPrivacy), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        if (copyFailed) Text(L10n.string(L10n.syncDiagnosticsCopyFailed), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CompactBanner(text: String, isError: Boolean) {
    val color = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun MetricRow(metricKey: String, syncState: SyncState, stacked: Boolean) {
    val isImport = metricKey in SyncPreferences.INBOUND_METRIC_KEYS || metricKey in SyncPreferences.ZEPP_CLOUD_METRIC_KEYS
    val (label, color) = when (syncState) {
        is SyncState.InProgress -> L10n.string(L10n.syncStatusSyncing) to MaterialTheme.colorScheme.primary
        is SyncState.Success -> L10n.string(when {
            syncState.diagnostic?.testMode == true -> L10n.syncStatusTestVerified
            isImport -> L10n.syncStatusCloudVerified
            else -> L10n.syncStatusDone
        }) to MaterialTheme.colorScheme.primary
        is SyncState.NoData -> L10n.string(L10n.syncStatusNoData) to MaterialTheme.colorScheme.onSurfaceVariant
        is SyncState.Error -> if (syncState.authenticationRequired) {
            L10n.string(L10n.syncWaitingForSignIn) to MaterialTheme.colorScheme.onSurfaceVariant
        } else if (isImport && (syncState.verifiedCount > 0 || syncState.diagnostic?.hasVerifiedRows == true)) {
            L10n.string(L10n.syncStatusPartialVerified) to MaterialTheme.colorScheme.tertiary
        } else L10n.string(L10n.syncStatusFailed) to MaterialTheme.colorScheme.error
        is SyncState.Idle -> L10n.string(L10n.syncStatusWaiting) to MaterialTheme.colorScheme.onSurfaceVariant
    }

    val modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("sync-metric-$metricKey")
        .semantics(mergeDescendants = true) { }
        .padding(horizontal = 14.dp, vertical = 8.dp)
    if (stacked) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricName(metricKey)
            MetricStatus(syncState, label, color)
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricName(metricKey, Modifier.weight(1f))
            MetricStatus(syncState, label, color, Modifier.weight(0.8f), alignEnd = true)
        }
    }
}

@Composable
private fun MetricName(metricKey: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppIcon(AppIcons.forMetric(SyncSelection.sourceMetricKey(metricKey)), null, Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary)
        Text(L10n.metric(metricKey), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MetricStatus(state: SyncState, label: String, color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier, alignEnd: Boolean = false) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, if (alignEnd) Alignment.End else Alignment.Start)) {
            when (state) {
                is SyncState.InProgress ->
                    CircularProgressIndicator(Modifier.size(14.dp).clearAndSetSemantics { }, strokeWidth = 2.dp)
                is SyncState.Success ->
                    AppIcon(
                        AppIcons.CheckCircle,
                        null,
                        Modifier.size(16.dp),
                        tint = color,
                    )
                is SyncState.Error ->
                    AppIcon(
                        AppIcons.ErrorOutline,
                        null,
                        Modifier.size(16.dp),
                        tint = color,
                    )
                is SyncState.Idle, is SyncState.NoData -> { }
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = color,
            )
        }
}
