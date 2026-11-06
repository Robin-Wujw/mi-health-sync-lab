package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloud
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportIssue
import com.bettermifitness.sync.data.api.MiHealthImportIssueSeverity
import com.bettermifitness.sync.data.api.MiHealthImportPlan
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiHealthImportVerification
import com.bettermifitness.sync.data.api.MiHealthImportVerificationStatus
import com.bettermifitness.sync.data.api.MiHealthImportVerifier
import com.bettermifitness.sync.data.api.MiSleepRevisionStatus
import com.bettermifitness.sync.data.api.MiSleepConflictDiagnostic
import com.bettermifitness.sync.data.api.MiSleepConflictOwnership
import com.bettermifitness.sync.data.api.safeMiHealthImportMismatchField
import com.bettermifitness.sync.data.api.summarizeMiHealthImportMismatchFields
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.health.HealthInboundMetric
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException

/** Reads every selected sleep target, including conflicts with no pending entry. Never writes a journal. */
internal class MiSleepConflictInspector(private val cloud: MiHealthImportCloud, private val journal: HealthImportJournal) {
    suspend fun inspect(account: String, plan: MiHealthImportPlan): MiSleepConflictDiagnostic {
        require(plan.metric == HealthInboundMetric.SLEEP)
        val report = MiSleepConflictAccumulator()
        val groups = plan.rawRows.groupBy { it.identity }.values
        val identities = groups.map { it.first().identity.stableKey }.toSet()
        val pending = journal.pendingIdentities(account, identities)
        val verified = journal.verifiedFingerprints(account)
        val owned = journal.ownedVerifiedFingerprints(account)
        val legacy = journal.legacyVerifiedFingerprints(account)
        val rows = groups.mapNotNull { group ->
            val row = group.first()
            if (group.map { it.canonicalContent to it.zoneOffsetSeconds }.distinct().size > 1) {
                report.unqueried(row, MiSleepRevisionStatus.SOURCE_COLLISION)
                null
            } else row
        }
        for ((index, group) in rows.groupBy { it.identity.key to it.identity.time.floorDiv(86_400L) }.values.withIndex()) {
            if (index >= 32) {
                report.readLimitedGroups++
                group.forEach { report.unqueried(it, MiSleepRevisionStatus.READ_LIMIT) }
                continue
            }
            val first = group.first()
            val day = first.identity.time.floorDiv(86_400L)
            val observed = try {
                cloud.readRows(MiHealthImportChannel.RAW, first.identity.key,
                    ((day - 1) * 86_400L).coerceAtLeast(0), (day + 1) * 86_400L + 2)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expired: MiApiException.AuthExpired) {
                throw expired // Safe to refresh and repeat this read-only operation.
            } catch (_: Exception) {
                report.readFailureGroups++
                group.forEach { report.unqueried(it, MiSleepRevisionStatus.READ_FAILED) }
                continue
            }
            for (row in group) {
                val digest = HealthImportLedger.identityDigest(row.identity.stableKey)
                report.observe(row, observed, verified[digest], owned[digest], legacy[digest],
                    row.identity.stableKey in pending, sleepRevisionSourceBlocker(row, plan.issues))
            }
        }
        return report.snapshot()
    }
}

internal fun sleepRevisionSourceBlocker(row: MiHealthImportRow, issues: List<MiHealthImportIssue>): MiSleepRevisionStatus? {
    val related = issues.filter { it.sourceRecordKeys.isEmpty() || it.sourceRecordKeys.any { key -> key in row.sourceRecordKeys } }
    if (related.any { it.severity == MiHealthImportIssueSeverity.REJECTED }) return MiSleepRevisionStatus.SOURCE_REJECTED
    if (related.any { it.code in setOf("SOURCE_TIMEZONE_UNAVAILABLE", "SOURCE_TIMEZONE_CHANGED") }) {
        return MiSleepRevisionStatus.SOURCE_TIMEZONE_UNCERTAIN
    }
    return null
}

