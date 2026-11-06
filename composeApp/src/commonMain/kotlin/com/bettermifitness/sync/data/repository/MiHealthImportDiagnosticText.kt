package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.api.MiHealthImportIssueSeverity
import com.bettermifitness.sync.data.api.MiHealthImportResponseSummary
import com.bettermifitness.sync.data.api.MiHealthImportStage
import com.bettermifitness.sync.data.api.safeMiHealthImportFailureReason
import com.bettermifitness.sync.data.api.safeMiHealthImportIssueCode
import com.bettermifitness.sync.data.api.safeMiHealthImportMismatchField
import com.bettermifitness.sync.data.api.safeMiHealthImportMessage
import com.bettermifitness.sync.data.api.safeMiPendingRecoveryStatus
import com.bettermifitness.sync.data.api.safeMiPendingRecoveryOutcome
import com.bettermifitness.sync.data.api.safeMiPendingReconcileStatus
import com.bettermifitness.sync.i18n.L10n

/** Human errors distinguish affected record counts from Xiaomi's actual business code. */
fun MiHealthImportRunException.userMessage(): String = buildList {
    if (verifiedRawCount > 0 || diagnostic?.hasVerifiedRows == true) add(L10n.text(L10n.importPartialProgress))
    val headline = when {
        diagnostic?.stage == MiHealthImportStage.PRECHECK && reason == MiHealthImportFailureReason.UNVERIFIED -> L10n.importPrecheckFailed
        else -> when (reason) {
            MiHealthImportFailureReason.CONFLICT -> L10n.importCloudConflict
            MiHealthImportFailureReason.PENDING -> L10n.importPendingVerification
            MiHealthImportFailureReason.REJECTED -> L10n.importCloudRejected
            MiHealthImportFailureReason.UNVERIFIED -> L10n.importCloudUnverified
            MiHealthImportFailureReason.SOURCE_REJECTED -> L10n.importRowsRejected
        }
    }
    add(L10n.text(headline))
    add(L10n.textFmt(L10n.importAffectedRecords, affectedCount))
    diagnostic?.let { d ->
        val response = if (d.stage == MiHealthImportStage.PRECHECK) d.readResponse else d.uploadResponse ?: d.readResponse
        response?.businessCode?.let { add(L10n.textFmt(L10n.importServerCode, it)) }
        response?.httpStatus?.let { add(L10n.textFmt(L10n.importHttpStatus, it)) }
        response?.accepted?.let { add(L10n.text(if (it) L10n.importBusinessAccepted else L10n.importBusinessRejected)) }
        response?.safeMessage?.let { safeMiHealthImportMessage(it).first }?.let {
            add(L10n.textFmt(L10n.importServerMessage, it))
        }
    }
    if (verifiedRawCount > 0) add(L10n.textFmt(L10n.syncImportRecords, verifiedRawCount))
    if (details.isNotBlank()) {
        val knownDetails = details.split(',').map(::safeMiHealthImportIssueCode).filter { it != "UNKNOWN" }.distinct()
        if (knownDetails.isNotEmpty()) add(knownDetails.joinToString("; ") { L10n.importWarning(it) })
    }
}.joinToString("\n")

