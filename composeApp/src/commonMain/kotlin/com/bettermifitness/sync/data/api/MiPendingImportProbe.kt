package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException

/** Counts only: safe to include in a diagnostic without identifying any record. */
data class MiPendingImportProbeResult(
    val pendingRows: Int = 0,
    val pendingVerified: Int = 0,
    val pendingMissing: Int = 0,
    val pendingMismatch: Int = 0,
    /** Includes [sourceCollisionRows], where expected content itself is ambiguous. */
    val pendingAmbiguous: Int = 0,
    val pendingUnqueried: Int = 0,
    /** Pending target identities with conflicting expected source content. */
    val sourceCollisionRows: Int = 0,
    /** Attempted broad reads, including failed reads. */
    val broadReadGroups: Int = 0,
    val broadReadFailureGroups: Int = 0,
    val broadReadLimitedGroups: Int = 0,
    val probeSelected: Int = 0,
    /** A narrow read returned a semantic match, not merely the same timestamp. */
    val probeFound: Int = 0,
    val probeMissing: Int = 0,
    val probeMismatch: Int = 0,
    val probeAmbiguous: Int = 0,
    val probeUnqueried: Int = 0,
    /** Different observations can also reflect cloud visibility changing between reads. */
    val broadVsNarrowDisagreement: Int = 0,
    /** Broad-MISSING targets with nondeleted same key/time/tag rows under another explicit SID. */
    val missingWithOtherSourceRows: Int = 0,
    /** Broad-MISSING targets with a tombstone under their exact SID/key/time/tag. */
    val missingWithDeletedRows: Int = 0,
    val nativeSelected: Int = 0,
    val nativeFound: Int = 0,
    val nativeMissing: Int = 0,
    val nativeMismatch: Int = 0,
    val nativeAmbiguous: Int = 0,
    val nativeUnqueried: Int = 0,
    val nativePagesRead: Int = 0,
    val nativeCompleteQueries: Int = 0,
    val nativeQueries: Int = 0,
    val nativeFollowUpQueries: Int = 0,
    val nativeTargetsWithinObservedSpan: Int = 0,
    val nativeStopReasons: Map<MiHistoryStopReason, Int> = emptyMap(),
    val nativeFollowUpFailures: Int = 0,
)

/**
 * Diagnose existing uncertain imports using reads only. This never uploads,
 * marks a target verified, clears pending state, or authorizes a later retry.
 *
 * First use the Runner's overlapping UTC-day read window for pending targets.
 * Then independently read at most three targets that were MISSING, ending each
 * query just after that target. For a reverse-ordered API this removes newer
 * records from the window without dropping the preceding-day bucket overlap.
 * Finally compare missing RAW targets using at most one key and five native
 * full-pull pages without start_time or limit. Unreturned targets are MISSING
 * only after an explicitly completed traversal; otherwise they are unqueried.
 *
 * MISSING means the query did not return the target's non-deleted SID/key/time
 * identity. Neither a broad nor a narrow MISSING result proves non-persistence.
 * A successful read also does not establish the endpoint's completeness.
 */