internal class MiSleepConflictAccumulator {
    private data class Observation(val verification: MiHealthImportVerification?, val pending: Boolean,
        val ownership: MiSleepConflictOwnership?, val eligibility: MiSleepRevisionStatus)
    private val observations = linkedMapOf<String, Observation>()
    var attemptedRevisionRows = 0
    var verifiedRevisionRows = 0
    var readFailureGroups = 0
    var readLimitedGroups = 0

    fun unqueried(row: MiHealthImportRow, reason: MiSleepRevisionStatus) {
        observations[row.identity.stableKey] = Observation(null, false, null, reason)
    }

    fun observe(row: MiHealthImportRow, observed: List<MiHealthImportCloudRow>, verified: String?, owned: String?, legacy: String?,
        pending: Boolean, sourceBlocker: MiSleepRevisionStatus?) {
        val verification = MiHealthImportVerifier.verify(row, observed)
        val eligibility = when {
            verification.status == MiHealthImportVerificationStatus.VERIFIED -> MiSleepRevisionStatus.ALREADY_MATCHED
            pending -> MiSleepRevisionStatus.PENDING
            sourceBlocker != null -> sourceBlocker
            else -> assessSleepRevision(row, observed, sourceTimezoneReliable = true)
        }
        observations[row.identity.stableKey] = Observation(verification, pending,
            describeSleepConflictOwnership(row, observed, verified, owned, legacy), eligibility)
    }

    fun snapshot(): MiSleepConflictDiagnostic {
        val values = observations.values
        return MiSleepConflictDiagnostic(
            inspectedRows = values.size,
            matchedRows = values.count { it.verification?.status == MiHealthImportVerificationStatus.VERIFIED },
            mismatchedRows = values.count { it.verification?.status == MiHealthImportVerificationStatus.MISMATCH },
            ambiguousRows = values.count { it.verification?.status == MiHealthImportVerificationStatus.AMBIGUOUS },
            missingRows = values.count { it.verification?.status == MiHealthImportVerificationStatus.MISSING },
            unqueriedRows = values.count { it.verification == null },
            pendingRows = values.count { it.pending },
            eligibleRevisionRows = values.count { it.eligibility == MiSleepRevisionStatus.ELIGIBLE },
            attemptedRevisionRows = attemptedRevisionRows, verifiedRevisionRows = verifiedRevisionRows,
            readFailureGroups = readFailureGroups, readLimitedGroups = readLimitedGroups,
            ownershipCounts = values.mapNotNull { it.ownership }.groupingBy { it }.eachCount(),
            eligibilityCounts = values.groupingBy { it.eligibility }.eachCount(),
            mismatchFields = summarizeMiHealthImportMismatchFields(values.mapNotNull { it.verification }),
        )
    }
}

fun MiSleepConflictDiagnostic.copyableText(): String = buildList {
    add("sleep_conflict.inspected_rows=$inspectedRows")
    add("sleep_conflict.matched_rows=$matchedRows")
    add("sleep_conflict.mismatched_rows=$mismatchedRows")
    add("sleep_conflict.ambiguous_rows=$ambiguousRows")
    add("sleep_conflict.missing_rows=$missingRows")
    add("sleep_conflict.unqueried_rows=$unqueriedRows")
    add("sleep_conflict.pending_rows=$pendingRows")
    add("sleep_conflict.eligible_revision_rows=$eligibleRevisionRows")
    add("sleep_conflict.attempted_revision_rows=$attemptedRevisionRows")
    add("sleep_conflict.verified_revision_rows=$verifiedRevisionRows")
    add("sleep_conflict.read_failure_groups=$readFailureGroups")
    add("sleep_conflict.read_limited_groups=$readLimitedGroups")
    ownershipCounts.entries.sortedBy { it.key.name }.forEach { (kind, count) -> add("sleep_conflict.ownership.${kind.name}=$count") }
    eligibilityCounts.entries.sortedBy { it.key.name }.forEach { (kind, count) -> add("sleep_conflict.eligibility.${kind.name}=$count") }
    mismatchFields.entries.groupBy { safeMiHealthImportMismatchField(it.key) }.entries.sortedBy { it.key }.forEach { (field, counts) ->
        add("sleep_conflict.mismatch_field.$field=${counts.maxOf { it.value }}")
    }
}.joinToString("\n")