fun MiHealthImportDiagnostic.userFacingDetails(): String = buildList {
    if (excludedSourcePoints > 0) add(L10n.textFmt(L10n.importExcludedPoints, verifiedRawRows, excludedSourcePoints))
    if (excludedHrvSamples > 0) add(L10n.textFmt(L10n.importHrvExcludedSamples, verifiedRawRows, excludedHrvSamples))
    if (verifiedOwnedRevisionRows > 0) add(L10n.textFmt(when (metricKey) {
        "hrv" -> L10n.importOwnedHrvRevision
        "resting_heart_rate" -> L10n.importOwnedRhrRevision
        else -> L10n.importOwnedHeartRateRevision
    }, verifiedOwnedRevisionRows))
    if (testMode && pendingRecovery == null && pendingReconcile == null) add(L10n.text(L10n.settingsInboundTestModeDetail))
    val stageLabel = L10n.text(when (stage) {
        MiHealthImportStage.PREPARE -> L10n.importStagePrepare
        MiHealthImportStage.PRECHECK -> L10n.importStagePrecheck
        MiHealthImportStage.UPLOAD -> L10n.importStageUpload
        MiHealthImportStage.READBACK -> L10n.importStageReadback
        MiHealthImportStage.COMPLETE -> L10n.importStageComplete
    })
    val channelLabel = L10n.text(when (channel) {
        MiHealthImportChannel.RAW -> L10n.importChannelRaw
        MiHealthImportChannel.DAILY_AGGREGATE -> L10n.importChannelDaily
        null -> L10n.importChannelAll
    })
    add(L10n.textFmt(L10n.importDiagnosticStage, stageLabel, channelLabel))
    if (batchIndex != null && batchRows != null) add(L10n.textFmt(L10n.importDiagnosticBatch, batchIndex, batchRows))
    add(L10n.textFmt(L10n.importDiagnosticCounts, plannedRawRows, attemptedRawRows, verifiedRawRows,
        plannedDailyRows, attemptedDailyRows, verifiedDailyRows))
    add(L10n.textFmt(L10n.importDiagnosticRawProgress, alreadyPresentRawRows, newlyVerifiedRawRows,
        notAttemptedRawRows))
    failureReason?.let { add(L10n.textFmt(L10n.importDiagnosticReason, failureReasonLabel(it))) }
    if (blockedPendingRows + blockedConflictRows + blockedAmbiguousRows + sourceCollisionRows > 0) {
        add(L10n.textFmt(L10n.importDiagnosticBlocked, blockedPendingRows, blockedConflictRows,
            blockedAmbiguousRows, sourceCollisionRows))
    }
    if (blockedVerifiedHistoryRows > 0) {
        add(L10n.textFmt(L10n.importBlockedVerifiedHistory, blockedVerifiedHistoryRows))
    }
    sleepConflict?.let {
        if (it.eligibleRevisionRows > 0 && !it.revisionRequested) {
            add(L10n.textFmt(L10n.importSleepRevisionAvailable, it.eligibleRevisionRows))
        }
        if (it.attemptedRevisionRows > 0) {
            add(L10n.textFmt(L10n.importSleepRevisionResult, it.attemptedRevisionRows, it.verifiedRevisionRows))
        }
    }
    if (readbackMissingRows + readbackMismatchedRows + readbackAmbiguousRows + readbackUnqueriedRows > 0) {
        add(L10n.textFmt(L10n.importDiagnosticReadback, readbackMissingRows, readbackMismatchedRows,
            readbackAmbiguousRows, readbackUnqueriedRows))
    }
    if (precheckMismatchFields.isNotEmpty()) add(L10n.textFmt(L10n.importDiagnosticPrecheckFields,
        safeMismatchCounts(precheckMismatchFields).entries.joinToString { "${it.key}: ${it.value}" }))
    if (readbackMismatchFields.isNotEmpty()) add(L10n.textFmt(L10n.importDiagnosticReadbackFields,
        safeMismatchCounts(readbackMismatchFields).entries.joinToString { "${it.key}: ${it.value}" }))
    if (preservedDailyRows > 0) add(L10n.textFmt(L10n.importDiagnosticPreservedDaily, preservedDailyRows))
    if (sourceRejectedRecords > 0) add(L10n.textFmt(L10n.importDiagnosticRejectedSource, sourceRejectedRecords))
    for (issue in issueCounts.filter { it.code != "HEART_RATE_SOURCE_POINT_EXCLUDED" }) {
        val code = safeMiHealthImportIssueCode(issue.code)
        val label = if (code == "UNKNOWN") L10n.text(L10n.importIssueUnknown) else L10n.importWarning(code)
        val severity = L10n.text(if (issue.severity == MiHealthImportIssueSeverity.REJECTED)
            L10n.importIssueRejected else L10n.importIssueWarning)
        add(L10n.textFmt(L10n.importDiagnosticIssue, severity, label, issue.occurrences, issue.sourceRecordCount))
    }
    if (issueCounts.any { it.code != "HEART_RATE_SOURCE_POINT_EXCLUDED" }) add(L10n.text(L10n.importDiagnosticIssueOverlap))
    if (affectedRows > 0) add(L10n.textFmt(L10n.importAffectedRecords, affectedRows))
    if (rhrDailyMatchedDays != null && rhrDailyMissingDays != null && rhrDailyConflictingDays != null) {
        add(L10n.textFmt(L10n.rhrDisplayCheckDetail, rhrDailyMatchedDays, rhrDailyMissingDays, rhrDailyConflictingDays))
    }
    pendingProbe?.let { p ->
        add(L10n.textFmt(L10n.pendingProbeSummary, p.pendingVerified, p.pendingMissing,
            p.pendingMismatch, p.pendingAmbiguous, p.pendingUnqueried))
        add(L10n.textFmt(L10n.pendingProbeNarrowSummary, p.probeSelected, p.probeFound,
            p.probeMissing, p.probeMismatch, p.probeAmbiguous, p.probeUnqueried))
        if (p.nativeSelected > 0) add(L10n.textFmt(L10n.pendingProbeNativeSummary,
            p.nativeSelected, p.nativeFound, p.nativeMissing, p.nativeMismatch, p.nativeAmbiguous,
            p.nativeUnqueried, p.nativePagesRead, p.nativeCompleteQueries))
        if (p.missingWithOtherSourceRows + p.missingWithDeletedRows > 0)
            add(L10n.textFmt(L10n.pendingProbeOtherSources, p.missingWithOtherSourceRows, p.missingWithDeletedRows))
    }
    if (pendingProbeFailed) add(L10n.text(L10n.pendingProbeFailed))
    pendingRecovery?.let { r ->
        add(L10n.pendingRecoveryStatus(safeMiPendingRecoveryStatus(r.status)))
        add(L10n.textFmt(L10n.pendingRecoverySummary, r.candidateRows, attemptedRawRows, verifiedRawRows))
        if (r.legacyWireMetadataUnknown) add(L10n.text(L10n.pendingRecoveryLegacy))
        r.previousAttempt?.let { add(it.userFacingDetails()) }
    }
    pendingReconcile?.let { r ->
        add(L10n.pendingReconcileStatus(safeMiPendingReconcileStatus(r.status)))
        r.oldestRecoveryAttempt?.let { add(it.userFacingDetails()) }
        add(L10n.text(L10n.pendingReconcileCloudOnly))
        add(L10n.textFmt(L10n.pendingReconcileSummary, r.candidateRows, r.reconciledRows, r.unresolvedRows))
        if (r.sourceChangedRows + r.sourceCollisionRows + r.cloudConflictRows + r.deletedRows +
            r.otherSourceRows + r.missingRows > 0) {
            add(L10n.textFmt(L10n.pendingReconcileBlocked, r.sourceChangedRows, r.sourceCollisionRows,
                r.cloudConflictRows, r.deletedRows, r.otherSourceRows, r.missingRows))
        }
        if (r.unqueriedRows + r.journalChangedRows + r.persistenceFailedRows > 0) {
            add(L10n.textFmt(L10n.pendingReconcileUnresolved, r.unqueriedRows, r.journalChangedRows,
                r.persistenceFailedRows))
        }
        if (r.historySelectedRows > 0) {
            add(L10n.textFmt(L10n.pendingReconcileHistory, r.historySelectedRows, r.historyPagesRead))
            if (r.historyComplete == false) add(L10n.text(L10n.pendingReconcileHistoryIncomplete))
        }
        r.readComparison?.let { add(it.userFacingDetails()) }
    }
    for (response in listOfNotNull(uploadResponse, readResponse)) {
        response.businessCode?.let { add(L10n.textFmt(L10n.importServerCode, it)) }
        response.httpStatus?.let { add(L10n.textFmt(L10n.importHttpStatus, it)) }
        response.accepted?.let { add(L10n.text(if (it) L10n.importBusinessAccepted else L10n.importBusinessRejected)) }
        if (response.uploadResultShape != null && response.accepted == null) add(L10n.text(L10n.importAcceptanceUnknown))
        val safe = safeMiHealthImportMessage(response.safeMessage)
        safe.first?.let { add(L10n.textFmt(L10n.importServerMessage, it)) }
        if (response.messageWithheld || safe.second) add(L10n.text(L10n.importMessageWithheld))
    }
}.distinct().joinToString("\n")

