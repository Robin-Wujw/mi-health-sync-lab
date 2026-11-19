package com.bettermifitness.sync.sync

import com.bettermifitness.sync.data.time.dayContaining
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.zepp.ZeppStream
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

@Serializable
data class AutoSyncWindow(val from: Long, val until: Long) {
    init { require(from < until) }
}

@Serializable
data class AutoSyncCheckpoint(
    val contiguousUntil: Long,
    val later: List<AutoSyncWindow> = emptyList(),
    val historicalScanFrom: Long? = null,
    val coverageStart: Long? = null,
) {
    internal fun confirm(window: AutoSyncWindow): AutoSyncCheckpoint {
        var frontier = contiguousUntil
        val merged = mutableListOf<AutoSyncWindow>()
        for (interval in (later + window).sortedBy { it.from }) {
            if (interval.from <= frontier) {
                frontier = maxOf(frontier, interval.until)
                continue
            }
            val previous = merged.lastOrNull()
            if (previous != null && interval.from <= previous.until) {
                merged[merged.lastIndex] = previous.copy(until = maxOf(previous.until, interval.until))
            } else {
                merged += interval
            }
        }
        return copy(contiguousUntil = frontier, later = merged.take(MAX_LATER_WINDOWS),
            historicalScanFrom = historicalScanFrom?.takeIf { it > frontier })
    }

    internal fun historicalWindow(before: Long): AutoSyncWindow? {
        val end = minOf(before, contiguousUntil + AUTO_WINDOW_SECONDS, later.firstOrNull()?.from ?: before)
        return if (end > contiguousUntil) AutoSyncWindow(contiguousUntil, end) else null
    }

    internal fun alternateHistoricalWindow(oldest: AutoSyncWindow, before: Long): AutoSyncWindow? {
        val first = oldest.until
        val cursor = historicalScanFrom?.takeIf { it in first until before } ?: first
        return uncoveredWindow(cursor, before) ?: uncoveredWindow(first, cursor)
    }

    private fun uncoveredWindow(from: Long, before: Long): AutoSyncWindow? {
        var start = maxOf(from, contiguousUntil)
        for (interval in later) {
            if (start >= before) return null
            if (interval.until <= start) continue
            if (interval.from > start) return AutoSyncWindow(start, minOf(interval.from, before, start + AUTO_WINDOW_SECONDS))
            start = interval.until
        }
        return if (start < before) AutoSyncWindow(start, minOf(before, start + AUTO_WINDOW_SECONDS)) else null
    }

    internal fun attemptedAlternate(until: Long): AutoSyncCheckpoint =
        copy(historicalScanFrom = until.takeIf { it > contiguousUntil })
}

private const val MAX_LATER_WINDOWS = 64
internal const val AUTO_WINDOW_SECONDS = 86_400L

@Serializable
data class AutoSyncSlice(
    val window: AutoSyncWindow,
    val metrics: Set<String>,
    val historical: Boolean,
    val timeouts: Int = 0,
    val alternate: Boolean = false,
    val sourceDay: Boolean = false,
    val zeppSplitDepth: Int = 0,
    val requiredZeppEvents: Set<ZeppStream> = emptySet(),
    val failedLeafAttempts: Int = 0,
) {
    internal val canSplitZeppEvents: Boolean get() = !sourceDay &&
        SyncPreferences.ZEPP_CLOUD_IMPORT_KEY in metrics && zeppSplitDepth < 6 && window.until - window.from > 60

    internal fun splitZeppEvents(streams: Set<ZeppStream>): List<AutoSyncSlice> {
        check(canSplitZeppEvents)
        val middle = window.from + (window.until - window.from) / 2
        return listOf(AutoSyncWindow(window.from, middle), AutoSyncWindow(middle, window.until)).map { child ->
            copy(window = child, metrics = setOf(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY), timeouts = 0,
                zeppSplitDepth = zeppSplitDepth + 1, requiredZeppEvents = requiredZeppEvents + streams, failedLeafAttempts = 0)
        }
    }
}

