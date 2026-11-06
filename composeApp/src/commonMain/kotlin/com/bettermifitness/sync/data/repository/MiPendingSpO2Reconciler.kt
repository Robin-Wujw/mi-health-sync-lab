package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.data.preferences.HealthImportPendingReconciliation
import com.bettermifitness.sync.data.preferences.HealthImportPendingSnapshot
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthRecordIds
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException

/**
 * Reconcile observed pending SpO2 or heart-rate targets in one local journal transaction.
 * The original class name is retained for existing callers. Heart rate never uses minute normalization.
 * No upload API, upload reservation, or broad pending reset is used here. Missing
 * observations never authorize a health write. All reads finish before the one
 * local mutation, so a read-time authentication refresh cannot replay a partial
 * reconciliation and make its diagnostic counts disappear.
 */
class MiPendingSpO2Reconciler(
    private val cloud: MiHealthImportCloud,
    private val journal: HealthImportJournal,
) {
    suspend fun reconcile(account: String, plan: MiHealthImportPlan,
        userRequested: Boolean, automatic: Boolean = false): MiHealthImportDiagnostic {
        require(account.isNotBlank())
        var stage = MiHealthImportStage.PREPARE
        var candidateCount = 0
        var broadReads = 0
        var broadFailures = 0
        var broadLimited = 0
        var historySelected = 0
        var historyPages = 0
        var historyComplete: Boolean? = null
        var historyFailed = false
        var historyDetails: MiTargetedHistoryRead? = null
        var oldestRecoveryAttempt: MiRecoveryAttemptDiagnostic? = null
        var readComparison: MiSpO2ReadComparison? = null
        var minuteMatchedRows = 0
        var lastReadFailure: MiHealthImportResponseSummary? = null
        val classifications = linkedMapOf<String, Classification>()
        fun count(kind: Classification) = classifications.values.count { it == kind }
        fun diagnostic(status: String): MiHealthImportDiagnostic {
            val reconciled = count(Classification.RECONCILED)
            val unresolved = candidateCount - reconciled
            val rejectedSources = plan.rawRows.filter {
                classifications[it.identity.stableKey] in setOf(Classification.SOURCE_CHANGED, Classification.SOURCE_COLLISION)
            }.flatMap { it.sourceRecordKeys }.distinct().size
            val reason = when {
                status == "UNSUPPORTED" -> MiHealthImportFailureReason.SOURCE_REJECTED
                unresolved == 0 -> null
                count(Classification.PERSISTENCE_FAILED) + count(Classification.JOURNAL_CHANGED) + count(Classification.UNQUERIED) > 0 ->
                    MiHealthImportFailureReason.UNVERIFIED
                count(Classification.CLOUD_CONFLICT) + count(Classification.DELETED) + count(Classification.OTHER_SOURCE) +
                    count(Classification.SOURCE_COLLISION) > 0 -> MiHealthImportFailureReason.CONFLICT
                count(Classification.SOURCE_CHANGED) > 0 -> MiHealthImportFailureReason.SOURCE_REJECTED
                else -> MiHealthImportFailureReason.PENDING
            }
            return MiHealthImportDiagnostic(
                metricKey = plan.metric.metricKey, stage = stage, channel = MiHealthImportChannel.RAW,
                inputRecords = plan.inputRecordCount, sourceRawRows = plan.rawRows.size,
                plannedRawRows = candidateCount, plannedDailyRows = 0,
                attemptedRawRows = 0, attemptedDailyRows = 0, rawBatchLimit = 0,
                verifiedRawRows = reconciled, alreadyPresentRawRows = reconciled, newlyVerifiedRawRows = 0,
                notAttemptedRawRows = unresolved, affectedRows = unresolved,
                blockedPendingRows = count(Classification.MISSING) + count(Classification.UNQUERIED) +
                    count(Classification.JOURNAL_CHANGED) + count(Classification.PERSISTENCE_FAILED),
                blockedConflictRows = count(Classification.CLOUD_CONFLICT) + count(Classification.DELETED) +
                    count(Classification.OTHER_SOURCE),
                sourceCollisionRows = count(Classification.SOURCE_COLLISION), sourceRejectedRecords = rejectedSources,
                failureReason = reason?.name, issueCounts = summarizeMiHealthImportIssues(plan.issues),
                readResponse = lastReadFailure.takeIf { count(Classification.UNQUERIED) > 0 },
                pendingReconcile = MiPendingReconcileDiagnostic(
                    status = status, userRequested = userRequested, candidateRows = candidateCount,
                    reconciledRows = reconciled, unresolvedRows = unresolved,
                    sourceChangedRows = count(Classification.SOURCE_CHANGED),
                    sourceCollisionRows = count(Classification.SOURCE_COLLISION),
                    cloudConflictRows = count(Classification.CLOUD_CONFLICT), deletedRows = count(Classification.DELETED),
                    otherSourceRows = count(Classification.OTHER_SOURCE), missingRows = count(Classification.MISSING),
                    unqueriedRows = count(Classification.UNQUERIED), journalChangedRows = count(Classification.JOURNAL_CHANGED),
                    persistenceFailedRows = count(Classification.PERSISTENCE_FAILED),
                    broadReadGroups = broadReads, broadReadFailureGroups = broadFailures, broadReadLimitedGroups = broadLimited,
                    historySelectedRows = historySelected, historyPagesRead = historyPages,
                    historyComplete = historyComplete, historyReadFailed = historyFailed,
                    historyQueries = historyDetails?.queries ?: 0,
                    historyFollowUpQueries = historyDetails?.followUpQueries ?: 0,
                    historyTargetsWithinObservedSpan = historyDetails?.targetsWithinObservedSpan ?: 0,
                    historyStopReasons = historyDetails?.stopReasons.orEmpty(),
                    historyFollowUpFailures = historyDetails?.followUpFailures ?: 0,
                    oldestRecoveryAttempt = oldestRecoveryAttempt,
                    readComparison = readComparison,
                    minuteReconciledRows = minuteMatchedRows,
                ),
            )
        }

        if (!userRequested && !automatic) return diagnostic("NOT_REQUESTED")
        if (plan.metric !in setOf(HealthInboundMetric.OXYGEN_SATURATION, HealthInboundMetric.HEART_RATE) || plan.rawRows.any {
                it.identity.key != plan.metric.metricKey || it.identity.sid != MI_HEALTH_CONNECT_IMPORT_SID ||
                    it.identity.tag != null || it.enrichment != null
            }) return diagnostic("UNSUPPORTED")
        val grouped = plan.rawRows.groupBy { it.identity.stableKey }
        val minutePolicy = MiSpO2MinuteMatchPolicy(plan.rawRows)
        val pending = journal.pendingIdentities(account, grouped.keys)
        val candidates = grouped.filterKeys { it in pending }
        candidateCount = candidates.size
        if (candidateCount == 0) {
            stage = MiHealthImportStage.COMPLETE
            return diagnostic("NO_PENDING")
        }
        val snapshots = journal.pendingSnapshots(account, candidates.keys)
        val oldestCandidate = candidates.values.flatten().minWithOrNull(compareBy({ it.identity.time }, { it.identity.stableKey }))
        oldestRecoveryAttempt = oldestCandidate?.let { snapshots[it.identity.stableKey] }
            ?.takeIf { it.recoveryConsumed }?.previousRecoveryDiagnostic()
        val targets = linkedMapOf<String, Target>()
        // A rejected mapper input may have removed an unknown conflicting target.
        // Without trustworthy target attribution, none of this plan's pending
        // rows is silently reconciled past that rejected source material.
        val rejected = plan.issues.filter { it.severity == MiHealthImportIssueSeverity.REJECTED }
        val sourcePlanRejected = rejected.any { plan.metric != HealthInboundMetric.HEART_RATE || it.affectedLocalDays.isNullOrEmpty() }
        for ((identity, sameTarget) in candidates) {
            if (sameTarget.map { it.canonicalContent to it.zoneOffsetSeconds }.distinct().size != 1) {
                classifications[identity] = Classification.SOURCE_COLLISION
                continue
            }
            val row = sameTarget.first().copy(
                sourceRecordKeys = sameTarget.flatMap { it.sourceRecordKeys }.toSet(),
                dayAggregationEligible = sameTarget.all { it.dayAggregationEligible },
            )
            val snapshot = snapshots[identity]
            val sourceEntry = MiHealthImportRunner.journalEntry(row)
            if (sourcePlanRejected || snapshot == null || !snapshot.matches(sourceEntry) ||
                row.sourceRecordKeys.isEmpty() || !row.dayAggregationEligible ||
                row.zoneOffsetSeconds !in -64_800..64_800 || bounds(row.identity.time) == null) {
                classifications[identity] = Classification.SOURCE_CHANGED
                continue
            }
            targets[identity] = Target(row, snapshot)
        }
        if (targets.isEmpty()) return diagnostic("INCOMPLETE")

        stage = MiHealthImportStage.PRECHECK
        val observations = mutableListOf<MiHealthImportCloudRow>()
        suspend fun <T> read(action: suspend () -> T): T? = try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MiApiException.AuthExpired) {
            throw e // No local reconciliation has begun, and there is never a health write.
        } catch (e: Exception) {
            lastReadFailure = miHealthImportFailureSummary(e)
            null
        }
        val days = targets.values.groupBy { it.row.identity.time.floorDiv(DAY_SECONDS) }
            .entries.sortedBy { it.key }
        broadLimited = (days.size - MAX_BROAD_GROUPS).coerceAtLeast(0)
        for ((_, group) in days.take(MAX_BROAD_GROUPS)) {
            val window = bounds(group.first().row.identity.time)!!
            broadReads++
            val observed = read { cloud.readRows(MiHealthImportChannel.RAW, plan.metric.metricKey, window.first, window.second) }
            if (observed == null) broadFailures++ else observations += observed
        }

        // Reuse every returned observation for every relevant target. Overlapping
        // day windows can reveal a conflict for a target that matched earlier.
        // Never replace an earlier conflict with a later matching observation.
        val broadObservations = observations.groupBy { it.time }
        val requiresHistory = targets.values.any {
            observe(it.row, broadObservations[it.row.identity.time].orEmpty()).kind != ObservationKind.MATCH
        }
        if (requiresHistory) {
            historySelected = targets.size
            val history = read { readMiTargetedHistory(cloud, targets.values.map { it.row },
                MAX_NATIVE_PAGES, followUpLimit = 8, followUpPages = 4) }
            if (history == null) {
                historyFailed = true
            } else {
                historyPages = history.pagesRead.coerceAtLeast(0)
                historyDetails = history
                historyComplete = targets.values.all { it.row.identity in history.completedTargetIdentities }
                observations += history.rows
            }
        }

        val combined = observations.groupBy { it.time }
        val matched = mutableListOf<HealthImportPendingReconciliation>()
        for ((identity, target) in targets) {
            val observed = observe(target.row, combined[target.row.identity.time].orEmpty())
            val normalized = if (observed.kind == ObservationKind.ABSENT) minutePolicy.find(target.row, observations) else null
            if (normalized != null) {
                matched += HealthImportPendingReconciliation(
                    expected = target.snapshot, sourceEntry = MiHealthImportRunner.journalEntry(target.row),
                    observedContentFingerprint = HealthRecordIds.version(canonicalMiHealthImportJson(normalized.value)).toString(),
                    observedTargetIdentity = normalized.importIdentity().stableKey,
                )
                continue
            }
            when (observed.kind) {
                ObservationKind.MATCH -> matched += HealthImportPendingReconciliation(
                    expected = target.snapshot, sourceEntry = MiHealthImportRunner.journalEntry(target.row),
                    observedContentFingerprint = HealthRecordIds.version(
                        canonicalMiHealthImportJson(observed.actual!!.value)).toString(),
                )
                ObservationKind.DELETED -> classifications[identity] = Classification.DELETED
                ObservationKind.OTHER_SOURCE -> classifications[identity] = Classification.OTHER_SOURCE
                ObservationKind.CONFLICT -> classifications[identity] = Classification.CLOUD_CONFLICT
                ObservationKind.ABSENT -> classifications[identity] =
                    if (target.row.identity in historyDetails?.completedTargetIdentities.orEmpty()) Classification.MISSING else Classification.UNQUERIED
            }
        }

        // Reuse this action's observations only. The strict minute policy above
        // is the sole normalization accepted; other similarities remain hints.
        if (plan.metric == HealthInboundMetric.OXYGEN_SATURATION) readComparison = compareMiSpO2Reads(plan.rawRows, observations,
            targets.filterKeys { classifications[it] == Classification.MISSING }.values.map { it.row.identity }.toSet())

        if (matched.isNotEmpty()) {
            val committed = try {
                journal.reconcileObservedPending(account, matched)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Do not rethrow an authentication-shaped error from local state
                // persistence into the repository's whole-operation auth retry.
                matched.forEach { classifications[it.expected.targetIdentity] = Classification.PERSISTENCE_FAILED }
                return diagnostic("LOCAL_SAVE_FAILED")
            }
            matched.forEach {
                classifications[it.expected.targetIdentity] = if (it.expected.targetIdentity in committed)
                    Classification.RECONCILED else Classification.JOURNAL_CHANGED
                if (it.expected.targetIdentity in committed && it.observedTargetIdentity != it.sourceEntry.targetIdentity) minuteMatchedRows++
            }
        }
        check(classifications.size == candidateCount)
        val reconciled = count(Classification.RECONCILED)
        if (reconciled == candidateCount) stage = MiHealthImportStage.COMPLETE
        return diagnostic(when {
            reconciled == candidateCount -> "COMPLETE"
            reconciled > 0 -> "PARTIAL"
            else -> "INCOMPLETE"
        })
    }

    private data class Target(val row: MiHealthImportRow, val snapshot: HealthImportPendingSnapshot)
    private enum class Classification {
        SOURCE_CHANGED, SOURCE_COLLISION, CLOUD_CONFLICT, DELETED, OTHER_SOURCE,
        MISSING, UNQUERIED, JOURNAL_CHANGED, PERSISTENCE_FAILED, RECONCILED,
    }
    private enum class ObservationKind { MATCH, ABSENT, DELETED, OTHER_SOURCE, CONFLICT }
    private data class Observation(val kind: ObservationKind, val actual: MiHealthImportCloudRow? = null)

    private fun observe(row: MiHealthImportRow, observed: List<MiHealthImportCloudRow>): Observation {
        val atTarget = observed.filter { it.key == row.identity.key && it.time == row.identity.time }
        if (atTarget.any { it.deleted }) return Observation(ObservationKind.DELETED)
        if (atTarget.any { it.sid != row.identity.sid }) return Observation(ObservationKind.OTHER_SOURCE)
        return when (MiHealthImportVerifier.verify(row, atTarget).status) {
            MiHealthImportVerificationStatus.VERIFIED -> Observation(ObservationKind.MATCH,
                atTarget.first { !it.deleted && it.sid == row.identity.sid })
            MiHealthImportVerificationStatus.MISSING -> Observation(ObservationKind.ABSENT)
            else -> Observation(ObservationKind.CONFLICT)
        }
    }

    private fun bounds(time: Long): Pair<Long, Long>? {
        if (time < 0) return null
        val day = time.floorDiv(DAY_SECONDS)
        if (day > (Long.MAX_VALUE - 2) / DAY_SECONDS - 1) return null
        return (day * DAY_SECONDS - DAY_SECONDS).coerceAtLeast(0) to ((day + 1) * DAY_SECONDS + 2)
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
        const val MAX_BROAD_GROUPS = 32
        const val MAX_NATIVE_PAGES = 32
    }
}
