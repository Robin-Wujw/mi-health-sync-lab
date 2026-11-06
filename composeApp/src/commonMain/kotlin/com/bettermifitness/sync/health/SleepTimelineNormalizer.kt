package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.SleepSession
import com.bettermifitness.sync.data.api.SleepStage
import com.bettermifitness.sync.data.time.MiTimezone

/**
 * Produce bounded, non-overlapping sleep timelines without inventing stages.
 * HC permits gaps and an empty stage list, but rejects overlapping stage ranges.
 * See androidx.health.connect.client.records.SleepSessionRecord.
 */
internal object SleepTimelineNormalizer {
    private const val UNKNOWN_STAGE = 1 // Mi's documented unknown state, not awake.

    fun normalize(sessions: List<SleepSession>, nowEpochSeconds: Long): List<SleepSession> {
        val clean = sessions.mapNotNull { clean(it, nowEpochSeconds) }
            .sortedWith(compareBy<SleepSession> { it.startTime }.thenBy { it.endTime })
        val result = mutableListOf<SleepSession>()
        val cluster = mutableListOf<SleepSession>()
        var clusterEnd = 0L
        for (session in clean) {
            // Legacy and modern reports of the same sleep may have different
            // starting points. Merge overlapping reports, never separated naps.
            if (cluster.isNotEmpty() && session.startTime >= clusterEnd) {
                result += mergeReports(cluster)
                cluster.clear()
            }
            cluster += session
            clusterEnd = maxOf(cluster.maxOf { it.endTime }, session.endTime)
        }
        if (cluster.isNotEmpty()) result += mergeReports(cluster)
        return result
    }

    private fun clean(session: SleepSession, now: Long): SleepSession? {
        val start = HealthDataNormalizer.toEpochSeconds(session.startTime)
        val rawEnd = HealthDataNormalizer.toEpochSeconds(session.endTime)
        if (!HealthDataNormalizer.isPlausibleEpochSeconds(start) ||
            !HealthDataNormalizer.isPlausibleEpochSeconds(rawEnd)
        ) return null
        // Health platforms require actual past instants. Do not add the global
        // clock-skew allowance to a sleep interval's end.
        val (_, end) = HealthTimePolicy.clampInterval(start, rawEnd, now, skewSec = 0L) ?: return null
        val inBedStart = HealthDataNormalizer.toEpochSeconds(session.inBedStart)
            .takeIf { HealthDataNormalizer.isPlausibleEpochSeconds(it) && it <= start } ?: start
        val inBedEnd = HealthDataNormalizer.toEpochSeconds(session.inBedEnd)
            .takeIf { HealthDataNormalizer.isPlausibleEpochSeconds(it) && it >= end }
            ?.coerceAtMost(now) ?: end
        return session.copy(
            startTime = start,
            endTime = end,
            inBedStart = inBedStart,
            inBedEnd = inBedEnd,
            // Keep original stage labels until all duplicate reports are joined.
            // Resolving one conflict early would lose its evidence on a later merge.
            stages = boundedStages(session.stages, start, end),
            // Keep positive source summaries intact. Their statistical algorithm
            // is unknown here, so a presumed "normal range" is not a validity rule.
            avgHrvMs = session.avgHrvMs?.takeIf { it > 0 },
            minHrvMs = session.minHrvMs?.takeIf { it > 0 },
            maxHrvMs = session.maxHrvMs?.takeIf { it > 0 },
            hrvAnalysisTimeSec = session.hrvAnalysisTimeSec
                ?.let(HealthDataNormalizer::toEpochSeconds)
                ?.takeIf { HealthDataNormalizer.isPlausibleEpochSeconds(it) && it <= now },
            tzIn15Min = MiTimezone.fromPayloadOrNull(session.tzIn15Min)?.unitsOf15Min,
        )
    }

    fun merge(a: SleepSession, b: SleepSession): SleepSession = mergeReports(listOf(a, b))

