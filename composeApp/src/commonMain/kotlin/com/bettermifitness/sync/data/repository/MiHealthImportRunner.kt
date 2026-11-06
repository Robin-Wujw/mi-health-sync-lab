package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloud
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.api.MiHealthImportResponseSummary
import com.bettermifitness.sync.data.api.MiHealthImportStage
import com.bettermifitness.sync.data.api.MiHealthImportIssueSeverity
import com.bettermifitness.sync.data.api.MiHealthImportPlan
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiHealthImportVerificationStatus
import com.bettermifitness.sync.data.api.MiHealthImportVerifier
import com.bettermifitness.sync.data.api.MiHealthImportObservationIndex
import com.bettermifitness.sync.data.api.MI_HEALTH_IMPORT_RAW_BATCH_SIZE
import com.bettermifitness.sync.data.api.MI_HEALTH_IMPORT_AGGREGATE_BATCH_SIZE
import com.bettermifitness.sync.data.api.canonicalMiHealthImportJson
import com.bettermifitness.sync.data.api.miHealthImportFailureSummary
import com.bettermifitness.sync.data.api.MiHealthImportVerification
import com.bettermifitness.sync.data.api.summarizeMiHealthImportIssues
import com.bettermifitness.sync.data.api.summarizeMiHealthImportMismatchFields
import com.bettermifitness.sync.data.api.MiSpO2MinuteMatchPolicy
import com.bettermifitness.sync.data.api.importIdentity
import com.bettermifitness.sync.data.preferences.HealthImportPendingReconciliation
import com.bettermifitness.sync.data.preferences.HealthImportObservedAlias
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.data.preferences.HealthImportJournalEntry
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.health.HealthRecordIds
import com.bettermifitness.sync.health.HealthInboundMetric
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class MiHealthImportFailureReason { CONFLICT, PENDING, REJECTED, UNVERIFIED, SOURCE_REJECTED }

class MiHealthImportRunException(
    val reason: MiHealthImportFailureReason,
    val verifiedRawCount: Int,
    val affectedCount: Int,
    val details: String = "",
    cause: Throwable? = null,
    val diagnostic: MiHealthImportDiagnostic? = null,
) : IllegalStateException("${reason.name}: $affectedCount records; $verifiedRawCount raw records verified. $details", cause)

data class MiHealthImportRunResult(
    val verifiedRawCount: Int,
    val newlyUploadedRawCount: Int,
    val alreadyPresentRawCount: Int,
    val verifiedAggregateCount: Int,
    /** Stable issue codes, localized by the caller. */
    val warnings: List<String>,
    val diagnostic: MiHealthImportDiagnostic? = null,
)

/**
 * Every upload has a preceding durable journal entry and a subsequent semantic
 * cloud readback. The journal is row-based, so changing the selected HC time
 * window or batch boundaries does not turn an uncertain write into a new one.
 * Daily reports and unowned differing values are preserved. Owned sleep replacement
 * requires a separate opt-in, unchanged baseline and a final cloud/journal recheck.
 */
