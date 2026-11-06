package com.bettermifitness.sync.data.api

import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException

/** A bounded set of independent native queries. Completion belongs to each query's end bound. */
internal data class MiTargetedHistoryRead(
    val rows: List<MiHealthImportCloudRow>,
    val pagesRead: Int,
    val queries: Int,
    val completeQueries: Int,
    val completedTargetIdentities: Set<MiHealthImportIdentity>,
    val stopReasons: Map<MiHistoryStopReason, Int>,
    val followUpQueries: Int,
    val targetsWithinObservedSpan: Int,
    val followUpFailures: Int = 0,
)

/**
 * A capped reverse traversal can spend every page on newer data. Re-anchor a
 * few reads just after the oldest unseen target instead of restarting the same
 * cursor chain. Only PAGE_LIMIT permits this fallback; malformed pagination
 * must remain visible. Returned timestamps never prove absence or EOF.
 */
internal suspend fun readMiTargetedHistory(
    cloud: MiHealthImportCloud,
    targets: List<MiHealthImportRow>,
    initialPages: Int,
    followUpLimit: Int,
    followUpPages: Int,
): MiTargetedHistoryRead {
    require(targets.isNotEmpty() && targets.map { it.identity.key }.distinct().size == 1)
    require(targets.all { it.identity.channel == MiHealthImportChannel.RAW && it.identity.time in 0 until Long.MAX_VALUE })
    require(followUpLimit in 0..8 && followUpPages in 1..32)
    val observations = mutableListOf<MiHealthImportCloudRow>()
    val completed = mutableSetOf<MiHealthImportIdentity>()
    val reasons = mutableMapOf<MiHistoryStopReason, Int>()
    val usedEnds = mutableSetOf<Long>()
    val withinSpan = mutableSetOf<MiHealthImportIdentity>()
    var pages = 0
    var queries = 0
    var completeQueries = 0
    var followUpFailures = 0
    suspend fun read(until: Long, limit: Int): MiHealthImportHistoryRead {
        usedEnds += until
        val history = cloud.readRawRowsWithoutStart(targets.first().identity.key, until, limit)
        require(history.pagesRead in 1..limit)
        queries++
        pages += history.pagesRead
        reasons[history.stopReason] = (reasons[history.stopReason] ?: 0) + 1
        val bounded = history.rows.filter { it.time >= 0 && it.time < until }
        observations += bounded
        if (history.complete) {
            completeQueries++
            completed += targets.filter { it.identity.time < until }.map { it.identity }
        }
        val earliest = bounded.minOfOrNull { it.time }
        val latest = bounded.maxOfOrNull { it.time }
        if (earliest != null && latest != null) withinSpan += targets.filter {
            it.identity.time in earliest..latest
        }.map { it.identity }
        return history
    }
    val initial = read(targets.maxOf { it.identity.time } + 1, initialPages)
    if (!initial.complete && initial.stopReason == MiHistoryStopReason.PAGE_LIMIT) {
        for (index in 0 until followUpLimit) {
            val next = targets.sortedBy { it.identity.time }.firstOrNull { target ->
                target.identity !in completed && target.identity.time + 1 !in usedEnds &&
                    observations.none { it.key == target.identity.key && it.time == target.identity.time }
            } ?: break
            val followUp = try { read(next.identity.time + 1, followUpPages) }
            catch (e: CancellationException) { throw e }
            catch (e: MiApiException.AuthExpired) { throw e }
            catch (_: Exception) { followUpFailures++; break }
            if (!followUp.complete && followUp.stopReason != MiHistoryStopReason.PAGE_LIMIT) break
        }
    }
    return MiTargetedHistoryRead(observations.distinct(), pages, queries, completeQueries,
        completed, reasons.toMap(), (queries - 1).coerceAtLeast(0), withinSpan.size, followUpFailures)
}