class MiPendingImportProbe(
    private val cloud: MiHealthImportCloud,
    private val journal: HealthImportJournal,
    private val maxBroadReadGroups: Int = MAX_BROAD_READ_GROUPS,
) {
    init {
        require(maxBroadReadGroups in 1..MAX_BROAD_READ_GROUPS)
    }

    suspend fun inspect(account: String, plan: MiHealthImportPlan): MiPendingImportProbeResult {
        require(account.isNotBlank())
        val candidates = plan.rows.groupBy { it.identity }
        if (candidates.isEmpty()) return MiPendingImportProbeResult()
        val pending = journal.pendingIdentities(account, candidates.keys.map { it.stableKey }.toSet())
        val pendingCandidates = candidates.filterKeys { it.stableKey in pending }
        if (pendingCandidates.isEmpty()) return MiPendingImportProbeResult()

        val sourceCollisions = pendingCandidates.filterValues { sameTarget ->
            sameTarget.map { it.canonicalContent to it.zoneOffsetSeconds }.distinct().size > 1
        }.keys
        // Do not choose a source revision arbitrarily when expected content conflicts.
        val rows = pendingCandidates.filterKeys { it !in sourceCollisions }.values.map { it.first() }
        val groups = rows.groupBy { Group(it.identity.channel, it.identity.key, it.identity.time.floorDiv(DAY_SECONDS)) }
            .entries.sortedWith(compareBy({ it.key.day }, { it.key.channel.ordinal }, { it.key.key }))
        val broad = mutableMapOf<MiHealthImportIdentity, MiHealthImportVerificationStatus>()
        val missingOtherSource = mutableSetOf<MiHealthImportIdentity>()
        val missingDeleted = mutableSetOf<MiHealthImportIdentity>()
        var broadReadGroups = 0
        var broadReadFailures = 0
        for ((group, targets) in groups.take(maxBroadReadGroups)) {
            // Invalid bounds remain unqueried, never evidence that a row is missing.
            val bounds = broadBounds(group.day) ?: continue
            broadReadGroups++
            val observed = read(group.channel, group.key, bounds.first, bounds.second)
            if (observed == null) {
                broadReadFailures++
                continue
            }
            for (target in targets) {
                val identity = target.identity
                val status = MiHealthImportVerifier.verify(target, observed).status
                broad[identity] = status
                if (status == MiHealthImportVerificationStatus.MISSING) {
                    val nearby = observed.filter { it.key == identity.key && it.time == identity.time && it.tag == identity.tag }
                    if (nearby.any { !it.deleted && !it.sid.isNullOrBlank() && it.sid != identity.sid }) {
                        missingOtherSource += identity
                    }
                    if (nearby.any { it.deleted && it.sid == identity.sid }) missingDeleted += identity
                }
            }
        }

        val missing = rows.filter { broad[it.identity] == MiHealthImportVerificationStatus.MISSING }
            .sortedWith(compareBy({ it.identity.time }, { it.identity.channel.ordinal }, { it.identity.key }, { it.identity.sid },
                { it.identity.tag.orEmpty() }))
        val selected = if (missing.size <= MAX_NARROW_PROBES) missing
            else listOf(missing.first(), missing[missing.size / 2], missing.last())
        val narrow = mutableListOf<MiHealthImportVerificationStatus>()
        for (target in selected) {
            val time = target.identity.time
            if (time < 0 || time == Long.MAX_VALUE) continue
            val bounds = broadBounds(time.floorDiv(DAY_SECONDS)) ?: continue
            val observed = read(target.identity.channel, target.identity.key, bounds.first, time + 1) ?: continue
            narrow += MiHealthImportVerifier.verify(target, observed).status
        }

        val nativeTargets = missing.filter { it.identity.channel == MiHealthImportChannel.RAW }
        val native = mutableMapOf<MiHealthImportIdentity, MiHealthImportVerificationStatus>()
        var nativePagesRead = 0
        var nativeCompleteQueries = 0
        var historyDetails: MiTargetedHistoryRead? = null
        // A normal metric plan has one raw key. Keep the diagnostic bounded even
        // if a future caller supplies a mixed plan; additional keys are unqueried.
        val nativeGroup = nativeTargets.groupBy { it.identity.key }.entries.sortedBy { it.key }.firstOrNull()
        if (nativeGroup != null) {
            val latest = nativeGroup.value.maxOf { it.identity.time }
            if (latest >= 0 && latest < Long.MAX_VALUE) {
                val history = readNative(nativeGroup.value)
                if (history != null) {
                    historyDetails = history
                    nativePagesRead = history.pagesRead
                    nativeCompleteQueries = history.completeQueries
                    for (target in nativeGroup.value) {
                        val status = MiHealthImportVerifier.verify(target, history.rows).status
                        if (status != MiHealthImportVerificationStatus.MISSING || target.identity in history.completedTargetIdentities) {
                            native[target.identity] = status
                        }
                    }
                }
            }
        }

        fun broadCount(status: MiHealthImportVerificationStatus) = broad.values.count { it == status }
        fun narrowCount(status: MiHealthImportVerificationStatus) = narrow.count { it == status }
        fun nativeCount(status: MiHealthImportVerificationStatus) = native.values.count { it == status }
        return MiPendingImportProbeResult(
            pendingRows = pendingCandidates.size,
            pendingVerified = broadCount(MiHealthImportVerificationStatus.VERIFIED),
            pendingMissing = broadCount(MiHealthImportVerificationStatus.MISSING),
            pendingMismatch = broadCount(MiHealthImportVerificationStatus.MISMATCH),
            pendingAmbiguous = broadCount(MiHealthImportVerificationStatus.AMBIGUOUS) + sourceCollisions.size,
            pendingUnqueried = rows.size - broad.size,
            sourceCollisionRows = sourceCollisions.size,
            broadReadGroups = broadReadGroups,
            broadReadFailureGroups = broadReadFailures,
            broadReadLimitedGroups = (groups.size - maxBroadReadGroups).coerceAtLeast(0),
            probeSelected = selected.size,
            probeFound = narrowCount(MiHealthImportVerificationStatus.VERIFIED),
            probeMissing = narrowCount(MiHealthImportVerificationStatus.MISSING),
            probeMismatch = narrowCount(MiHealthImportVerificationStatus.MISMATCH),
            probeAmbiguous = narrowCount(MiHealthImportVerificationStatus.AMBIGUOUS),
            probeUnqueried = selected.size - narrow.size,
            broadVsNarrowDisagreement = narrow.count { it != MiHealthImportVerificationStatus.MISSING },
            missingWithOtherSourceRows = missingOtherSource.size,
            missingWithDeletedRows = missingDeleted.size,
            nativeSelected = nativeTargets.size,
            nativeFound = nativeCount(MiHealthImportVerificationStatus.VERIFIED),
            nativeMissing = nativeCount(MiHealthImportVerificationStatus.MISSING),
            nativeMismatch = nativeCount(MiHealthImportVerificationStatus.MISMATCH),
            nativeAmbiguous = nativeCount(MiHealthImportVerificationStatus.AMBIGUOUS),
            nativeUnqueried = nativeTargets.size - native.size,
            nativePagesRead = nativePagesRead,
            nativeCompleteQueries = nativeCompleteQueries,
            nativeQueries = historyDetails?.queries ?: 0,
            nativeFollowUpQueries = historyDetails?.followUpQueries ?: 0,
            nativeTargetsWithinObservedSpan = historyDetails?.targetsWithinObservedSpan ?: 0,
            nativeStopReasons = historyDetails?.stopReasons.orEmpty(),
            nativeFollowUpFailures = historyDetails?.followUpFailures ?: 0,
        )
    }

    private suspend fun readNative(targets: List<MiHealthImportRow>): MiTargetedHistoryRead? = try {
        readMiTargetedHistory(cloud, targets, MAX_NATIVE_PAGES, followUpLimit = 3, followUpPages = 2)
    } catch (e: CancellationException) {
        throw e
    } catch (e: MiApiException.AuthExpired) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun read(
        channel: MiHealthImportChannel,
        key: String,
        from: Long,
        until: Long,
    ): List<MiHealthImportCloudRow>? = try {
        cloud.readRows(channel, key, from, until)
    } catch (e: CancellationException) {
        throw e
    } catch (e: MiApiException.AuthExpired) {
        throw e // Safe for the caller to refresh a session: no writes occurred.
    } catch (_: Exception) {
        // Keep errors distinct from a successful query that returned no match.
        null
    }

    private fun broadBounds(day: Long): Pair<Long, Long>? {
        if (day < 0 || day > (Long.MAX_VALUE - 2) / DAY_SECONDS - 1) return null
        val from = (day * DAY_SECONDS - DAY_SECONDS).coerceAtLeast(0)
        return from to ((day + 1) * DAY_SECONDS + 2)
    }

    private data class Group(val channel: MiHealthImportChannel, val key: String, val day: Long)

    private companion object {
        const val DAY_SECONDS = 86_400L
        const val MAX_BROAD_READ_GROUPS = 32
        const val MAX_NARROW_PROBES = 3
        const val MAX_NATIVE_PAGES = 5
    }
}
