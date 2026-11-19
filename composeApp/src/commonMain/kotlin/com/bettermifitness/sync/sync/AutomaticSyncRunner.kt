package com.bettermifitness.sync.sync

import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.preferences.SyncPreferencesPort
import com.bettermifitness.sync.data.repository.AutomaticZeppRead
import com.bettermifitness.sync.data.repository.HealthSyncRunner
import com.bettermifitness.sync.data.repository.SyncRunResult
import com.bettermifitness.sync.data.repository.SyncPageScope
import com.bettermifitness.sync.data.repository.SOURCE_DAY_METRICS
import com.bettermifitness.sync.data.time.dayContaining
import com.bettermifitness.sync.i18n.L10n
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone

internal class AutomaticSyncRunner(private val prefs: SyncPreferencesPort, private val repository: HealthSyncRunner) {
    suspend fun run(
        selection: ActiveSyncSelection,
        keys: Map<String, String>,
        planId: String = selection.to.toString(),
        generation: Long = 0,
        zeppSourceIdentity: Long? = null,
        authorized: suspend () -> Boolean,
    ): SyncRunResult {
        val latest = selection.enabled.intersect(LATEST_ONLY)
        val interval = selection.enabled - latest
        val until = selection.to.epochSeconds
        val zone = prefs.readSourceCalendarZoneId()?.let { TimeZone.of(it) }
        val dailyKeys = if (zone == null) emptyMap() else keys.filterKeys { it in SOURCE_DAY_METRICS }
            .mapValues { (_, key) -> "$key-source-day" }
        val persisted = prefs.readAutomaticCheckpoints((keys.values + dailyKeys.values).toSet())
        val initial = until - AUTO_WINDOW_SECONDS
        val newRaw = keys.mapValues { (metric, key) ->
            val saved = persisted[key] ?: AutoSyncCheckpoint(initial)
            if (metric in dailyKeys && saved.coverageStart == null) saved.copy(coverageStart = saved.contiguousUntil) else saved
        }
        val newDaily = dailyKeys.mapValues { (metric, key) ->
            val start = checkNotNull(newRaw.getValue(metric).coverageStart)
            val day = checkNotNull(zone).dayContaining(start)
            persisted[key] ?: AutoSyncCheckpoint(if (start == day.from) start else day.until)
        }
        val checks = (keys.map { (metric, key) -> key to newRaw.getValue(metric) } +
            dailyKeys.map { (metric, key) -> key to newDaily.getValue(metric) }).toMap()
            .toMutableMap()
        var plan = prefs.readAutomaticContinuation()?.takeIf { it.matches(generation, selection.enabled, keys, zone?.id) }
            ?: AutoSyncContinuation(planId, generation, selection.enabled, keys,
                planAutomaticWindows(until, interval, latest, newRaw) +
                    (if (zone == null) emptyList() else planSourceDaySlices(zone, until, newRaw, newDaily)), zone?.id)
        val slices = plan.remaining
        val pageScope = SyncPageScope(plan.id, generation)
        var result = EMPTY
        if (slices.isEmpty() || !authorized() || !prefs.autoSync.first()) return result
        val baseline = checks.filter { (key, value) -> persisted[key] != value }
        prefs.saveAutomaticProgress(generation, baseline, plan)

        suspend fun advance(slice: AutoSyncSlice, report: SyncRunResult?, resumedProgress: Boolean) {
            val confirmed = if (slice.sourceDay) report?.confirmedSourceDayKeys.orEmpty() else report?.confirmedMetricKeys.orEmpty()
            val replacements = slice.continuationReplacements(report, resumedProgress, confirmed)
            val updated = plan.advance(slice, timeout = report == null, replacements = replacements, resumedProgress = resumedProgress)
            val advanceScan = replacements.isEmpty() && (slice.alternate || slice.sourceDay) &&
                (report != null || (slice.timeouts > 0 && !resumedProgress))
            val sliceKeys = if (slice.sourceDay) dailyKeys else keys
            val updates = slice.metrics.mapNotNull { metric ->
                val key = sliceKeys[metric] ?: return@mapNotNull null
                val previous = checks.getValue(key)
                val checkpoint = if (metric in confirmed) previous.confirm(slice.window) else previous
                val next = if (advanceScan) checkpoint.attemptedAlternate(slice.window.until) else checkpoint
                if (next == previous) null else key to next
            }.toMap()
            prefs.saveAutomaticProgress(generation, updates, updated.takeIf { it.remaining.isNotEmpty() })
            if (updated.remaining.isEmpty()) repository.discardPageCheckpoints(pageScope)
            plan = updated
            checks.putAll(updates)
        }

        suspend fun runLane(lane: List<AutoSyncSlice>, budget: Duration) {
            if (lane.isEmpty()) return
            var running: AutoSyncSlice? = null
            var checkpointRevision = -1L
            val completed = withTimeoutOrNull(budget) {
                for (slice in lane) {
                    if (!authorized() || !prefs.autoSync.first()) break
                    running = slice
                    checkpointRevision = repository.pageCheckpointRevision()
                    val zeppRead = if (SyncPreferences.ZEPP_CLOUD_IMPORT_KEY in slice.metrics) {
                        AutomaticZeppRead(slice.canSplitZeppEvents, slice.requiredZeppEvents, zeppSourceIdentity)
                    } else null
                    val report = repository.syncCheckpointedSlice(
                        Instant.fromEpochSeconds(slice.window.from).toString(),
                        Instant.fromEpochSeconds(slice.window.until).toString(), slice.metrics, zeppRead, pageScope,
                    )
                    withContext(NonCancellable) {
                        if (authorized()) advance(slice, report, repository.pageCheckpointRevision() > checkpointRevision)
                        result = result.merge(report)
                        if (slice.sourceDay) {
                            val unverified = slice.metrics.intersect(report.confirmedMetricKeys) - report.confirmedSourceDayKeys
                            if (unverified.isNotEmpty()) result = result.merge(incompleteSourceDay(unverified.size))
                        }
                        running = null
                    }
                }
                true
            }
            if (completed == null && authorized() && lane.any { it in plan.remaining }) {
                running?.let { advance(it, null, repository.pageCheckpointRevision() > checkpointRevision) }
                result = result.merge(timedOut())
            }
        }

        val (daily, ordinary) = slices.partition { it.sourceDay }
        val (history, recent) = ordinary.partition { it.historical }
        val (alternate, oldest) = history.partition { it.alternate }
        val dailyBudget = when {
            daily.isEmpty() -> Duration.ZERO
            ordinary.isEmpty() -> 7.minutes
            else -> 2.minutes
        }
        val ordinaryBudget = 7.minutes - dailyBudget
        val recentBudget = if (history.isEmpty()) ordinaryBudget else if (recent.isEmpty()) Duration.ZERO else 3.minutes
        val historyBudget = ordinaryBudget - recentBudget
        val oldestBudget = if (alternate.isEmpty()) historyBudget else if (oldest.isEmpty()) Duration.ZERO
            else historyBudget / 2
        runLane(recent, recentBudget)
        runLane(oldest, oldestBudget)
        runLane(alternate, historyBudget - oldestBudget)
        runLane(daily, dailyBudget)
        return result
    }

