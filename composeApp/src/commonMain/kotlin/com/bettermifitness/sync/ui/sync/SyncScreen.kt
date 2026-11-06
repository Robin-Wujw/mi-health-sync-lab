package com.bettermifitness.sync.ui.sync

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettermifitness.sync.i18n.L10n
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.repository.SyncState
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.repository.copyableText
import com.bettermifitness.sync.data.repository.userFacingDetails
import com.bettermifitness.sync.platform.appVersionLabel
import com.bettermifitness.sync.platform.setPlainText
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: SyncViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    val healthName = state.healthServiceName.ifBlank { L10n.string(L10n.healthFallback) }
    val metrics = state.visibleMetrics
    val outboundKeys = metrics.filterNot { it == SyncMetric.WEIGHT }.map { it.key }
    val inboundKeys = state.visibleInboundMetrics
    val daysLabel = if (state.rangeDays == 1) L10n.string(L10n.syncLastOneDay) else L10n.stringFmt(L10n.syncLastNDays, state.rangeDays)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(L10n.string(L10n.syncTitle), fontWeight = FontWeight.SemiBold) },
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
            StickyCtaBar(modifier = Modifier.navigationBarsPadding()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = if (state.isSyncing && !state.pendingActionRunning) L10n.string(L10n.homeSyncing) else L10n.string(L10n.syncNow),
                        onClick = viewModel::startSync,
                        enabled = state.healthAvailable && state.prefsReady && state.hasSelectedSyncTasks &&
                            !state.isSyncing && !state.pendingActionRunning,
                        loading = state.isSyncing && !state.pendingActionRunning,
                        icon = AppIcons.Sync,
                    )
                    if (!state.healthAvailable) {
                        TextButton(
                            onClick = viewModel::openHealthService,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(L10n.textFmt(L10n.syncOpenHealth, healthName))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BrandSpacing.ScreenHorizontal)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                daysLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (inboundKeys.isNotEmpty()) {
                Text(
                    L10n.string(L10n.syncDirectionSummary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.inboundTestMode) CompactBanner(L10n.string(L10n.settingsInboundTestModeDetail), isError = false)
            }
            state.permissionError?.let { err ->
                CompactBanner(err, isError = true)
            }
            state.availabilityHint?.let { hint ->
                CompactBanner(hint, isError = false)
            }
            if (state.outcomeIsWarning) {
                state.outcomeMessage?.let { CompactBanner(it, isError = false) }
            }

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
                        stateFor = { viewModel.stateFor(state.progress, it) },
                    )
                }
                if (SyncMetric.WEIGHT in metrics) {
                    SyncMetricsGroup(
                        title = L10n.string(L10n.syncWeightTitle),
                        metricKeys = listOf(SyncMetric.WEIGHT.key),
                        stateFor = { viewModel.stateFor(state.progress, it) },
                    )
                }
                if (inboundKeys.isNotEmpty()) {
                    SyncMetricsGroup(
                        title = L10n.string(L10n.syncInboundTitle),
                        metricKeys = inboundKeys,
                        stateFor = { viewModel.stateFor(state.progress, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncMetricsGroup(
    title: String,
    metricKeys: List<String>,
    stateFor: (String) -> SyncState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
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
                    val isImport = key in SyncPreferences.INBOUND_METRIC_KEYS
                    MetricRow(metricKey = key, syncState = metricState)
                    val detail = when (metricState) {
                        is SyncState.Error -> metricState.message
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
                                metricState is SyncState.Error && metricState.diagnostic?.hasVerifiedRows == true -> MaterialTheme.colorScheme.tertiary
                                metricState is SyncState.Error -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(start = 48.dp, end = 14.dp, bottom = 12.dp),
                        )
                    }
                    val diagnostic = when (metricState) {
                        is SyncState.Error -> metricState.diagnostic
                        is SyncState.Success -> metricState.diagnostic
                        is SyncState.NoData -> metricState.diagnostic
                        else -> null
                    }
                    if (diagnostic != null && (metricState is SyncState.Error || diagnostic.testMode))
                        ImportDiagnosticPanel(diagnostic)
                    if (index < metricKeys.lastIndex) HorizontalDivider(Modifier.padding(start = 48.dp))
                }
            }
        }
    }
}

@Composable
private fun ImportDiagnosticPanel(diagnostic: MiHealthImportDiagnostic) {
    var expanded by remember(diagnostic) { mutableStateOf(false) }
    var copied by remember(diagnostic) { mutableStateOf(false) }
    var copyFailed by remember(diagnostic) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(start = 42.dp, end = 14.dp, bottom = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(L10n.string(if (expanded) L10n.syncDiagnosticsHide else L10n.syncDiagnosticsShow))
            }
            if (expanded) TextButton(onClick = {
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
            Text(diagnostic.userFacingDetails(), style = MaterialTheme.typography.bodySmall)
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
private fun MetricRow(metricKey: String, syncState: SyncState) {
    val isImport = metricKey in SyncPreferences.INBOUND_METRIC_KEYS
    val (label, color) = when (syncState) {
        is SyncState.InProgress -> L10n.string(L10n.syncStatusSyncing) to MaterialTheme.colorScheme.primary
        is SyncState.Success -> L10n.string(when {
            syncState.diagnostic?.testMode == true -> L10n.syncStatusTestVerified
            isImport -> L10n.syncStatusCloudVerified
            else -> L10n.syncStatusDone
        }) to MaterialTheme.colorScheme.primary
        is SyncState.NoData -> L10n.string(L10n.syncStatusNoData) to MaterialTheme.colorScheme.onSurfaceVariant
        is SyncState.Error -> if (isImport && syncState.diagnostic?.hasVerifiedRows == true) {
            L10n.string(L10n.syncStatusPartialVerified) to MaterialTheme.colorScheme.tertiary
        } else L10n.string(L10n.syncStatusFailed) to MaterialTheme.colorScheme.error
        is SyncState.Idle -> L10n.string(L10n.syncStatusWaiting) to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(
            AppIcons.forMetric(SyncSelection.sourceMetricKey(metricKey)),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            L10n.metric(metricKey),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            modifier = Modifier.width(if (isImport) 108.dp else 88.dp),
        ) {
            when (syncState) {
                is SyncState.InProgress ->
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
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
                maxLines = 2,
            )
        }
    }
}