/** An explicit, stable allowlist for support. No exception text or arbitrary JSON is serialized. */
fun MiHealthImportDiagnostic.copyableText(appVersion: String): String = buildList {
    add("Mi Health Sync Lab import diagnostic v2")
    add("app_version=" + appVersion.filter { it.isLetterOrDigit() || it in ".-_ ()" }.take(80))
    add("metric=" + metricKey.takeIf { it in DIAGNOSTIC_METRICS }.orEmpty().ifBlank { "unknown" })
    add("test_mode=$testMode")
    add("stage=${stage.name}")
    add("outcome=" + when {
        failureReason == null && stage == MiHealthImportStage.COMPLETE &&
            (excludedSourcePoints > 0 || excludedHrvSamples > 0) -> "COMPLETE_WITH_EXCLUSIONS"
        failureReason == null && stage == MiHealthImportStage.COMPLETE -> "COMPLETE"
        hasVerifiedRows -> "PARTIAL"
        else -> "INCOMPLETE"
    })
    failureReason?.let { add("failure_reason=" + safeMiHealthImportFailureReason(it)) }
    add("channel=${channel?.name ?: "ALL"}")
    add("input_records=$inputRecords")
    add("source_raw_rows=$sourceRawRows")
    add("excluded_source_points=$excludedSourcePoints")
    add("planned_raw_rows=$plannedRawRows")
    add("planned_daily_rows=$plannedDailyRows")
    add("attempted_raw_rows=$attemptedRawRows")
    add("attempted_daily_rows=$attemptedDailyRows")
    add("verified_raw_rows=$verifiedRawRows")
    add("verified_daily_rows=$verifiedDailyRows")
    add("already_present_raw_rows=$alreadyPresentRawRows")
    add("newly_verified_raw_rows=$newlyVerifiedRawRows")
    add("not_attempted_raw_rows=$notAttemptedRawRows")
    add("not_attempted_daily_rows=$notAttemptedDailyRows")
    add("blocked_pending_rows=$blockedPendingRows")
    add("blocked_conflict_rows=$blockedConflictRows")
    add("blocked_ambiguous_rows=$blockedAmbiguousRows")
    add("blocked_verified_history_rows=$blockedVerifiedHistoryRows")
    add("source_collision_rows=$sourceCollisionRows")
    add("readback_missing_rows=$readbackMissingRows")
    add("readback_mismatched_rows=$readbackMismatchedRows")
    add("readback_ambiguous_rows=$readbackAmbiguousRows")
    add("readback_unqueried_rows=$readbackUnqueriedRows")
    add("preserved_daily_rows=$preservedDailyRows")
    add("source_rejected_records=$sourceRejectedRecords")
    add("excluded_hrv_samples=$excludedHrvSamples")
    add("attempted_owned_revision_rows=$attemptedOwnedRevisionRows")
    add("verified_owned_revision_rows=$verifiedOwnedRevisionRows")
    add("eligible_owned_revision_rows=$eligibleOwnedRevisionRows")
    add("unowned_conflict_rows=$unownedConflictRows")
    add("changed_owned_baseline_rows=$changedOwnedBaselineRows")
    for ((issue, count) in ownedRevisionMetadataIssues) add("owned_revision_metadata.${issue.name}=$count")
    for ((field, count) in safeMismatchCounts(precheckMismatchFields)) add("precheck_mismatch_field.$field=$count")
    for ((field, count) in safeMismatchCounts(readbackMismatchFields)) add("readback_mismatch_field.$field=$count")
    add("affected_rows=$affectedRows")
    // Counts describe different units and stages; this makes their overlap explicit.
    add("count_notes=verified_includes_preexisting;not_attempted_includes_blocked;issue_sources_may_overlap")
    issueCounts.forEachIndexed { index, issue ->
        add("issue.$index.code=" + safeMiHealthImportIssueCode(issue.code))
        add("issue.$index.severity=${issue.severity.name}")
        add("issue.$index.occurrences=${issue.occurrences}")
        add("issue.$index.source_records=${issue.sourceRecordCount}")
    }
    batchIndex?.let { add("batch_index=$it") }
    batchRows?.let { add("batch_rows=$it") }
    add("raw_batch_limit=$rawBatchLimit")
    rhrDailyMatchedDays?.let { add("rhr_daily_matched_days=$it") }
    rhrDailyMissingDays?.let { add("rhr_daily_missing_days=$it") }
    rhrDailyConflictingDays?.let { add("rhr_daily_conflicting_days=$it") }
    pendingProbe?.let { p ->
        add("pending_probe.read_only=true")
        add("pending_probe.pending_rows=${p.pendingRows}")
        add("pending_probe.verified=${p.pendingVerified}")
        add("pending_probe.missing=${p.pendingMissing}")
        add("pending_probe.mismatched=${p.pendingMismatch}")
        add("pending_probe.ambiguous=${p.pendingAmbiguous}")
        add("pending_probe.unqueried=${p.pendingUnqueried}")
        add("pending_probe.source_collision_rows=${p.sourceCollisionRows}")
        add("pending_probe.broad_read_groups=${p.broadReadGroups}")
        add("pending_probe.broad_read_failure_groups=${p.broadReadFailureGroups}")
        add("pending_probe.broad_read_limited_groups=${p.broadReadLimitedGroups}")
        add("pending_probe.narrow_selected=${p.probeSelected}")
        add("pending_probe.narrow_found=${p.probeFound}")
        add("pending_probe.narrow_missing=${p.probeMissing}")
        add("pending_probe.narrow_mismatched=${p.probeMismatch}")
        add("pending_probe.narrow_ambiguous=${p.probeAmbiguous}")
        add("pending_probe.narrow_unqueried=${p.probeUnqueried}")
        add("pending_probe.broad_narrow_disagreement=${p.broadVsNarrowDisagreement}")
        add("pending_probe.missing_with_other_source_rows=${p.missingWithOtherSourceRows}")
        add("pending_probe.missing_with_deleted_rows=${p.missingWithDeletedRows}")
        add("pending_probe.native_selected=${p.nativeSelected}")
        add("pending_probe.native_found=${p.nativeFound}")
        add("pending_probe.native_missing=${p.nativeMissing}")
        add("pending_probe.native_mismatched=${p.nativeMismatch}")
        add("pending_probe.native_ambiguous=${p.nativeAmbiguous}")
        add("pending_probe.native_unqueried=${p.nativeUnqueried}")
        add("pending_probe.native_pages_read=${p.nativePagesRead}")
        add("pending_probe.native_complete_queries=${p.nativeCompleteQueries}")
        add("pending_probe.native_queries=${p.nativeQueries}")
        add("pending_probe.native_follow_up_queries=${p.nativeFollowUpQueries}")
        add("pending_probe.native_follow_up_failures=${p.nativeFollowUpFailures}")
        add("pending_probe.native_targets_within_observed_span=${p.nativeTargetsWithinObservedSpan}")
        p.nativeStopReasons.forEach { (reason, count) -> add("pending_probe.native_stop.${reason.name}=$count") }
    }
    if (pendingProbeFailed) add("pending_probe.failed=true")
    pendingRecovery?.let { r ->
        add("pending_recovery.status=" + safeMiPendingRecoveryStatus(r.status))
        add("pending_recovery.user_requested=${r.userRequested}")
        add("pending_recovery.automatic=${r.automatic}")
        add("pending_recovery.candidate_rows=${r.candidateRows}")
        add("pending_recovery.previously_consumed=${r.previouslyConsumed}")
        add("pending_recovery.consumed_this_run=${r.consumedThisRun}")
        add("pending_recovery.legacy_row_metadata_unknown=${r.legacyWireMetadataUnknown}")
        add("pending_recovery.history_pages_read=${r.historyPagesRead}")
        r.historyComplete?.let { add("pending_recovery.history_complete=$it") }
        r.historyStopReason?.let { add("pending_recovery.history_stop_reason=${it.name}") }
        r.previousOutcome?.let { add("pending_recovery.previous_outcome=" + safeMiPendingRecoveryOutcome(it)) }
        r.previousAttempt?.let { add(it.copyableText()) }
        r.precheckStatus?.let { add("pending_recovery.precheck_status=${it.name}") }
        add("pending_recovery.native_readback_pages=${r.nativeReadbackPages}")
        r.nativeReadbackComplete?.let { add("pending_recovery.native_readback_complete=$it") }
        r.nativeReadbackStopReason?.let { add("pending_recovery.native_readback_stop_reason=${it.name}") }
        r.nativeReadbackStatus?.let { add("pending_recovery.native_readback_status=${it.name}") }
        add("pending_recovery.native_readback_failed=${r.nativeReadbackFailed}")
    }
    pendingReconcile?.let { r ->
        add("pending_reconcile.status=" + safeMiPendingReconcileStatus(r.status))
        add("pending_reconcile.user_requested=${r.userRequested}")
        add("pending_reconcile.cloud_read_only=true")
        add("pending_reconcile.updates_local_journal=true")
        add("pending_reconcile.candidate_rows=${r.candidateRows}")
        add("pending_reconcile.reconciled_rows=${r.reconciledRows}")
        add("pending_reconcile.minute_reconciled_rows=${r.minuteReconciledRows}")
        add("pending_reconcile.unresolved_rows=${r.unresolvedRows}")
        add("pending_reconcile.source_changed_rows=${r.sourceChangedRows}")
        add("pending_reconcile.source_collision_rows=${r.sourceCollisionRows}")
        add("pending_reconcile.cloud_conflict_rows=${r.cloudConflictRows}")
        add("pending_reconcile.deleted_rows=${r.deletedRows}")
        add("pending_reconcile.other_source_rows=${r.otherSourceRows}")
        add("pending_reconcile.missing_rows=${r.missingRows}")
        add("pending_reconcile.unqueried_rows=${r.unqueriedRows}")
        add("pending_reconcile.journal_changed_rows=${r.journalChangedRows}")
        add("pending_reconcile.persistence_failed_rows=${r.persistenceFailedRows}")
        add("pending_reconcile.broad_read_groups=${r.broadReadGroups}")
        add("pending_reconcile.broad_read_failure_groups=${r.broadReadFailureGroups}")
        add("pending_reconcile.broad_read_limited_groups=${r.broadReadLimitedGroups}")
        add("pending_reconcile.history_selected_rows=${r.historySelectedRows}")
        add("pending_reconcile.history_pages_read=${r.historyPagesRead}")
        r.historyComplete?.let { add("pending_reconcile.history_complete=$it") }
        add("pending_reconcile.history_read_failed=${r.historyReadFailed}")
        add("pending_reconcile.history_queries=${r.historyQueries}")
        add("pending_reconcile.history_follow_up_queries=${r.historyFollowUpQueries}")
        add("pending_reconcile.history_follow_up_failures=${r.historyFollowUpFailures}")
        add("pending_reconcile.history_targets_within_observed_span=${r.historyTargetsWithinObservedSpan}")
        r.historyStopReasons.forEach { (reason, count) -> add("pending_reconcile.history_stop.${reason.name}=$count") }
        add("pending_reconcile.count_notes=disjoint_candidate_outcomes;unresolved_is_not_global_pending;no_new_uploads")
        r.oldestRecoveryAttempt?.let {
            add("pending_reconcile.oldest_previous_outcome=${it.outcome?.name ?: "UNKNOWN"}")
            add(it.copyableText().replace("pending_recovery.", "pending_reconcile.oldest_"))
        }
        r.readComparison?.let { add(it.copyableText()) }
    }
    sleepConflict?.let { add(it.copyableText()) }
    addResponse("upload", uploadResponse)
    addResponse("read", readResponse)
    add("excluded=credentials,account_ids,device_ids,source_record_ids,timestamps,health_values,request_bodies,response_bodies")
}.joinToString("\n")