class MiHealthImportRunner(
    private val cloud: MiHealthImportCloud,
    private val journal: HealthImportJournal,
    private val readbackPause: suspend (Long) -> Unit = { delay(it) },
) {
    // One ordered state machine, kept whole so no safety step can be skipped. Its behavior was verified on a device.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "ThrowsCount")
    suspend fun run(account: String, plan: MiHealthImportPlan, testMode: Boolean = false,
        allowLegacySleepHrvEnrichment: Boolean = false,
        allowOwnedSleepRevision: Boolean = false): MiHealthImportRunResult {
        require(account.isNotBlank())
        // A test uses one real source row and creates no daily report. It does
        // not clear the journal, bypass preflight or resend an uncertain write.
        val selectedTestRow = if (testMode) plan.rawRows.maxByOrNull { it.identity.time } else null
        val selectedRows = if (testMode) selectedTestRow?.let { selected ->
            // Keep colliding revisions until the normal conflict check rejects them.
            plan.rawRows.filter { it.identity == selected.identity }
        }.orEmpty() else plan.rows
        val selectedIssues = if (testMode && selectedTestRow != null) plan.issues.filter { issue ->
            issue.sourceRecordKeys.isEmpty() || issue.sourceRecordKeys.any { it in selectedTestRow.sourceRecordKeys }
        } else plan.issues
        val duplicateTargets = selectedRows.groupBy { it.identity }.filterValues { same ->
            same.map { it.canonicalContent to it.zoneOffsetSeconds }.distinct().size > 1
        }.keys
        // Conflicting candidates never get an arbitrary winner. Isolate their
        // target identities while allowing independent, valid targets to run.
        val rows = selectedRows.distinctBy { it.identity }
        val minutePolicy = MiSpO2MinuteMatchPolicy(plan.rawRows)
        // A rejected mapper input can hide another target in the same minute.
        val allowMinuteMatch = plan.issues.none { it.severity == MiHealthImportIssueSeverity.REJECTED }
        val rejected = selectedIssues.filter { it.severity == MiHealthImportIssueSeverity.REJECTED }
        val incompleteDayIssues = selectedIssues.filter { it.severity == MiHealthImportIssueSeverity.REJECTED ||
            it.code == "HEART_RATE_SOURCE_POINT_EXCLUDED" }
        val sourceRejectedCount = rejected.flatMap { it.sourceRecordKeys }.distinct().size +
            rejected.count { it.sourceRecordKeys.isEmpty() }
        val warnings = selectedIssues.filter { it.severity == MiHealthImportIssueSeverity.WARNING &&
            it.code != "HEART_RATE_SOURCE_POINT_EXCLUDED" }
            .map { it.code }.distinct().toMutableList()
        if (testMode) warnings += "TEST_MODE_SINGLE_RECORD"
        var verifiedRaw = 0
        var isolateHeartRateDays = false
        val verifiedRawIdentities = mutableSetOf<com.bettermifitness.sync.data.api.MiHealthImportIdentity>()
        var newlyUploadedRaw = 0
        var alreadyPresentRaw = 0
        var verifiedAggregates = 0
        var alreadyPresentDaily = 0
        var preservedDaily = 0
        var attemptedRaw = 0
        var attemptedDaily = 0
        var attemptedOwnedRevisions = 0
        var verifiedOwnedRevisions = 0
        var eligibleOwnedRevisions = 0
        var unownedConflicts = 0
        var changedOwnedBaselines = 0
        var blockedPending = 0
        var blockedConflict = 0
        var blockedAmbiguous = 0
        var blockedVerifiedHistory = 0
        // Latest successful readback classification, not a sum of retry polls.
        // Null means no successful readback classified this attempted target.
        val unresolvedReadbacks = linkedMapOf<String, MiHealthImportVerification?>()
        val blockedVerifications = linkedMapOf<String, MiHealthImportVerification>()
        val sleepConflicts = if (plan.metric == HealthInboundMetric.SLEEP) MiSleepConflictAccumulator(allowOwnedSleepRevision) else null
        var stage = MiHealthImportStage.PREPARE
        var currentChannel: MiHealthImportChannel? = null
        var batchIndex: Int? = null
        var batchRows: Int? = null
        var uploadResponse: MiHealthImportResponseSummary? = null
        var readResponse: MiHealthImportResponseSummary? = null
        val rawBatchLimit = if (testMode) 1 else MI_HEALTH_IMPORT_RAW_BATCH_SIZE
        val plannedRaw = rows.count { it.identity.channel == MiHealthImportChannel.RAW }
        val plannedDaily = rows.count { it.identity.channel == MiHealthImportChannel.DAILY_AGGREGATE }

        data class FailureContext(
            val stage: MiHealthImportStage,
            val channel: MiHealthImportChannel?,
            val batchIndex: Int?,
            val batchRows: Int?,
            val uploadResponse: MiHealthImportResponseSummary?,
            val readResponse: MiHealthImportResponseSummary?,
        )
        fun context() = FailureContext(stage, currentChannel, batchIndex, batchRows, uploadResponse, readResponse)
        fun restore(context: FailureContext) {
            stage = context.stage
            currentChannel = context.channel
            batchIndex = context.batchIndex
            batchRows = context.batchRows
            uploadResponse = context.uploadResponse
            readResponse = context.readResponse
        }
        fun clearRequestContext() {
            batchIndex = null
            batchRows = null
            uploadResponse = null
            readResponse = null
        }
        fun diagnostic(affected: Int = 0, reason: MiHealthImportFailureReason? = null) = MiHealthImportDiagnostic(
            metricKey = plan.metric.metricKey, stage = stage, channel = currentChannel,
            testMode = testMode, inputRecords = plan.inputRecordCount,
            sourceRawRows = plan.rawRows.size,
            plannedRawRows = plannedRaw, plannedDailyRows = plannedDaily,
            attemptedRawRows = attemptedRaw, attemptedDailyRows = attemptedDaily,
            verifiedRawRows = verifiedRaw, verifiedDailyRows = verifiedAggregates,
            affectedRows = affected, batchIndex = batchIndex, batchRows = batchRows,
            rawBatchLimit = rawBatchLimit, uploadResponse = uploadResponse, readResponse = readResponse,
            failureReason = reason?.name,
            alreadyPresentRawRows = alreadyPresentRaw,
            newlyVerifiedRawRows = newlyUploadedRaw,
            notAttemptedRawRows = (plannedRaw - attemptedRaw - alreadyPresentRaw).coerceAtLeast(0),
            notAttemptedDailyRows = (plannedDaily - attemptedDaily - alreadyPresentDaily - preservedDaily).coerceAtLeast(0),
            blockedPendingRows = blockedPending,
            blockedConflictRows = blockedConflict,
            blockedAmbiguousRows = blockedAmbiguous,
            blockedVerifiedHistoryRows = blockedVerifiedHistory,
            sourceCollisionRows = duplicateTargets.size,
            readbackMissingRows = unresolvedReadbacks.values.count { it?.status == MiHealthImportVerificationStatus.MISSING },
            readbackMismatchedRows = unresolvedReadbacks.values.count { it?.status == MiHealthImportVerificationStatus.MISMATCH },
            readbackAmbiguousRows = unresolvedReadbacks.values.count { it?.status == MiHealthImportVerificationStatus.AMBIGUOUS },
            readbackUnqueriedRows = unresolvedReadbacks.values.count { it == null },
            preservedDailyRows = preservedDaily,
            sourceRejectedRecords = sourceRejectedCount,
            issueCounts = summarizeMiHealthImportIssues(selectedIssues),
            precheckMismatchFields = summarizeMiHealthImportMismatchFields(blockedVerifications.values),
            readbackMismatchFields = summarizeMiHealthImportMismatchFields(unresolvedReadbacks.values.filterNotNull()),
            sleepConflict = sleepConflicts?.snapshot(),
            attemptedOwnedRevisionRows = attemptedOwnedRevisions,
            verifiedOwnedRevisionRows = verifiedOwnedRevisions,
            eligibleOwnedRevisionRows = eligibleOwnedRevisions,
            unownedConflictRows = unownedConflicts,
            changedOwnedBaselineRows = changedOwnedBaselines,
        )

        fun failure(reason: MiHealthImportFailureReason, affected: Int, details: String = "", cause: Throwable? = null) =
            MiHealthImportRunException(reason, verifiedRaw, affected, details, cause, diagnostic(affected, reason))

        suspend fun processChannel(channel: MiHealthImportChannel) {
            currentChannel = channel
            // Read each UTC day once before mutation, then verify all submitted
            // batches together. Avoid re-reading a full day for every HR point.
            val groups = rows.filter { it.identity.channel == channel && it.identity !in duplicateTargets }
                .filter { daily -> channel != MiHealthImportChannel.DAILY_AGGREGATE || !isolateHeartRateDays ||
                    (incompleteDayIssues.none { it.affectedLocalDays.isNullOrEmpty() ||
                        daily.identity.time.floorDiv(DAY_SECONDS) in it.affectedLocalDays } &&
                    rows.filter { raw -> raw.identity.channel == MiHealthImportChannel.RAW &&
                        (raw.identity.time + raw.zoneOffsetSeconds).floorDiv(DAY_SECONDS) * DAY_SECONDS == daily.identity.time
                    }.let { raw -> raw.isNotEmpty() && raw.all { it.identity in verifiedRawIdentities } }) }
                .groupBy { it.identity.key to it.identity.time.floorDiv(DAY_SECONDS) }
                .entries.sortedBy { it.key.second }
            val channelCollisions = duplicateTargets.count { it.channel == channel }
            var blockedReason: MiHealthImportFailureReason? = if (channelCollisions > 0) MiHealthImportFailureReason.CONFLICT else null
            var blockedCount = channelCollisions
            val blockedFields = linkedSetOf<String>()
            if (channelCollisions > 0) blockedFields += "source_target_collision"
            var deferredReadbackContext: FailureContext? = null
            for ((groupKey, dayRows) in groups) {
                val from = (groupKey.second * DAY_SECONDS - DAY_SECONDS).coerceAtLeast(0)
                val until = (groupKey.second + 1) * DAY_SECONDS + 2
                suspend fun read(): List<MiHealthImportCloudRow> = cloud.readRows(channel, groupKey.first, from, until)
                stage = MiHealthImportStage.PRECHECK
                clearRequestContext()
                val before = try { read() } catch (e: CancellationException) {
                    throw e
                } catch (e: MiApiException.AuthExpired) {
                    if (attemptedRaw > 0 || attemptedDaily > 0) {
                        readResponse = miHealthImportFailureSummary(e)
                        throw failure(MiHealthImportFailureReason.UNVERIFIED, dayRows.size, cause = e)
                    }
                    throw e // No POST yet: allow the existing shared session refresh.
                } catch (e: Exception) {
                    readResponse = miHealthImportFailureSummary(e)
                    throw failure(MiHealthImportFailureReason.UNVERIFIED, dayRows.size, cause = e)
                }
                val beforeIndex = MiHealthImportObservationIndex(before)
                val minuteIdentities = dayRows.filter { it.identity.channel == MiHealthImportChannel.RAW &&
                    it.identity.key == "spo2" && it.identity.time >= 0 && it.identity.time % 60 != 0L }
                    .associate { it.identity.stableKey to it.identity.copy(time = it.identity.time.floorDiv(60) * 60).stableKey }
                val pending = journal.pendingIdentities(account,
                    dayRows.map { it.identity.stableKey }.toSet() + minuteIdentities.values)
                val verifiedFingerprints = journal.verifiedFingerprints(account)
                val ownedFingerprints = journal.ownedVerifiedFingerprints(account)
                val legacyFingerprints = if (allowLegacySleepHrvEnrichment || sleepConflicts != null)
                    journal.legacyVerifiedFingerprints(account) else emptyMap()
                val existing = mutableListOf<MiHealthImportRow>()
                val aliasExisting = mutableListOf<MiHealthImportRow>()
                val toUpload = mutableListOf<MiHealthImportRow>()
                val enrichments = mutableMapOf<String, PreparedSleepHrvEnrichment>()
                val sleepRevisions = mutableMapOf<String, PreparedOwnedSleepRevision>()
                val heartRevisions = mutableMapOf<String, PreparedOwnedHeartRateRevision>()
                for (row in dayRows.sortedBy { it.identity.time }) {
                    val status = beforeIndex.verify(row)
                    val digest = HealthImportLedger.identityDigest(row.identity.stableKey)
                    val sourceBlocker = sleepRevisionSourceBlocker(row, selectedIssues)
                    if (channel == MiHealthImportChannel.RAW) sleepConflicts?.observe(row, before,
                        verifiedFingerprints[digest], ownedFingerprints[digest], legacyFingerprints[digest],
                        row.identity.stableKey in pending, sourceBlocker)
                    if (status.status == MiHealthImportVerificationStatus.VERIFIED) {
                        existing += row
                        continue
                    }
                    val minuteMatch = if (allowMinuteMatch && status.status == MiHealthImportVerificationStatus.MISSING)
                        minutePolicy.find(row, before) else null
                    if (minuteMatch != null) {
                        val fingerprint = HealthRecordIds.version(canonicalMiHealthImportJson(minuteMatch.value)).toString()
                        val committed = try {
                            if (row.identity.stableKey in pending) {
                                val snapshot = journal.pendingSnapshots(account, setOf(row.identity.stableKey))[row.identity.stableKey]
                                if (snapshot == null) emptySet() else journal.reconcileObservedPending(account,
                                    listOf(HealthImportPendingReconciliation(
                                        snapshot, journalEntry(row), fingerprint, minuteMatch.importIdentity().stableKey)))
                            } else journal.recordObservedAliases(account, listOf(HealthImportObservedAlias(
                                journalEntry(row), minuteMatch.importIdentity().stableKey, fingerprint)))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { throw failure(MiHealthImportFailureReason.UNVERIFIED, 1, cause = e) }
                        if (row.identity.stableKey in committed) aliasExisting += row else {
                            blockedReason = blockedReason ?: MiHealthImportFailureReason.UNVERIFIED
                            blockedCount++
                            blockedVerifiedHistory++
                        }
                        continue
                    }
                    val dailyAlreadyExists = channel == MiHealthImportChannel.DAILY_AGGREGATE && before.any {
                        !it.deleted && it.key == row.identity.key && it.tag == row.identity.tag &&
                            sameOrUncertainLocalDay(row, it)
                    }
                    if (dailyAlreadyExists) {
                        warnings += "EXISTING_DAILY_REPORT_PRESERVED"
                        preservedDaily++
                        continue
                    }
                    if (row.identity.stableKey in pending) {
                        blockedReason = blockedReason ?: MiHealthImportFailureReason.PENDING
                        blockedCount++
                        blockedPending++
                        blockedVerifications[row.identity.stableKey] = status
                        blockedFields += status.mismatchedFields
                    } else if (status.status != MiHealthImportVerificationStatus.MISSING) {
                        val enrichment = prepareSleepHrvEnrichment(row, before, ownedFingerprints[digest],
                            legacyFingerprints[digest], allowLegacySleepHrvEnrichment)
                        val revision = if (plan.metric == HealthInboundMetric.SLEEP && sourceBlocker == null)
                            prepareOwnedSleepRevision(row, before, ownedFingerprints[digest],
                                userRequested = allowOwnedSleepRevision, sourceTimezoneReliable = true) else null
                        val heartRevision = if (plan.metric in setOf(HealthInboundMetric.HEART_RATE,
                                HealthInboundMetric.RESTING_HEART_RATE) && rejected.isEmpty())
                            prepareOwnedHeartRateRevision(row, beforeIndex.atTarget(row), ownedFingerprints[digest]) else null
                        val hrvRevision = if (plan.metric == HealthInboundMetric.HRV && rejected.isEmpty())
                            prepareOwnedHrvSummaryRevision(row, beforeIndex.atTarget(row), ownedFingerprints[digest]) else null
                        if (enrichment != null) {
                            toUpload += enrichment.row
                            enrichments[row.identity.stableKey] = enrichment
                        } else if (revision != null) {
                            toUpload += revision.row
                            sleepRevisions[row.identity.stableKey] = revision
                        } else if (heartRevision != null) {
                            toUpload += heartRevision.row
                            heartRevisions[row.identity.stableKey] = heartRevision
                            eligibleOwnedRevisions++
                        } else if (hrvRevision != null) {
                            toUpload += hrvRevision.row
                            enrichments[row.identity.stableKey] = hrvRevision
                            eligibleOwnedRevisions++
                        } else {
                            blockedReason = blockedReason ?: MiHealthImportFailureReason.CONFLICT
                            blockedCount++
                            if (status.status == MiHealthImportVerificationStatus.AMBIGUOUS) blockedAmbiguous++ else blockedConflict++
                            blockedVerifications[row.identity.stableKey] = status
                            blockedFields += status.mismatchedFields
                            if (plan.metric in setOf(HealthInboundMetric.HEART_RATE, HealthInboundMetric.RESTING_HEART_RATE,
                                    HealthInboundMetric.HRV)) {
                                if (ownedFingerprints[digest] == null) unownedConflicts++
                                else if (beforeIndex.atTarget(row).none {
                                    HealthRecordIds.version(canonicalMiHealthImportJson(it.value)).toString() == ownedFingerprints[digest]
                                }) changedOwnedBaselines++
                            }
                        }
                    } else if (channel == MiHealthImportChannel.RAW &&
                        HealthImportLedger.identityDigest(row.identity.stableKey) in verifiedFingerprints) {
                        // A successful earlier read (including the native history
                        // fallback) is not undone by a later query omission. A
                        // server deletion or changed source value must not cause
                        // an automatic second write to the same target either.
                        blockedReason = blockedReason ?: MiHealthImportFailureReason.UNVERIFIED
                        blockedCount++
                        blockedVerifiedHistory++
                    } else if (minuteIdentities[row.identity.stableKey]?.let { alias ->
                        alias in pending || HealthImportLedger.identityDigest(alias) in verifiedFingerprints
                    } == true) {
                        // A prior write to the destination minute still protects it
                        // when a later query omits that cloud row or the source window changes.
                        blockedReason = blockedReason ?: MiHealthImportFailureReason.UNVERIFIED
                        blockedCount++
                        blockedVerifiedHistory++
                    } else if (row.identity in minutePolicy.collisions || minutePolicy.hasMinuteObservation(row, before)) {
                        // Two source seconds in one destination minute may overwrite
                        // each other remotely; never discover this only after POST.
                        blockedReason = blockedReason ?: MiHealthImportFailureReason.CONFLICT
                        blockedCount++
                        blockedConflict++
                    } else {
                        toUpload += row
                    }
                }
                if (existing.isNotEmpty()) journal.markVerified(account, existing.map { verifiedEntry(it, beforeIndex.atTarget(it)) })
                if (channel == MiHealthImportChannel.RAW) {
                    verifiedRawIdentities += (existing + aliasExisting).map { it.identity }
                    verifiedRaw += existing.size + aliasExisting.size
                    alreadyPresentRaw += existing.size + aliasExisting.size
                } else {
                    verifiedAggregates += existing.size
                    alreadyPresentDaily += existing.size
                }

                val attempted = mutableListOf<MiHealthImportRow>()
                val submittedPending = mutableMapOf<String, com.bettermifitness.sync.data.preferences.HealthImportPendingSnapshot>()
                val ownershipEligible = mutableSetOf<String>()
                var uploadFailure: Throwable? = null
                var explicitlyRejected = false
                var stoppedPreflightReason: MiHealthImportFailureReason? = null
                var stoppedPreflightCause: Throwable? = null
                var stoppedPreflightResponse: MiHealthImportResponseSummary? = null
                var stoppedPreflightDetail = "HRV_ENRICHMENT_CHANGED"
                val batchSize = if (channel == MiHealthImportChannel.RAW) rawBatchLimit else MI_HEALTH_IMPORT_AGGREGATE_BATCH_SIZE
                for ((index, batch) in toUpload.chunked(batchSize).withIndex()) {
                    val batchEnrichments = batch.mapNotNull { enrichments[it.identity.stableKey] }
                    val batchRevisions = batch.mapNotNull { sleepRevisions[it.identity.stableKey] }
                    val batchHeartRevisions = batch.mapNotNull { heartRevisions[it.identity.stableKey] }
                    val batchHrvRevisions = batchEnrichments.filter { it.replacesOwnedSummary }
                    if (batchEnrichments.isNotEmpty() || batchRevisions.isNotEmpty() || batchHeartRevisions.isNotEmpty()) {
                        val updateCount = batchEnrichments.size + batchRevisions.size + batchHeartRevisions.size
                        stage = MiHealthImportStage.PRECHECK
                        val justBeforeWrite = try { read() } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            readResponse = miHealthImportFailureSummary(e)
                            if (attempted.isEmpty()) throw failure(MiHealthImportFailureReason.UNVERIFIED, updateCount, cause = e)
                            stoppedPreflightReason = MiHealthImportFailureReason.UNVERIFIED
                            stoppedPreflightCause = e
                            stoppedPreflightResponse = readResponse
                            break // First verify any preceding batches that were already submitted.
                        }
                        if (batchEnrichments.any { !it.stillMatches(justBeforeWrite) }) {
                            blockedConflict += batchEnrichments.size
                            if (attempted.isEmpty())
                                throw failure(MiHealthImportFailureReason.CONFLICT, batchEnrichments.size, "HRV_ENRICHMENT_CHANGED")
                            stoppedPreflightReason = MiHealthImportFailureReason.CONFLICT
                            break
                        }
                        if (batchHrvRevisions.isNotEmpty()) {
                            val currentOwned = journal.ownedVerifiedFingerprints(account)
                            val currentPending = journal.pendingIdentities(account,
                                batchHrvRevisions.map { it.row.identity.stableKey }.toSet())
                            if (batchHrvRevisions.any { it.row.identity.stableKey in currentPending ||
                                    currentOwned[HealthImportLedger.identityDigest(it.row.identity.stableKey)] !=
                                    it.previousFingerprint }) {
                                blockedConflict += batchHrvRevisions.size
                                if (attempted.isEmpty()) throw failure(MiHealthImportFailureReason.CONFLICT,
                                    batchHrvRevisions.size, "HRV_ENRICHMENT_CHANGED")
                                stoppedPreflightReason = MiHealthImportFailureReason.CONFLICT
                                break
                            }
                        }
                        if (batchEnrichments.any { it.usedLegacyPermission }) warnings += "LEGACY_SLEEP_HRV_ENRICHMENT"
                        if (batchRevisions.isNotEmpty()) {
                            // Check durable provenance again after the last GET. A newly pending or
                            // downgraded target cannot inherit the earlier preparation's permission.
                            val currentOwned = journal.ownedVerifiedFingerprints(account)
                            val currentPending = journal.pendingIdentities(account,
                                batchRevisions.map { it.row.identity.stableKey }.toSet())
                            val changed = batchRevisions.any {
                                !it.stillMatches(justBeforeWrite) || it.row.identity.stableKey in currentPending ||
                                    currentOwned[HealthImportLedger.identityDigest(it.row.identity.stableKey)] != it.previousFingerprint
                            }
                            if (changed) {
                                blockedConflict += batchRevisions.size
                                stoppedPreflightDetail = "SLEEP_REVISION_CHANGED"
                                if (attempted.isEmpty())
                                    throw failure(MiHealthImportFailureReason.CONFLICT, batchRevisions.size, stoppedPreflightDetail)
                                stoppedPreflightReason = MiHealthImportFailureReason.CONFLICT
                                break
                            }
                        }
                        if (batchHeartRevisions.isNotEmpty()) {
                            val currentOwned = journal.ownedVerifiedFingerprints(account)
                            val currentPending = journal.pendingIdentities(account,
                                batchHeartRevisions.map { it.row.identity.stableKey }.toSet())
                            val currentIndex = MiHealthImportObservationIndex(justBeforeWrite)
                            val changed = batchHeartRevisions.any { revision ->
                                !revision.stillMatches(currentIndex.atTarget(revision.row)) ||
                                    revision.row.identity.stableKey in currentPending ||
                                    currentOwned[HealthImportLedger.identityDigest(revision.row.identity.stableKey)] !=
                                    revision.previousFingerprint
                            }
                            if (changed) {
                                blockedConflict += batchHeartRevisions.size
                                stoppedPreflightDetail = "HEART_RATE_REVISION_CHANGED"
                                if (attempted.isEmpty()) throw failure(MiHealthImportFailureReason.CONFLICT,
                                    batchHeartRevisions.size, stoppedPreflightDetail)
                                stoppedPreflightReason = MiHealthImportFailureReason.CONFLICT
                                break
                            }
                        }
                    }
                    journal.markPending(account, batch.map(::journalEntry))
                    submittedPending += journal.pendingSnapshots(account, batch.map { it.identity.stableKey }.toSet())
                    attempted += batch
                    attemptedOwnedRevisions += batchHeartRevisions.size + batchHrvRevisions.size
                    if (batchRevisions.isNotEmpty()) {
                        sleepConflicts?.let { it.attemptedRevisionRows += batchRevisions.size }
                        warnings += "OWNED_SLEEP_REVISION"
                    }
                    batch.forEach { unresolvedReadbacks[it.identity.stableKey] = null }
                    if (channel == MiHealthImportChannel.RAW) attemptedRaw += batch.size else attemptedDaily += batch.size
                    stage = MiHealthImportStage.UPLOAD
                    batchIndex = index + 1
                    batchRows = batch.size
                    try {
                        val result = cloud.uploadRows(batch, experimentalEnabled = true)
                        uploadResponse = result.responseSummary()
                        if (!result.accepted) {
                            explicitlyRejected = true
                            break
                        }
                        ownershipEligible += batch.map { it.identity.stableKey }
                    } catch (e: CancellationException) {
                        throw e // Pending entries deliberately survive cancellation.
                    } catch (e: Exception) {
                        uploadFailure = e
                        uploadResponse = miHealthImportFailureSummary(e)
                        // The request may have applied despite losing its response.
                        // An explicit business rejection above never grants ownership.
                        ownershipEligible += batch.map { it.identity.stableKey }
                        break // May have applied: read back before considering any retry.
                    }
                }
                if (attempted.isNotEmpty()) {
                    var remaining = attempted.toList()
                    val postObservations = linkedSetOf<MiHealthImportCloudRow>()
                    var readFailure: Throwable? = null
                    repeat(READBACK_ATTEMPTS) { attempt ->
                        if (remaining.isEmpty()) return@repeat
                        if (attempt > 0) readbackPause(READBACK_DELAYS_MILLIS[attempt - 1])
                        stage = MiHealthImportStage.READBACK
                        val observed = try { read() } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            readFailure = e
                            readResponse = miHealthImportFailureSummary(e)
                            return@repeat
                        }
                        readFailure = null
                        readResponse = null
                        postObservations += observed
                        val observedIndex = MiHealthImportObservationIndex(observed)
                        val confirmed = mutableListOf<MiHealthImportRow>()
                        val minuteConfirmed = mutableListOf<MiHealthImportRow>()
                        for (row in remaining) {
                            val verification = observedIndex.verify(row)
                            if (verification.status == MiHealthImportVerificationStatus.VERIFIED) {
                                confirmed += row
                            } else {
                                val minuteMatch = if (allowMinuteMatch && verification.status == MiHealthImportVerificationStatus.MISSING)
                                    minutePolicy.find(row, postObservations.toList()) else null
                                val snapshot = if (minuteMatch != null) submittedPending[row.identity.stableKey] else null
                                val reconciled = if (minuteMatch != null && snapshot != null) try {
                                    row.identity.stableKey in journal.reconcileObservedPending(
                                        account, listOf(HealthImportPendingReconciliation(snapshot,
                                        journalEntry(row), HealthRecordIds.version(canonicalMiHealthImportJson(minuteMatch.value))
                                            .toString(),
                                        minuteMatch.importIdentity().stableKey)))
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { throw failure(MiHealthImportFailureReason.UNVERIFIED, remaining.size, cause = e) }
                                else false
                                if (reconciled) {
                                    minuteConfirmed += row
                                } else unresolvedReadbacks[row.identity.stableKey] = verification
                            }
                        }
                        if (confirmed.isNotEmpty() || minuteConfirmed.isNotEmpty()) {
                            journal.markVerified(account, confirmed.map {
                                verifiedEntry(it, observedIndex.atTarget(it), ownedUpload = it.identity.stableKey in ownershipEligible)
                            })
                            sleepConflicts?.let { report ->
                                report.verifiedRevisionRows += confirmed.count { it.identity.stableKey in sleepRevisions }
                            }
                            verifiedOwnedRevisions += confirmed.count { it.identity.stableKey in heartRevisions }
                            verifiedOwnedRevisions += confirmed.count { enrichments[it.identity.stableKey]?.replacesOwnedSummary == true }
                            if (channel == MiHealthImportChannel.RAW) {
                                verifiedRawIdentities += (confirmed + minuteConfirmed).map { it.identity }
                                verifiedRaw += confirmed.size + minuteConfirmed.size
                                newlyUploadedRaw += confirmed.size + minuteConfirmed.size
                            } else verifiedAggregates += confirmed.size
                            val confirmedIds = (confirmed + minuteConfirmed).map { it.identity }.toSet()
                            (confirmed + minuteConfirmed).forEach { unresolvedReadbacks.remove(it.identity.stableKey) }
                            remaining = remaining.filterNot { it.identity in confirmedIds }
                        }
                    }
                    if (remaining.isNotEmpty()) {
                        if (explicitlyRejected) stage = MiHealthImportStage.UPLOAD
                        // A healthy, accepted POST followed by readable but still
                        // unmatched data quarantines just these target rows. It
                        // must not prevent new, independent days from progressing.
                        // Never repeat a POST or release its pending lock here.
                        if (!explicitlyRejected && uploadFailure == null && readFailure == null && stoppedPreflightReason == null) {
                            deferredReadbackContext = context()
                            continue
                        }
                        throw failure(
                            if (explicitlyRejected) MiHealthImportFailureReason.REJECTED else MiHealthImportFailureReason.UNVERIFIED,
                            remaining.size,
                            cause = uploadFailure ?: readFailure,
                        )
                    }
                    // A transport error after some batches means later batches
                    // were not attempted. Verified applied data is kept; report
                    // the unfinished remainder instead of claiming completion.
                    val notAttempted = toUpload.size - attempted.size
                    if (notAttempted > 0) {
                        stoppedPreflightReason?.let { reason ->
                            stage = MiHealthImportStage.PRECHECK
                            batchIndex = null
                            batchRows = null
                            uploadResponse = null
                            readResponse = stoppedPreflightResponse
                            throw failure(reason, notAttempted,
                                if (reason == MiHealthImportFailureReason.CONFLICT) stoppedPreflightDetail
                                    else "remaining_batches_not_submitted",
                                stoppedPreflightCause)
                        }
                        stage = MiHealthImportStage.UPLOAD
                        throw failure(MiHealthImportFailureReason.UNVERIFIED,
                            notAttempted, "remaining_batches_not_submitted", uploadFailure)
                    }
                }
            }
            if (unresolvedReadbacks.isNotEmpty()) {
                deferredReadbackContext?.let(::restore)
                throw failure(MiHealthImportFailureReason.UNVERIFIED, unresolvedReadbacks.size + blockedCount,
                    blockedFields.joinToString())
            }
            if (blockedCount > 0) {
                stage = if (blockedCount == channelCollisions) MiHealthImportStage.PREPARE else MiHealthImportStage.PRECHECK
                clearRequestContext()
                throw failure(blockedReason!!, blockedCount, blockedFields.joinToString())
            }
        }

        var deferredRawFailure: MiHealthImportRunException? = null
        var deferredRawContext: FailureContext? = null
        try { processChannel(MiHealthImportChannel.RAW) }
        catch (e: MiHealthImportRunException) {
            if (plan.metric != HealthInboundMetric.HEART_RATE || stage != MiHealthImportStage.PRECHECK ||
                e.reason !in setOf(MiHealthImportFailureReason.PENDING, MiHealthImportFailureReason.CONFLICT)) throw e
            deferredRawFailure = e
            deferredRawContext = context()
        }
        if (rejected.isNotEmpty() && plan.metric != HealthInboundMetric.HEART_RATE) {
            stage = MiHealthImportStage.PREPARE
            clearRequestContext()
            throw failure(MiHealthImportFailureReason.SOURCE_REJECTED, sourceRejectedCount,
                rejected.map { it.code }.distinct().joinToString())
        }
        // Daily summaries only follow fully verified source rows. A partial raw
        // import cannot overwrite the app's day-level representation.
        isolateHeartRateDays = plan.metric == HealthInboundMetric.HEART_RATE &&
            (deferredRawFailure != null || incompleteDayIssues.isNotEmpty())
        processChannel(MiHealthImportChannel.DAILY_AGGREGATE)
        deferredRawFailure?.let {
            restore(deferredRawContext!!)
            throw failure(it.reason, it.affectedCount, it.details, it.cause)
        }
        if (rejected.isNotEmpty()) {
            stage = MiHealthImportStage.PREPARE
            clearRequestContext()
            throw failure(MiHealthImportFailureReason.SOURCE_REJECTED, sourceRejectedCount,
                rejected.map { it.code }.distinct().joinToString())
        }
        stage = MiHealthImportStage.COMPLETE
        currentChannel = null
        clearRequestContext()
        return MiHealthImportRunResult(verifiedRaw, newlyUploadedRaw, alreadyPresentRaw, verifiedAggregates, warnings.distinct(),
            diagnostic())
    }

    companion object {
        private const val DAY_SECONDS = 86_400L
        private const val READBACK_ATTEMPTS = 3
        private val READBACK_DELAYS_MILLIS = longArrayOf(1_000L, 3_000L)
        fun contentFingerprint(row: MiHealthImportRow): String = HealthRecordIds.version(row.canonicalContent).toString()
        fun journalEntry(row: MiHealthImportRow): HealthImportJournalEntry = HealthImportJournalEntry(
            row.identity.stableKey, contentFingerprint(row), row.sourceRecordKeys,
            zoneOffsetSeconds = row.zoneOffsetSeconds,
        )

        private fun verifiedEntry(row: MiHealthImportRow, observed: List<MiHealthImportCloudRow>,
            ownedUpload: Boolean = false): HealthImportJournalEntry {
            val actual = observed.first { !it.deleted && it.sid == row.identity.sid &&
                it.key == row.identity.key && it.time == row.identity.time &&
                (row.identity.channel == MiHealthImportChannel.RAW || it.tag == row.identity.tag) }
            // Save the exact observed content, including server-added fields.
            // Future export filtering can then match cloud rows without guessing.
            return journalEntry(row).copy(
                contentFingerprint = HealthRecordIds.version(canonicalMiHealthImportJson(actual.value)).toString(),
                ownedUpload = ownedUpload)
        }

        private fun sameOrUncertainLocalDay(expected: MiHealthImportRow, observed: MiHealthImportCloudRow): Boolean {
            if (expected.identity.time == observed.time) return true
            val expectedDay = (expected.identity.time + expected.zoneOffsetSeconds).floorDiv(DAY_SECONDS)
            return observed.zoneOffsetSeconds?.let { offset ->
                (observed.time + offset).floorDiv(DAY_SECONDS) == expectedDay
            } ?: (kotlin.math.abs(observed.time - expected.identity.time) <= 18 * 3600L)
        }
    }
}