@Serializable
data class AutoSyncContinuation(
    val id: String,
    val generation: Long,
    val enabled: Set<String>,
    val checkpointKeys: Map<String, String>,
    val remaining: List<AutoSyncSlice>,
    val calendarZoneId: String? = null,
) {
    internal fun matches(generation: Long, enabled: Set<String>, keys: Map<String, String>, zoneId: String? = null): Boolean =
        this.generation == generation && this.enabled == enabled && checkpointKeys == keys && calendarZoneId == zoneId &&
            remaining.isNotEmpty()

    internal fun advance(slice: AutoSyncSlice, timeout: Boolean, replacements: List<AutoSyncSlice> = emptyList(),
        resumedProgress: Boolean = false) = copy(
        remaining = remaining.flatMap { pending ->
            when {
                pending != slice -> listOf(pending)
                timeout && resumedProgress -> listOf(pending.copy(timeouts = 0))
                timeout && pending.timeouts == 0 -> listOf(pending.copy(timeouts = 1))
                else -> emptyList()
            }
        } + replacements,
    )
}

internal fun planSourceDaySlices(
    zone: TimeZone,
    until: Long,
    raw: Map<String, AutoSyncCheckpoint>,
    daily: Map<String, AutoSyncCheckpoint>,
): List<AutoSyncSlice> = daily.mapNotNull { (metric, checkpoint) ->
    val source = raw[metric] ?: return@mapNotNull null
    val baseline = source.coverageStart ?: return@mapNotNull null
    val verified = buildList {
        if (source.contiguousUntil > baseline) add(AutoSyncWindow(baseline, source.contiguousUntil))
        addAll(source.later)
    }
    val scan = maxOf(checkpoint.contiguousUntil, checkpoint.historicalScanFrom ?: baseline)
    fun next(from: Long, to: Long): AutoSyncWindow? {
        for (range in verified) {
            var cursor = maxOf(from, range.from, checkpoint.contiguousUntil)
            val limit = minOf(to, range.until)
            while (cursor < limit) {
                var day = zone.dayContaining(cursor)
                if (cursor > day.from) day = zone.dayContaining(day.until)
                if (day.until > limit || day.until <= cursor) break
                val done = checkpoint.later.firstOrNull { day.from >= it.from && day.until <= it.until }
                if (done == null) return AutoSyncWindow(day.from, day.until)
                cursor = done.until
            }
        }
        return null
    }
    (next(scan, until) ?: next(baseline, minOf(scan, until)))?.let { it to metric }
}.groupBy({ it.first }, { it.second }).entries.sortedBy { it.key.from }.map { (window, metrics) ->
    AutoSyncSlice(window, metrics.toSet(), historical = true, sourceDay = true)
}

internal fun planAutomaticWindows(
    until: Long,
    intervalMetrics: Set<String>,
    latestOnly: Set<String>,
    checkpoints: Map<String, AutoSyncCheckpoint>,
): List<AutoSyncSlice> {
    val recent = AutoSyncWindow(until - AUTO_WINDOW_SECONDS, until)
    val byMetric = intervalMetrics.mapNotNull { metric ->
        checkpoints[metric]?.historicalWindow(recent.from)?.let { metric to it }
    }
    val historical = byMetric.groupBy({ it.second }, { it.first })
    val alternate = byMetric.mapNotNull { (metric, oldest) ->
        checkpoints.getValue(metric).alternateHistoricalWindow(oldest, recent.from)?.let { it to metric }
    }.groupBy({ it.first }, { it.second })
    return buildList {
        if (intervalMetrics.isNotEmpty() || latestOnly.isNotEmpty()) {
            add(AutoSyncSlice(recent, intervalMetrics + latestOnly, historical = false))
        }
        historical.entries.sortedBy { it.key.from }.forEach { (window, metrics) ->
            add(AutoSyncSlice(window, metrics.toSet(), historical = true))
        }
        alternate.entries.sortedBy { it.key.from }.forEach { (window, metrics) ->
            add(AutoSyncSlice(window, metrics.toSet(), historical = true, alternate = true))
        }
    }
}