private fun failureReasonLabel(reason: String): String = L10n.text(when (safeMiHealthImportFailureReason(reason)) {
    "CONFLICT" -> L10n.importReasonConflict
    "PENDING" -> L10n.importReasonPending
    "REJECTED" -> L10n.importReasonRejected
    "UNVERIFIED" -> L10n.importReasonUnverified
    "SOURCE_REJECTED" -> L10n.importReasonSourceRejected
    else -> L10n.importIssueUnknown
})

private fun safeMismatchCounts(counts: Map<String, Int>): Map<String, Int> = counts.entries
    .groupBy { safeMiHealthImportMismatchField(it.key) }
    .entries.sortedBy { it.key }.associate { (field, entries) -> field to entries.sumOf { it.value.coerceAtLeast(0) } }

private fun MutableList<String>.addResponse(prefix: String, response: MiHealthImportResponseSummary?) {
    if (response == null) return
    response.businessCode?.let { add("${prefix}_business_code=$it") }
    response.httpStatus?.let { add("${prefix}_http_status=$it") }
    response.accepted?.let { add("${prefix}_accepted=$it") }
    response.transportFailure?.let { add("${prefix}_transport_failure=${it.name}") }
    response.uploadResultShape?.let { add("${prefix}_result_shape=${it.name}") }
    val safe = safeMiHealthImportMessage(response.safeMessage)
    safe.first?.let { add("${prefix}_message=$it") }
    add("${prefix}_message_withheld=${response.messageWithheld || safe.second}")
}

private val DIAGNOSTIC_METRICS = setOf("heart_rate", "resting_heart_rate", "sleep", "hrv", "spo2")
