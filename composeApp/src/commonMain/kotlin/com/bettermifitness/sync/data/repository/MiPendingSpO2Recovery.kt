package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.data.preferences.HealthImportJournalEntry
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.data.preferences.HealthImportPendingSnapshot
import com.bettermifitness.sync.data.preferences.HealthImportPendingReconciliation
import com.bettermifitness.sync.data.preferences.HealthImportRecoveryOutcome
import com.bettermifitness.sync.data.preferences.HealthImportRecoveryReservation
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthRecordIds
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * One explicitly requested extra attempt for the oldest pending SpO2 target.
 * Missing reads are observations, not proof that an earlier POST never applied.
 * A durable per-target allowance is consumed before POST and is never reset by
 * this runner. Within the selected source plan, repeating the command cannot
 * step past its oldest unresolved used target.
 */
class MiPendingSpO2Recovery(
    private val cloud: MiHealthImportCloud,
    private val journal: HealthImportJournal,
    private val readbackPause: suspend (Long) -> Unit = { delay(it) },
) {
    // One ordered state machine, kept whole so no safety step can be skipped. Its behavior was verified on a device.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    suspend fun recoverOne(account: String, plan: MiHealthImportPlan,
        userRequested: Boolean, automatic: Boolean = false, targetIdentity: String? = null): MiHealthImportDiagnostic {
        require(account.isNotBlank())
        var candidate: MiHealthImportRow? = null
        var snapshot: HealthImportPendingSnapshot? = null
        var stage = MiHealthImportStage.PREPARE
        var attempted = 0
        var existing = 0
        var newlyVerified = 0
        var consumed = false
        var historyPages = 0
        var historyComplete: Boolean? = null
        var historyStopReason: MiHistoryStopReason? = null
        var uploadResponse: MiHealthImportResponseSummary? = null
        var readResponse: MiHealthImportResponseSummary? = null
        var lastReadback: MiHealthImportVerification? = null
        var precheckFields = emptySet<String>()
        var precheckStatus: MiHealthImportVerificationStatus? = null
        var nativeReadbackPages = 0
        var nativeReadbackComplete: Boolean? = null
        var nativeReadbackStopReason: MiHistoryStopReason? = null
        var nativeReadbackStatus: MiHealthImportVerificationStatus? = null
        var nativeReadbackFailed = false
        var sourceCollision = 0
        var sourceRejected = 0
        var candidateCount = 0
        fun diagnostic(status: String, reason: MiHealthImportFailureReason? = null): MiHealthImportDiagnostic {
            val selected = if (candidate == null) 0 else 1
            val verified = existing + newlyVerified
            val failed = reason != null
            return MiHealthImportDiagnostic(
                metricKey = plan.metric.metricKey, stage = stage, channel = MiHealthImportChannel.RAW,
                testMode = true, inputRecords = plan.inputRecordCount, sourceRawRows = plan.rawRows.size,
                plannedRawRows = selected, plannedDailyRows = 0, attemptedRawRows = attempted,
                verifiedRawRows = verified, alreadyPresentRawRows = existing, newlyVerifiedRawRows = newlyVerified,
                notAttemptedRawRows = (selected - attempted - existing).coerceAtLeast(0), rawBatchLimit = 1,
                affectedRows = if (failed) selected.coerceAtLeast(1) else 0,
                sourceRejectedRecords = sourceRejected, sourceCollisionRows = sourceCollision,
                blockedPendingRows = if (failed && attempted == 0 && reason == MiHealthImportFailureReason.PENDING) selected else 0,
                blockedConflictRows = if (failed && attempted == 0 && reason == MiHealthImportFailureReason.CONFLICT) selected else 0,
                readbackMissingRows = if (attempted == 1 && lastReadback?.status == MiHealthImportVerificationStatus.MISSING) 1 else 0,
                readbackMismatchedRows = if (attempted == 1 && lastReadback?.status == MiHealthImportVerificationStatus.MISMATCH) 1 else 0,
                readbackAmbiguousRows = if (attempted == 1 && lastReadback?.status == MiHealthImportVerificationStatus.AMBIGUOUS) 1 else 0,
                readbackUnqueriedRows = if (attempted == 1 && newlyVerified == 0 && lastReadback == null) 1 else 0,
                precheckMismatchFields = precheckFields.associateWith { 1 },
                readbackMismatchFields = lastReadback?.mismatchedFields.orEmpty().associateWith { 1 },
                uploadResponse = uploadResponse, readResponse = readResponse, failureReason = reason?.name,
                issueCounts = summarizeMiHealthImportIssues(plan.issues),
                pendingRecovery = MiPendingRecoveryDiagnostic(status = status, userRequested = userRequested, automatic = automatic,
                    candidateRows = candidateCount, previouslyConsumed = snapshot?.recoveryConsumed == true,
                    consumedThisRun = consumed, legacyWireMetadataUnknown = snapshot?.zoneOffsetSeconds == null && snapshot != null,
                    historyPagesRead = historyPages, historyComplete = historyComplete,
                    previousOutcome = snapshot?.recoveryAttempt?.outcome, historyStopReason = historyStopReason,
                    previousAttempt = snapshot?.takeIf { it.recoveryConsumed }?.previousRecoveryDiagnostic(),
                    precheckStatus = precheckStatus,
                    nativeReadbackPages = nativeReadbackPages, nativeReadbackComplete = nativeReadbackComplete,
                    nativeReadbackStopReason = nativeReadbackStopReason, nativeReadbackStatus = nativeReadbackStatus,
                    nativeReadbackFailed = nativeReadbackFailed),
            )
        }

        if (!userRequested && !automatic) return diagnostic("NOT_REQUESTED")
        val heart = plan.metric == HealthInboundMetric.HEART_RATE
        val key = if (heart) "heart_rate" else "spo2"
        val historyLimit = if (heart) 128 else 32
        if (plan.metric !in setOf(HealthInboundMetric.OXYGEN_SATURATION, HealthInboundMetric.HEART_RATE) || plan.rawRows.any {
                it.identity.key != key || it.identity.sid != MI_HEALTH_CONNECT_IMPORT_SID ||
                    it.identity.tag != null || it.enrichment != null
            }) return diagnostic("UNSUPPORTED", MiHealthImportFailureReason.SOURCE_REJECTED)

        val pending = journal.pendingIdentities(account, plan.rawRows.map { it.identity.stableKey }.toSet())
        candidateCount = plan.rawRows.filter { it.identity.stableKey in pending }.map { it.identity }.distinct().size
        val selected = plan.rawRows.filter { it.identity.stableKey in pending &&
            (targetIdentity == null || it.identity.stableKey == targetIdentity) }
            .minWithOrNull(compareBy({ it.identity.time }, { it.identity.stableKey }))
        if (selected == null) {
            stage = MiHealthImportStage.COMPLETE
            return diagnostic(if (plan.issues.any { it.code == "HEART_RATE_SOURCE_POINT_EXCLUDED" })
                "NO_PENDING_WITH_EXCLUSIONS" else "NO_PENDING")
        }
        candidate = selected
        val sameTarget = plan.rawRows.filter { it.identity == selected.identity }
        if (sameTarget.map { it.canonicalContent to it.zoneOffsetSeconds }.distinct().size != 1) {
            sourceCollision = 1
            return diagnostic("SOURCE_COLLISION", MiHealthImportFailureReason.CONFLICT)
        }
        val row = selected.copy(sourceRecordKeys = sameTarget.flatMap { it.sourceRecordKeys }.toSet())
        val minutePolicy = MiSpO2MinuteMatchPolicy(plan.rawRows)
        candidate = row
        // Rejected source material can hide a conflicting target removed by the
        // mapper. Recovery is intentionally narrower than normal partial sync.
        val localDay = (row.identity.time + row.zoneOffsetSeconds).floorDiv(DAY)
        val rejected = plan.issues.filter { issue -> issue.severity == MiHealthImportIssueSeverity.REJECTED &&
            if (heart && issue.code == "TARGET_TIME_COLLISION" && issue.affectedTarget?.key == "heart_rate")
                issue.affectedTarget == row.identity
            else (!heart || issue.affectedLocalDays == null || localDay in issue.affectedLocalDays) }
        if (rejected.isNotEmpty()) {
            sourceRejected = rejected.flatMap { it.sourceRecordKeys }.distinct().size.coerceAtLeast(1)
            return diagnostic("SOURCE_CHANGED", MiHealthImportFailureReason.SOURCE_REJECTED)
        }
        val entry = MiHealthImportRunner.journalEntry(row).copy(zoneOffsetSeconds = row.zoneOffsetSeconds)
        snapshot = journal.pendingSnapshots(account, setOf(row.identity.stableKey))[row.identity.stableKey]
        val original = snapshot
        if (original == null || !original.matches(entry) || row.sourceRecordKeys.isEmpty() ||
            !row.dayAggregationEligible || row.zoneOffsetSeconds !in -64_800..64_800) {
            sourceRejected = row.sourceRecordKeys.size.coerceAtLeast(1)
            return diagnostic("SOURCE_CHANGED", MiHealthImportFailureReason.SOURCE_REJECTED)
        }
        val time = row.identity.time
        val day = time.floorDiv(DAY)
        if (time < 0 || day > (Long.MAX_VALUE - 2) / DAY - 1) {
            sourceRejected = row.sourceRecordKeys.size.coerceAtLeast(1)
            return diagnostic("SOURCE_CHANGED", MiHealthImportFailureReason.SOURCE_REJECTED)
        }
        val from = (day * DAY - DAY).coerceAtLeast(0)
        // Start just before the target, so an exclusive start bound cannot omit
        // it. SpO2 still needs every observation in its destination minute.
        val minuteStart = time.floorDiv(60) * 60
        val targetFrom = ((if (heart) time else minuteStart) - 1).coerceAtLeast(0)
        val targetUntil = if (heart) time + 1 else minuteStart + 60
        stage = MiHealthImportStage.PRECHECK

        suspend fun <T> readBefore(action: suspend () -> T): T? = try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MiApiException.AuthExpired) {
            throw e // Nothing has consumed an allowance or attempted a POST yet.
        } catch (e: Exception) {
            readResponse = miHealthImportFailureSummary(e)
            null
        }

        suspend fun existingResult(observed: List<MiHealthImportCloudRow>,
            minuteMatch: MiHealthImportCloudRow? = null): MiHealthImportDiagnostic {
            try {
                // One transaction checks the exact pending/recovery snapshot, records the
                // observation and clears only this lock. Never promote a read to ownership.
                val committed = journal.reconcileObservedPending(account, listOf(HealthImportPendingReconciliation(
                    expected = original, sourceEntry = entry,
                    observedContentFingerprint = minuteMatch?.let {
                        HealthRecordIds.version(canonicalMiHealthImportJson(it.value)).toString()
                    }
                        ?: observedEntry(row, observed, owned = false).contentFingerprint,
                    observedTargetIdentity = minuteMatch?.importIdentity()?.stableKey ?: entry.targetIdentity,
                )))
                if (row.identity.stableKey !in committed)
                    return diagnostic("RESERVATION_CHANGED", MiHealthImportFailureReason.UNVERIFIED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return diagnostic("READ_FAILED", MiHealthImportFailureReason.UNVERIFIED)
            }
            existing = 1
            stage = MiHealthImportStage.COMPLETE
            return diagnostic("ALREADY_PRESENT")
        }

        val precheckObservations = linkedSetOf<MiHealthImportCloudRow>()
        suspend fun inspectBefore(observed: List<MiHealthImportCloudRow>): MiHealthImportDiagnostic? {
            precheckObservations += observed
            val combined = precheckObservations.toList()
            val check = strictObservation(row, combined)
            precheckFields = check.verification.mismatchedFields
            precheckStatus = check.verification.status
            if (check.status == "MISSING") {
                minutePolicy.find(row, combined)?.let {
                    precheckStatus = MiHealthImportVerificationStatus.VERIFIED
                    return existingResult(combined, it)
                }
                if (minutePolicy.hasMinuteObservation(row, combined)) return diagnostic("CONFLICT", MiHealthImportFailureReason.CONFLICT)
            }
            return when (check.status) {
                "ALREADY_PRESENT" -> existingResult(combined)
                "MISSING" -> null
                else -> diagnostic(check.status, MiHealthImportFailureReason.CONFLICT)
            }
        }

        val broad = readBefore { cloud.readRows(MiHealthImportChannel.RAW, key, targetFrom, targetUntil) }
            ?: return diagnostic("READ_FAILED", MiHealthImportFailureReason.UNVERIFIED)
        inspectBefore(broad)?.let { return it }
        val historical = readBefore { cloud.readRawRowsWithoutStart(key, time + 1, maxPages = historyLimit) }
            ?: return diagnostic("READ_FAILED", MiHealthImportFailureReason.UNVERIFIED)
        historyPages = historical.pagesRead
        historyStopReason = historical.stopReason
        historyComplete = historical.complete && historical.pagesRead in 1..historyLimit
        // Visible matching/conflicting rows are useful even if traversal hit its
        // cap. Only an ABSENT observation needs a completed traversal before POST.
        inspectBefore(historical.rows)?.let { return it }
        if (historyComplete != true) return diagnostic("HISTORY_INCOMPLETE", MiHealthImportFailureReason.UNVERIFIED)
        // Preserve the fresh overlapping-day prewrite check: Xiaomi can omit the
        // bucket containing start_time. A narrow omission cannot authorize POST.
        val narrow = readBefore { cloud.readRows(MiHealthImportChannel.RAW, key, from, time + 1) }
            ?: return diagnostic("READ_FAILED", MiHealthImportFailureReason.UNVERIFIED)
        inspectBefore(narrow)?.let { return it }
        if (original.recoveryConsumed) return diagnostic("RETRY_ALREADY_CONSUMED", MiHealthImportFailureReason.PENDING)
        if (row.identity in minutePolicy.collisions) {
            sourceCollision = 1
            return diagnostic("SOURCE_COLLISION", MiHealthImportFailureReason.CONFLICT)
        }
        if (!heart && time % 60 != 0L) {
            val minuteIdentity = row.identity.copy(time = time.floorDiv(60) * 60).stableKey
            if (minuteIdentity in journal.pendingIdentities(account, setOf(minuteIdentity)) ||
                HealthImportLedger.identityDigest(minuteIdentity) in journal.verifiedFingerprints(account)) {
                return diagnostic("RESERVATION_CHANGED", MiHealthImportFailureReason.UNVERIFIED)
            }
        }

        val reservation = journal.reserveSingleRecovery(account, original, entry)
            ?: return diagnostic("RESERVATION_CHANGED", MiHealthImportFailureReason.PENDING)
        consumed = true
        val submittedSnapshot = journal.pendingSnapshots(account, setOf(row.identity.stableKey))[row.identity.stableKey]

        suspend fun note(outcome: HealthImportRecoveryOutcome, accepted: Boolean? = null,
            code: Int? = null, readback: String? = null): Boolean = try {
            journal.recordRecoveryOutcome(account, reservation, outcome, accepted, code, readback)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false // The already-committed RESERVED record still prevents another POST.
        }

        var rejectedUpload = false
        var ownershipEligible = false
        stage = MiHealthImportStage.UPLOAD
        attempted = 1
        try {
            val response = cloud.uploadRows(listOf(row), experimentalEnabled = true)
            uploadResponse = response.responseSummary()
            rejectedUpload = !response.accepted
            ownershipEligible = response.accepted
            if (!note(if (response.accepted) HealthImportRecoveryOutcome.ACCEPTED else HealthImportRecoveryOutcome.REJECTED,
                    response.accepted, response.code)) return diagnostic("UPLOAD_UNVERIFIED", MiHealthImportFailureReason.UNVERIFIED)
        } catch (e: CancellationException) {
            throw e // Reservation survives process cancellation and restart.
        } catch (e: Exception) {
            uploadResponse = miHealthImportFailureSummary(e)
            ownershipEligible = true // It may have applied despite a lost response.
            if (!note(HealthImportRecoveryOutcome.TRANSPORT_UNKNOWN, code = uploadResponse?.businessCode)) {
                return diagnostic("UPLOAD_UNVERIFIED", MiHealthImportFailureReason.UNVERIFIED)
            }
        }

        stage = MiHealthImportStage.READBACK
        // Collect only observations made after this POST. A later positive query
        // must not erase an earlier tombstone, other source or content conflict.
        val postObservations = linkedSetOf<MiHealthImportCloudRow>()
        suspend fun verifySubmitted(observed: List<MiHealthImportCloudRow>, absenceComplete: Boolean = true): MiHealthImportDiagnostic? {
            postObservations += observed.filter { it.key == row.identity.key && it.time.floorDiv(60) == row.identity.time.floorDiv(60) }
            val checked = strictObservation(row, postObservations.toList())
            val minuteMatch = if (checked.status == "MISSING") minutePolicy.find(row, postObservations.toList()) else null
            if (minuteMatch != null && submittedSnapshot != null) {
                try {
                    val committed = journal.reconcileObservedPending(account, listOf(HealthImportPendingReconciliation(
                        submittedSnapshot, entry, HealthRecordIds.version(canonicalMiHealthImportJson(minuteMatch.value)).toString(),
                        minuteMatch.importIdentity().stableKey)))
                    if (row.identity.stableKey !in committed) return diagnostic("UPLOAD_UNVERIFIED", MiHealthImportFailureReason.UNVERIFIED)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { return diagnostic("UPLOAD_UNVERIFIED", MiHealthImportFailureReason.UNVERIFIED) }
                lastReadback = MiHealthImportVerification(MiHealthImportVerificationStatus.VERIFIED)
                newlyVerified = 1
                stage = MiHealthImportStage.COMPLETE
                return diagnostic("VERIFIED")
            }
            lastReadback = checked.verification.takeIf {
                it.status != MiHealthImportVerificationStatus.MISSING || absenceComplete
            }
            if (checked.status == "ALREADY_PRESENT") {
                try {
                    if (!note(HealthImportRecoveryOutcome.VERIFIED, readback = "VERIFIED")) {
                        return diagnostic("UPLOAD_UNVERIFIED", MiHealthImportFailureReason.UNVERIFIED)
                    }
                    journal.markVerified(account, listOf(observedEntry(row, postObservations.toList(), ownershipEligible)))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return diagnostic("UPLOAD_UNVERIFIED", MiHealthImportFailureReason.UNVERIFIED)
                }
                newlyVerified = 1
                stage = MiHealthImportStage.COMPLETE
                return diagnostic("VERIFIED")
            }
            if (!note(if (rejectedUpload) HealthImportRecoveryOutcome.REJECTED else HealthImportRecoveryOutcome.READBACK_UNVERIFIED,
                    readback = lastReadback?.status?.name ?: "UNQUERIED")) return diagnostic(
                        "UPLOAD_UNVERIFIED", MiHealthImportFailureReason.UNVERIFIED)
            return null
        }
        repeat(3) { attempt ->
            if (attempt > 0) readbackPause(if (attempt == 1) 1_000L else 3_000L)
            val observed = try {
                // If the targeted query omits the row, retain a broad fallback
                // before the independent native read. Every read here is fresh.
                cloud.readRows(MiHealthImportChannel.RAW, key,
                    if (attempt == 0) targetFrom else from, if (attempt == 0) targetUntil else time + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Auth errors after POST never escape to an outer whole-run retry.
                lastReadback = strictObservation(row, postObservations.toList()).verification.takeIf {
                    it.status != MiHealthImportVerificationStatus.MISSING
                }
                readResponse = miHealthImportFailureSummary(e)
                if (!note(if (rejectedUpload) HealthImportRecoveryOutcome.REJECTED else HealthImportRecoveryOutcome.READBACK_UNVERIFIED,
                        readback = lastReadback?.status?.name ?: "UNQUERIED")) return diagnostic(
                            "UPLOAD_UNVERIFIED", MiHealthImportFailureReason.UNVERIFIED)
                return@repeat
            }
            readResponse = null
            verifySubmitted(observed)?.let { return it }
        }
        // The window endpoint is known to omit some observations. Independently
        // check the native request shape once, without sending a second upload.
        val nativeReadback = try {
            cloud.readRawRowsWithoutStart(key, time + 1, maxPages = historyLimit).also {
                require(it.pagesRead in 1..historyLimit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Includes AuthExpired: a completed POST must never be replayed by auth retry.
            nativeReadbackFailed = true
            readResponse = miHealthImportFailureSummary(e)
            lastReadback = strictObservation(row, postObservations.toList()).verification.takeIf {
                it.status != MiHealthImportVerificationStatus.MISSING
            }
            if (!note(if (rejectedUpload) HealthImportRecoveryOutcome.REJECTED else HealthImportRecoveryOutcome.READBACK_UNVERIFIED,
                    readback = lastReadback?.status?.name ?: "UNQUERIED")) return diagnostic(
                        "UPLOAD_UNVERIFIED", MiHealthImportFailureReason.UNVERIFIED)
            null
        }
        if (nativeReadback != null) {
            nativeReadbackPages = nativeReadback.pagesRead
            nativeReadbackComplete = nativeReadback.complete
            nativeReadbackStopReason = nativeReadback.stopReason
            val status = strictObservation(row, nativeReadback.rows).verification.status
            nativeReadbackStatus = status.takeIf { it != MiHealthImportVerificationStatus.MISSING || nativeReadback.complete }
            readResponse = null
            verifySubmitted(nativeReadback.rows, absenceComplete = nativeReadback.complete)?.let { return it }
        }
        if (rejectedUpload) {
            stage = MiHealthImportStage.UPLOAD
            return diagnostic("UPLOAD_REJECTED", MiHealthImportFailureReason.REJECTED)
        }
        return diagnostic("UPLOAD_UNVERIFIED", MiHealthImportFailureReason.UNVERIFIED)
    }

    private data class Observation(val status: String, val verification: MiHealthImportVerification)

    /** Ordinary MISSING also includes tombstones and other SIDs; recovery must not. */
    private fun strictObservation(row: MiHealthImportRow, observed: List<MiHealthImportCloudRow>): Observation {
        val atTarget = observed.filter { it.key == row.identity.key && it.time == row.identity.time }
        if (atTarget.any { it.deleted }) return Observation("TOMBSTONE_PRESENT",
            MiHealthImportVerification(MiHealthImportVerificationStatus.AMBIGUOUS, setOf("deleted")))
        if (atTarget.any { it.sid != row.identity.sid }) return Observation("OTHER_SOURCE_PRESENT",
            MiHealthImportVerification(MiHealthImportVerificationStatus.AMBIGUOUS, setOf("sid")))
        val verified = MiHealthImportVerifier.verify(row, observed)
        return Observation(when (verified.status) {
            MiHealthImportVerificationStatus.VERIFIED -> "ALREADY_PRESENT"
            MiHealthImportVerificationStatus.MISSING -> "MISSING"
            else -> "CONFLICT"
        }, verified)
    }

    private fun observedEntry(row: MiHealthImportRow, observed: List<MiHealthImportCloudRow>, owned: Boolean): HealthImportJournalEntry {
        val actual = observed.first {
            !it.deleted && it.sid == row.identity.sid && it.key == row.identity.key && it.time == row.identity.time
        }
        return MiHealthImportRunner.journalEntry(row).copy(
            contentFingerprint = HealthRecordIds.version(canonicalMiHealthImportJson(actual.value)).toString(),
            ownedUpload = owned, zoneOffsetSeconds = row.zoneOffsetSeconds)
    }

    private companion object { const val DAY = 86_400L }
}
