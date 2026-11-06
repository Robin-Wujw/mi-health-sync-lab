package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloud
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.api.MiHealthImportIssueSeverity
import com.bettermifitness.sync.data.api.MiHealthImportObservationIndex
import com.bettermifitness.sync.data.api.MiHealthImportPlan
import com.bettermifitness.sync.data.api.MiHealthImportStage
import com.bettermifitness.sync.data.api.MiHealthImportVerification
import com.bettermifitness.sync.data.api.MiHealthImportVerificationStatus
import com.bettermifitness.sync.data.api.summarizeMiHealthImportMismatchFields
import com.bettermifitness.sync.data.api.MiSpO2MinuteMatchPolicy
import com.bettermifitness.sync.data.api.MiRevisionMetadataIssue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.health.HealthInboundMetric

/** Cloud reads and ledger reads only. No reservation or health write. */
internal suspend fun inspectMiHealthImportPreflight(
    account: String, plan: MiHealthImportPlan, cloud: MiHealthImportCloud, journal: HealthImportJournal,
): MiHealthImportDiagnostic {
    val base = plan.inspectionDiagnostic()
    val rows = plan.rawRows.distinctBy { it.identity }
    val pending = journal.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet())
    var matched = 0
    var conflicts = 0
    var ambiguous = 0
    var blockedPending = 0
    var eligible = 0
    val metadata = mutableListOf<MiRevisionMetadataIssue>()
    val verifications = mutableListOf<MiHealthImportVerification>()
    val minutePolicy = MiSpO2MinuteMatchPolicy(plan.rawRows)
    val sourceValid = plan.issues.none { it.severity == MiHealthImportIssueSeverity.REJECTED }
    for ((group, targets) in rows.groupBy { it.identity.key to it.identity.time.floorDiv(86_400) }) {
        val observed = cloud.readRows(MiHealthImportChannel.RAW, group.first,
            (group.second * 86_400 - 86_400).coerceAtLeast(0), (group.second + 1) * 86_400 + 2)
        val index = MiHealthImportObservationIndex(observed)
        for (row in targets) {
            val verification = index.verify(row)
            if (verification.status == MiHealthImportVerificationStatus.VERIFIED) { matched++; continue }
            if (sourceValid && verification.status == MiHealthImportVerificationStatus.MISSING &&
                minutePolicy.find(row, observed) != null) { matched++; continue }
            verifications += verification
            if (row.identity.stableKey in pending) { blockedPending++; continue }
            if (verification.status == MiHealthImportVerificationStatus.MISSING) continue
            if (verification.status == MiHealthImportVerificationStatus.AMBIGUOUS) ambiguous++ else conflicts++
            val actual = index.atTarget(row)
            if (sourceValid && (prepareHeartRateRevision(row, actual) != null ||
                    prepareSleepRevision(row, actual, sourceTimezoneReliable = true) != null)) eligible++
            if (plan.metric == HealthInboundMetric.RESTING_HEART_RATE) {
                if (actual.any { it.zoneOffsetSeconds == null }) metadata += MiRevisionMetadataIssue.CLOUD_ZONE_ABSENT
                if (actual.any { it.tag != null && it.tag.isBlank() }) metadata += MiRevisionMetadataIssue.CLOUD_TAG_BLANK
                if (actual.any { !it.tag.isNullOrBlank() }) metadata += MiRevisionMetadataIssue.CLOUD_TAG_NONBLANK
                if (actual.any { (it.value["bpm"] as? JsonPrimitive)?.longOrNull == null })
                    metadata += MiRevisionMetadataIssue.CLOUD_BPM_NON_INTEGER_ENCODING
                if (actual.any { (it.value["date_time"] as? JsonPrimitive)?.longOrNull == null })
                    metadata += MiRevisionMetadataIssue.CLOUD_DATE_NON_INTEGER_ENCODING
                if (row.value.keys != setOf("date_time", "bpm")) metadata += MiRevisionMetadataIssue.SOURCE_SCHEMA_UNEXPECTED
                if (row.identity.time % 86_400L != 0L) metadata += MiRevisionMetadataIssue.SOURCE_DATE_UNALIGNED
            }
        }
    }
    val reason = when {
        conflicts + ambiguous > 0 -> MiHealthImportFailureReason.CONFLICT.name
        blockedPending > 0 -> MiHealthImportFailureReason.PENDING.name
        else -> base.failureReason
    }
    return base.copy(stage = if (matched == rows.size && reason == null) MiHealthImportStage.COMPLETE else MiHealthImportStage.PRECHECK,
        channel = MiHealthImportChannel.RAW, verifiedRawRows = matched, alreadyPresentRawRows = matched,
        notAttemptedRawRows = rows.size - matched, blockedConflictRows = conflicts,
        blockedAmbiguousRows = ambiguous, blockedPendingRows = blockedPending,
        eligibleRevisionRows = eligible, revisionMetadataIssues = metadata.groupingBy { it }.eachCount(),
        affectedRows = rows.size - matched + base.sourceRejectedRecords,
        precheckMismatchFields = summarizeMiHealthImportMismatchFields(verifications), failureReason = reason)
}