    private fun mergeReports(reports: List<SleepSession>): SleepSession {
        val start = reports.minOf { it.startTime }
        val end = reports.maxOf { it.endTime }
        val byCoverage = reports.sortedWith(
            compareByDescending<SleepSession> { it.endTime - it.startTime }
                .thenBy { it.startTime },
        )
        val preferred = byCoverage.first()
        val hrvSource = byCoverage.firstOrNull { it.avgHrvMs != null }
        return preferred.copy(
            startTime = start,
            endTime = end,
            inBedStart = reports.minOf { it.inBedStart.takeIf { bed -> bed > 0 } ?: it.startTime },
            inBedEnd = reports.maxOf { it.inBedEnd.takeIf { bed -> bed > 0 } ?: it.endTime },
            stages = timeline(reports.flatMap { it.stages }, start, end),
            // Preserve one report's HRV summary as a unit; never average separate
            // reports or combine an average with another report's min/max.
            avgHrvMs = hrvSource?.avgHrvMs,
            minHrvMs = hrvSource?.minHrvMs,
            maxHrvMs = hrvSource?.maxHrvMs,
            hrvAnalysisTimeSec = hrvSource?.hrvAnalysisTimeSec,
            tzIn15Min = byCoverage.firstNotNullOfOrNull { it.tzIn15Min },
        )
    }

    private data class Event(val time: Long, val state: Int, val delta: Int)

    private fun boundedStages(rawStages: List<SleepStage>, start: Long, end: Long): List<SleepStage> =
        rawStages.mapNotNull { raw ->
            val rawStart = HealthDataNormalizer.toEpochSeconds(raw.startTime)
            val rawEnd = HealthDataNormalizer.toEpochSeconds(raw.endTime)
            if (!HealthDataNormalizer.isPlausibleEpochSeconds(rawStart) ||
                !HealthDataNormalizer.isPlausibleEpochSeconds(rawEnd) || rawEnd <= rawStart
            ) return@mapNotNull null
            val s = maxOf(start, rawStart)
            val e = minOf(end, rawEnd)
            if (e <= s) return@mapNotNull null
            SleepStage(s, e, raw.stage)
        }

    fun timeline(rawStages: List<SleepStage>, start: Long, end: Long): List<SleepStage> {
        val events = boundedStages(rawStages, start, end).flatMap { raw ->
            val state = when (HealthDataNormalizer.miSleepStageKind(raw.stage)) {
                HealthDataNormalizer.MiSleepStageKind.AWAKE -> 5
                HealthDataNormalizer.MiSleepStageKind.DEEP -> 2
                HealthDataNormalizer.MiSleepStageKind.LIGHT -> 3
                HealthDataNormalizer.MiSleepStageKind.REM -> 4
                HealthDataNormalizer.MiSleepStageKind.UNKNOWN -> UNKNOWN_STAGE
            }
            listOf(Event(raw.startTime, state, 1), Event(raw.endTime, state, -1))
        }.groupBy { it.time }.toList().sortedBy { it.first }

        val active = mutableMapOf<Int, Int>()
        val result = mutableListOf<SleepStage>()
        var previous: Long? = null
        for ((time, changes) in events) {
            val from = previous
            if (from != null && time > from && active.isNotEmpty()) {
                val known = active.keys.filter { it != UNKNOWN_STAGE }
                // Conflicting explicit phases are unknown for their intersection.
                // A duplicate/unspecified report never overrides an explicit phase.
                val state = known.singleOrNull() ?: UNKNOWN_STAGE
                val last = result.lastOrNull()
                if (last != null && last.endTime == from && last.stage == state) {
                    result[result.lastIndex] = last.copy(endTime = time)
                } else {
                    result += SleepStage(from, time, state)
                }
            }
            for (event in changes) {
                val count = (active[event.state] ?: 0) + event.delta
                if (count == 0) active.remove(event.state) else active[event.state] = count
            }
            previous = time
        }
        return result
    }
}