    private fun AutoSyncSlice.zeppReplacements(report: SyncRunResult?): List<AutoSyncSlice> = when {
        report == null -> emptyList()
        canSplitZeppEvents && report.zeppEventSplit.isNotEmpty() -> splitZeppEvents(report.zeppEventSplit)
        zeppSplitDepth > 0 && failedLeafAttempts == 0 && SyncPreferences.ZEPP_CLOUD_IMPORT_KEY !in report.confirmedMetricKeys ->
            listOf(copy(failedLeafAttempts = 1))
        else -> emptyList()
    }

    private fun AutoSyncSlice.continuationReplacements(report: SyncRunResult?, resumedProgress: Boolean, confirmed: Set<String>):
        List<AutoSyncSlice> {
        val zepp = zeppReplacements(report)
        if (!resumedProgress || report?.hadRetryableFailure != true || sourceDay) return zepp
        val remaining = metrics - confirmed - zepp.flatMap { it.metrics }.toSet()
        return zepp + if (remaining.isEmpty()) emptyList() else listOf(copy(metrics = remaining, timeouts = 0))
    }

    private fun timedOut() = EMPTY.copy(attempted = 1, failed = 1, errorMessages = listOf(L10n.text(L10n.syncInterrupted)))

    private fun incompleteSourceDay(count: Int) = EMPTY.copy(attempted = count, failed = count,
        errorMessages = listOf(L10n.text(L10n.syncSourceDayUnverified)))

    private fun SyncRunResult.merge(other: SyncRunResult) = SyncRunResult(
        attempted + other.attempted, succeeded + other.succeeded, failed + other.failed, totalRecords + other.totalRecords,
        hadRetryableFailure || other.hadRetryableFailure, hadAuthFailure || other.hadAuthFailure,
        errorMessages + other.errorMessages, noData + other.noData, noDataMessages + other.noDataMessages,
        excludedSourcePoints + other.excludedSourcePoints, warningMessages + other.warningMessages,
        excludedHrvSamples + other.excludedHrvSamples, confirmedMetricKeys + other.confirmedMetricKeys,
        healthAccessBlocks + other.healthAccessBlocks,
        confirmedSourceDayKeys + other.confirmedSourceDayKeys,
    )

    companion object {
        val LATEST_ONLY = setOf("weight", SyncPreferences.BODY_COMPOSITION_IMPORT_KEY)
        private val EMPTY = SyncRunResult(0, 0, 0, 0, false, false, emptyList())
    }
}
