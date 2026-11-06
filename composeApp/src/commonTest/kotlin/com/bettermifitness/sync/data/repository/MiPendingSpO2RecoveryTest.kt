package com.bettermifitness.sync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import com.bettermifitness.sync.health.HealthInboundMetric
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiPendingSpO2RecoveryTest {
    @Test fun denseHeartRecoveryKeepsOneFreshBroadCheckAndNarrowsTheOtherReads() = runBlocking {
        val rows = (0 until 20).map { heartRow(day + 1000 + it * 60) }
        val (_, ledger) = fixture(rows)
        val cloud = Cloud("heart_rate").apply {
            respectWindow = true
            onUpload = { persisted += it.map { r -> r.cloudRow() }; MiHealthImportUploadResult(true, 0) }
        }
        val result = MiPendingImportRecovery(cloud, ledger).recover(account,
            plan(rows).copy(metric = HealthInboundMetric.HEART_RATE), true)
        assertEquals(20, result.newlyVerifiedRawRows)
        assertEquals(null, result.failureReason)
        assertEquals(1, cloud.historyLimits.size)
        assertEquals(60, cloud.readWindows.size)
        assertEquals(40, cloud.readWindows.count { (from, until) -> until - from == 2L })
        assertEquals(20, cloud.readWindows.count { (from, until) -> until - from > 2L })
    }

    @Test fun narrowedSpo2PrecheckRetainsConflictsLaterInTheSameMinute() = runBlocking {
        val minute = (day + 1000).floorDiv(60) * 60
        val target = row(minute + 5)
        val (_, ledger) = fixture(listOf(target))
        val cloud = Cloud().apply {
            respectWindow = true
            persisted += row(minute).cloudRow()
            persisted += row(minute + 50).cloudRow()
        }
        val result = runner(cloud, ledger).recoverOne(account, plan(listOf(target)), true)
        assertEquals("CONFLICT", result.pendingRecovery?.status)
        assertEquals(listOf((minute - 1) to (minute + 60)), cloud.readWindows)
        assertTrue(cloud.uploads.isEmpty())
        assertTrue(ledger.recoveryAttempts(account, setOf(target.identity.stableKey)).isEmpty())
    }

    @Test fun onlySkippedSourceSecondsExplainWhyThereIsNothingToRecover() = runBlocking {
        val (_, ledger) = fixture(emptyList())
        val cloud = Cloud("heart_rate")
        val sourceIssue = MiHealthImportIssue("HEART_RATE_SOURCE_POINT_EXCLUDED", setOf("a", "b"), "synthetic",
            MiHealthImportIssueSeverity.WARNING)
        val result = MiPendingImportRecovery(cloud, ledger).recover(account,
            plan(emptyList()).copy(metric = HealthInboundMetric.HEART_RATE, issues = listOf(sourceIssue)), true)
        assertEquals("NO_PENDING_WITH_EXCLUSIONS", result.pendingRecovery?.status)
        assertEquals(1, result.excludedSourcePoints)
        assertEquals(null, result.failureReason)
        assertTrue(cloud.events.isEmpty())
    }

    private val account = "synthetic-account"
    private val day = 20_000L * 86_400L

    @Test
    fun explicitLegacyRecoverySelectsOnlyOldestOf132PendingAndPreservesOtherRows(): Unit = runBlocking {
        val rows = (0 until 195).map { row(day + 1_000 + it * 60) }
        val pending = rows.drop(63)
        val (store, ledger) = fixture(pending, legacy = true)
        val cloud = Cloud().apply { persisted += rows.take(63).map { it.cloudRow() } }
        cloud.onUpload = { submitted ->
            val current = HealthImportLedger(store).pendingSnapshots(account, submitted.map { it.identity.stableKey }.toSet())
            assertTrue(current.values.single().recoveryConsumed)
            assertEquals(HealthImportRecoveryOutcome.RESERVED.name, current.values.single().recoveryAttempt?.outcome)
            cloud.persisted += submitted.map { it.cloudRow() }
            MiHealthImportUploadResult(true, 0, "ok")
        }
        val diagnostic = runner(cloud, ledger).recoverOne(account, plan(rows), true)
        assertEquals("VERIFIED", diagnostic.pendingRecovery?.status)
        assertEquals(132, diagnostic.pendingRecovery?.candidateRows)
        assertEquals(true, diagnostic.pendingRecovery?.legacyWireMetadataUnknown)
        assertEquals(195, diagnostic.inputRecords)
        assertEquals(1, diagnostic.plannedRawRows)
        assertEquals(0, diagnostic.plannedDailyRows)
        assertEquals(1, diagnostic.attemptedRawRows)
        assertEquals(1, diagnostic.newlyVerifiedRawRows)
        assertEquals(1, cloud.uploads.size)
        assertEquals(listOf(pending.first()), cloud.uploads.single())
        assertEquals(32, cloud.historyLimits.single())
        assertEquals(listOf("read", "history", "read", "upload", "read"), cloud.events)
        assertEquals(pending.drop(1).map { it.identity.stableKey }.toSet(),
            ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()))
        assertEquals(1, ledger.ownedVerifiedFingerprints(account).size)
        assertEquals(HealthImportRecoveryOutcome.VERIFIED.name,
            HealthImportLedger(store).recoveryAttempts(account, setOf(pending.first().identity.stableKey)).values.single().outcome)
    }

    @Test
    fun noExplicitRequestAndUnsupportedMetricsMakeNoCloudRequestOrConsumeAllowance(): Unit = runBlocking {
        val row = row(day)
        val (_, ledger) = fixture(listOf(row))
        val cloud = Cloud()
        assertEquals("NOT_REQUESTED", runner(cloud, ledger).recoverOne(account, plan(listOf(row)), false).pendingRecovery?.status)
        assertEquals("UNSUPPORTED", runner(cloud, ledger).recoverOne(account,
            plan(listOf(row)).copy(metric = HealthInboundMetric.HEART_RATE), true).pendingRecovery?.status)
        assertTrue(cloud.events.isEmpty())
        assertTrue(ledger.recoveryAttempts(account, setOf(row.identity.stableKey)).isEmpty())
    }

    @Test
    fun aPreexistingMatchIsObservedWithoutUploadingOrClaimingOwnership(): Unit = runBlocking {
        val row = row(day)
        val (_, ledger) = fixture(listOf(row))
        val cloud = Cloud().apply { persisted += row.cloudRow() }
        val diagnostic = runner(cloud, ledger).recoverOne(account, plan(listOf(row)), true)
        assertEquals("ALREADY_PRESENT", diagnostic.pendingRecovery?.status)
        assertEquals(1, diagnostic.alreadyPresentRawRows)
        assertEquals(0, diagnostic.attemptedRawRows)
        assertEquals(listOf("read"), cloud.events)
        assertTrue(ledger.pendingIdentities(account, setOf(row.identity.stableKey)).isEmpty())
        assertTrue(ledger.ownedVerifiedFingerprints(account).isEmpty())
        assertTrue(ledger.recoveryAttempts(account, setOf(row.identity.stableKey)).isEmpty())
    }

    @Test
    fun tombstonesOtherSourcesAndConflictingValuesBlockEvenWhenNormalVerifierMightSayMissing(): Unit = runBlocking {
        val row = row(day)
        val cases = listOf(
            listOf(row.cloudRow().copy(deleted = true)) to "TOMBSTONE_PRESENT",
            listOf(row.cloudRow().copy(sid = "another-source")) to "OTHER_SOURCE_PRESENT",
            listOf(row.cloudRow().copy(sid = null)) to "OTHER_SOURCE_PRESENT",
            listOf(this@MiPendingSpO2RecoveryTest.row(day, 97).cloudRow()) to "CONFLICT",
            listOf(row.cloudRow(), this@MiPendingSpO2RecoveryTest.row(day, 97).cloudRow()) to "CONFLICT",
        )
        for ((observed, expected) in cases) {
            val (_, ledger) = fixture(listOf(row))
            val cloud = Cloud().apply { persisted += observed }
            val diagnostic = runner(cloud, ledger).recoverOne(account, plan(listOf(row)), true)
            assertEquals(expected, diagnostic.pendingRecovery?.status)
            assertEquals(0, diagnostic.attemptedRawRows)
            assertEquals(1, diagnostic.blockedConflictRows)
            assertTrue(cloud.uploads.isEmpty())
            assertTrue(ledger.recoveryAttempts(account, setOf(row.identity.stableKey)).isEmpty())
        }
    }

    @Test
    fun incompleteNativeHistoryNeverAuthorizesAnAbsentTargetRetry(): Unit = runBlocking {
        val row = row(day)
        val (_, ledger) = fixture(listOf(row))
        val cloud = Cloud().apply { onHistory = { MiHealthImportHistoryRead(emptyList(), 32, false) } }
        val diagnostic = runner(cloud, ledger).recoverOne(account, plan(listOf(row)), true)
        assertEquals("HISTORY_INCOMPLETE", diagnostic.pendingRecovery?.status)
        assertEquals(false, diagnostic.pendingRecovery?.historyComplete)
        assertEquals(32, diagnostic.pendingRecovery?.historyPagesRead)
        assertEquals(listOf("read", "history"), cloud.events)
        assertTrue(ledger.recoveryAttempts(account, setOf(row.identity.stableKey)).isEmpty())
        assertEquals(0, diagnostic.attemptedRawRows)
    }

    @Test
    fun nativeOnlyMatchesCanBeObservedWithoutWritingEvenWhenHistoryTraversalIsPartial(): Unit = runBlocking {
        val target = row(day)
        for (complete in listOf(false, true)) {
            val (_, ledger) = fixture(listOf(target))
            val cloud = Cloud().apply {
                onRead = { emptyList() }
                onHistory = { MiHealthImportHistoryRead(listOf(target.cloudRow()), 1, complete) }
            }
            val diagnostic = runner(cloud, ledger).recoverOne(account, plan(listOf(target)), true)
            assertEquals("ALREADY_PRESENT", diagnostic.pendingRecovery?.status)
            assertEquals(complete, diagnostic.pendingRecovery?.historyComplete)
            assertEquals(1, diagnostic.alreadyPresentRawRows)
            assertEquals(0, diagnostic.attemptedRawRows)
            assertEquals(listOf("read", "history"), cloud.events)
            assertTrue(ledger.pendingIdentities(account, setOf(target.identity.stableKey)).isEmpty())
            assertEquals(1, ledger.verifiedFingerprints(account).size)
            assertTrue(ledger.ownedVerifiedFingerprints(account).isEmpty())
        }
    }

    @Test
    fun partialNativeHistoryStillReportsVisibleTombstonesOtherSourcesAndConflicts(): Unit = runBlocking {
        val target = row(day)
        for ((observed, expected) in listOf(
            listOf(target.cloudRow().copy(deleted = true)) to "TOMBSTONE_PRESENT",
            listOf(target.cloudRow(), target.cloudRow().copy(sid = "other-source")) to "OTHER_SOURCE_PRESENT",
            listOf(target.cloudRow(), row(day, 97).cloudRow()) to "CONFLICT",
        )) {
            val (_, ledger) = fixture(listOf(target))
            val cloud = Cloud().apply { onHistory = { MiHealthImportHistoryRead(observed, 32, false) } }
            val diagnostic = runner(cloud, ledger).recoverOne(account, plan(listOf(target)), true)
            assertEquals(expected, diagnostic.pendingRecovery?.status)
            assertEquals(false, diagnostic.pendingRecovery?.historyComplete)
            assertEquals(0, diagnostic.attemptedRawRows)
            assertTrue(ledger.recoveryAttempts(account, setOf(target.identity.stableKey)).isEmpty())
            assertEquals(setOf(target.identity.stableKey), ledger.pendingIdentities(account, setOf(target.identity.stableKey)))
        }
    }

    @Test
    fun aTargetThatChangesAtTheFinalNarrowReadIsNotUploaded(): Unit = runBlocking {
        val row = row(day)
        val (_, ledger) = fixture(listOf(row))
        val cloud = Cloud().apply {
            onRead = { index -> if (index == 2) listOf(row.cloudRow().copy(sid = "concurrent-source")) else emptyList() }
        }
        val diagnostic = runner(cloud, ledger).recoverOne(account, plan(listOf(row)), true)
        assertEquals("OTHER_SOURCE_PRESENT", diagnostic.pendingRecovery?.status)
        assertEquals(listOf("read", "history", "read"), cloud.events)
        assertTrue(ledger.recoveryAttempts(account, setOf(row.identity.stableKey)).isEmpty())
        assertTrue(cloud.uploads.isEmpty())
    }

    @Test
    fun changedContentSourceIdentityOrKnownZoneCannotReuseOldPending(): Unit = runBlocking {
        val original = row(day)
        val changed = listOf(row(day, 97), original.copy(sourceRecordKeys = setOf("changed-record")),
            original.copy(zoneOffsetSeconds = 0), original.copy(dayAggregationEligible = false))
        for (current in changed) {
            val (_, ledger) = fixture(listOf(original))
            val cloud = Cloud()
            val diagnostic = runner(cloud, ledger).recoverOne(account, plan(listOf(current)), true)
            assertEquals("SOURCE_CHANGED", diagnostic.pendingRecovery?.status)
            assertTrue(cloud.events.isEmpty())
            assertTrue(ledger.recoveryAttempts(account, setOf(original.identity.stableKey)).isEmpty())
        }
    }

    @Test
    fun sourceCollisionIsRejectedWithoutSelectingAnArbitraryPayload(): Unit = runBlocking {
        val original = row(day)
        val (_, ledger) = fixture(listOf(original))
        val cloud = Cloud()
        val diagnostic = runner(cloud, ledger).recoverOne(account, plan(listOf(original, row(day, 97))), true)
        assertEquals("SOURCE_COLLISION", diagnostic.pendingRecovery?.status)
        assertEquals(1, diagnostic.sourceCollisionRows)
        assertTrue(cloud.events.isEmpty())
    }

    @Test
    fun atomicSnapshotChangeBetweenReadsAndPostStopsTheWrite(): Unit = runBlocking {
        val row = row(day)
        val (_, ledger) = fixture(listOf(row))
        val racing = object : HealthImportJournal by ledger {
            override suspend fun reserveSingleRecovery(account: String, expected: HealthImportPendingSnapshot,
                entry: HealthImportJournalEntry): HealthImportRecoveryReservation? {
                ledger.markPending(account, listOf(entry.copy(contentFingerprint = "changed-before-post")))
                return ledger.reserveSingleRecovery(account, expected, entry)
            }
        }
        val cloud = Cloud()
        val diagnostic = runner(cloud, racing).recoverOne(account, plan(listOf(row)), true)
        assertEquals("RESERVATION_CHANGED", diagnostic.pendingRecovery?.status)
        assertEquals(0, diagnostic.attemptedRawRows)
        assertTrue(cloud.uploads.isEmpty())
        assertTrue(ledger.recoveryAttempts(account, setOf(row.identity.stableKey)).isEmpty())
    }

    @Test
    fun lostPostResponseStaysConsumedAfterRestartAndDoesNotAdvanceToAnotherPendingTarget(): Unit = runBlocking {
        val rows = listOf(row(day), row(day + 60))
        val (store, ledger) = fixture(rows)
        val cloud = Cloud().apply { onUpload = { throw MiApiException.AuthExpired() } }
        val first = runner(cloud, ledger).recoverOne(account, plan(rows), true)
        assertEquals("UPLOAD_UNVERIFIED", first.pendingRecovery?.status)
        assertEquals(1, first.attemptedRawRows)
        assertEquals(1, first.readbackMissingRows)
        assertEquals(true, first.pendingRecovery?.consumedThisRun)
        val second = runner(cloud, HealthImportLedger(store)).recoverOne(account, plan(rows), true)
        assertEquals("RETRY_ALREADY_CONSUMED", second.pendingRecovery?.status)
        assertEquals(true, second.pendingRecovery?.previouslyConsumed)
        assertEquals(2, second.pendingRecovery?.candidateRows)
        assertEquals(0, second.attemptedRawRows)
        assertEquals(listOf(listOf(rows.first())), cloud.uploads)
        assertEquals(rows.map { it.identity.stableKey }.toSet(), ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()))
    }

    @Test
    fun cancellationAfterReservationCannotRestoreTheOneUseAllowance(): Unit = runBlocking {
        val row = row(day)
        val (store, ledger) = fixture(listOf(row))
        val cloud = Cloud().apply { onUpload = { throw CancellationException("synthetic cancellation") } }
        assertFailsWith<CancellationException> { runner(cloud, ledger).recoverOne(account, plan(listOf(row)), true) }
        val snapshot = HealthImportLedger(store).pendingSnapshots(account, setOf(row.identity.stableKey)).values.single()
        assertTrue(snapshot.recoveryConsumed)
        assertEquals(HealthImportRecoveryOutcome.RESERVED.name, snapshot.recoveryAttempt?.outcome)
        val second = runner(cloud, HealthImportLedger(store)).recoverOne(account, plan(listOf(row)), true)
        assertEquals("RETRY_ALREADY_CONSUMED", second.pendingRecovery?.status)
        assertEquals(1, cloud.uploads.size)
    }

    @Test
    fun explicitRejectionAndReadbackStatusArePersistedWithoutAutomaticRetry(): Unit = runBlocking {
        val row = row(day)
        val (store, ledger) = fixture(listOf(row))
        val cloud = Cloud().apply { onUpload = { MiHealthImportUploadResult(false, 100, "invalid") } }
        val diagnostic = runner(cloud, ledger).recoverOne(account, plan(listOf(row)), true)
        assertEquals("UPLOAD_REJECTED", diagnostic.pendingRecovery?.status)
        assertEquals(100, diagnostic.uploadResponse?.businessCode)
        assertEquals(false, diagnostic.uploadResponse?.accepted)
        val saved = HealthImportLedger(store).recoveryAttempts(account, setOf(row.identity.stableKey)).values.single()
        assertEquals(HealthImportRecoveryOutcome.REJECTED.name, saved.outcome)
        assertEquals(false, saved.accepted)
        assertEquals(100, saved.businessCode)
        assertEquals("MISSING", saved.readbackStatus)
        val second = runner(cloud, HealthImportLedger(store)).recoverOne(account, plan(listOf(row)), true)
        assertEquals("RETRY_ALREADY_CONSUMED", second.pendingRecovery?.status)
        assertEquals(1, cloud.uploads.size)
        assertEquals(false, second.pendingRecovery?.previousAttempt?.accepted)
        assertEquals(100, second.pendingRecovery?.previousAttempt?.businessCode)
        assertEquals(MiRecoveryReadbackStatus.MISSING, second.pendingRecovery?.previousAttempt?.readbackStatus)
    }

    @Test
    fun codeZeroWithoutExplicitAcceptancePreservesUnknownHistoryAndConsumedAllowance(): Unit = runBlocking {
        val targets = listOf(row(day), row(day + 60))
        val identities = targets.map { it.identity.stableKey }.toSet()
        val (store, ledger) = fixture(targets)
        val cloud = Cloud().apply {
            onUpload = { parseMiHealthImportUploadResponse(buildJsonObject {
                put("code", 0); put("message", "ok"); put("result", buildJsonObject {})
            }) }
            onRead = { index ->
                if (index == 3) {
                    val afterPost = HealthImportLedger(store).recoveryAttempts(account, identities).values.single()
                    assertEquals(HealthImportRecoveryOutcome.TRANSPORT_UNKNOWN.name, afterPost.outcome)
                    assertEquals(null, afterPost.accepted)
                    assertEquals(0, afterPost.businessCode)
                }
                emptyList()
            }
        }

        val first = runner(cloud, ledger).recoverOne(account, plan(targets), true)
        assertEquals("UPLOAD_UNVERIFIED", first.pendingRecovery?.status)
        assertEquals(1, first.attemptedRawRows)
        assertEquals(1, first.readbackMissingRows)
        assertEquals(true, first.pendingRecovery?.consumedThisRun)
        assertEquals(0, first.uploadResponse?.businessCode)
        assertEquals(null, first.uploadResponse?.accepted)
        assertEquals(MiHealthImportTransportFailure.INVALID_RESPONSE, first.uploadResponse?.transportFailure)
        assertEquals(2, cloud.historyLimits.size)

        val reopened = HealthImportLedger(store)
        val saved = reopened.recoveryAttempts(account, identities).values.single()
        assertEquals(HealthImportRecoveryOutcome.READBACK_UNVERIFIED.name, saved.outcome)
        assertEquals(null, saved.accepted)
        assertEquals(0, saved.businessCode)
        assertEquals("MISSING", saved.readbackStatus)
        assertTrue(reopened.pendingSnapshots(account, identities).getValue(targets.first().identity.stableKey).recoveryConsumed)
        val second = runner(cloud, reopened).recoverOne(account, plan(targets), true)
        assertEquals("RETRY_ALREADY_CONSUMED", second.pendingRecovery?.status)
        assertEquals(0, second.attemptedRawRows)
        assertEquals(null, second.pendingRecovery?.previousAttempt?.accepted)
        assertEquals(0, second.pendingRecovery?.previousAttempt?.businessCode)
        assertEquals(MiRecoveryReadbackStatus.MISSING, second.pendingRecovery?.previousAttempt?.readbackStatus)
        assertEquals(listOf(listOf(targets.first())), cloud.uploads)
        assertEquals(identities, reopened.pendingIdentities(account, identities))
        assertTrue(reopened.verifiedFingerprints(account).isEmpty())
    }

    @Test
    fun malformedAcceptanceCanStillVerifyTheSingleRecoveryByReadback(): Unit = runBlocking {
        val targets = listOf(row(day), row(day + 60))
        val identities = targets.map { it.identity.stableKey }.toSet()
        val (store, ledger) = fixture(targets)
        val cloud = Cloud()
        cloud.onUpload = { submitted ->
            cloud.persisted += submitted.map { it.cloudRow() }
            parseMiHealthImportUploadResponse(buildJsonObject {
                put("code", 0); put("message", "ok"); put("result", buildJsonObject {})
            })
        }

        val diagnostic = runner(cloud, ledger).recoverOne(account, plan(targets), true)
        assertEquals("VERIFIED", diagnostic.pendingRecovery?.status)
        assertEquals(1, diagnostic.attemptedRawRows)
        assertEquals(1, diagnostic.newlyVerifiedRawRows)
        assertEquals(0, diagnostic.uploadResponse?.businessCode)
        assertEquals(null, diagnostic.uploadResponse?.accepted)
        assertEquals(MiHealthImportTransportFailure.INVALID_RESPONSE, diagnostic.uploadResponse?.transportFailure)
        assertEquals(listOf(listOf(targets.first())), cloud.uploads)
        assertEquals(listOf("read", "history", "read", "upload", "read"), cloud.events)
        val reopened = HealthImportLedger(store)
        assertEquals(setOf(targets.last().identity.stableKey), reopened.pendingIdentities(account, identities))
        assertEquals(1, reopened.verifiedFingerprints(account).size)
        val saved = reopened.recoveryAttempts(account, identities).values.single()
        assertEquals(HealthImportRecoveryOutcome.VERIFIED.name, saved.outcome)
        assertEquals(null, saved.accepted)
        assertEquals(0, saved.businessCode)
        assertEquals("VERIFIED", saved.readbackStatus)
    }

    @Test
    fun preflightAuthenticationFailureCanRefreshButPostAuthenticationFailureCannotEscape(): Unit = runBlocking {
        val row = row(day)
        val (_, ledger) = fixture(listOf(row))
        val before = Cloud().apply { onRead = { throw MiApiException.AuthExpired() } }
        assertFailsWith<MiApiException.AuthExpired> { runner(before, ledger).recoverOne(account, plan(listOf(row)), true) }
        assertTrue(ledger.recoveryAttempts(account, setOf(row.identity.stableKey)).isEmpty())
        val after = Cloud().apply {
            onUpload = { MiHealthImportUploadResult(true, 0, "ok") }
            onRead = { index -> if (index > 2) throw MiApiException.AuthExpired() else emptyList() }
            onHistory = { if (uploads.isNotEmpty()) throw MiApiException.AuthExpired()
                else MiHealthImportHistoryRead(emptyList(), 1, true) }
        }
        val result = runner(after, ledger).recoverOne(account, plan(listOf(row)), true)
        assertEquals("UPLOAD_UNVERIFIED", result.pendingRecovery?.status)
        assertEquals(1, result.readbackUnqueriedRows)
        assertEquals(1, after.uploads.size)
        assertEquals(true, ledger.recoveryAttempts(account, setOf(row.identity.stableKey)).values.single().accepted)
    }

    @Test
    fun ordinaryReadErrorsRemainUnqueriedAndCannotConsumeRecovery(): Unit = runBlocking {
        val row = row(day)
        val (_, ledger) = fixture(listOf(row))
        val cloud = Cloud().apply { onHistory = { error("synthetic unavailable history") } }
        val result = runner(cloud, ledger).recoverOne(account, plan(listOf(row)), true)
        assertEquals("READ_FAILED", result.pendingRecovery?.status)
        assertEquals(0, result.attemptedRawRows)
        assertFalse(result.pendingRecovery?.consumedThisRun == true)
        assertTrue(ledger.recoveryAttempts(account, setOf(row.identity.stableKey)).isEmpty())
    }

    @Test
    fun failedOutcomePersistenceAfterPostReadbackKeepsPendingAndCannotCauseAnotherPost(): Unit = runBlocking {
        val target = row(day)
        val (_, ledger) = fixture(listOf(target))
        val failing = object : HealthImportJournal by ledger {
            override suspend fun reconcileObservedPending(account: String,
                entries: List<HealthImportPendingReconciliation>): Set<String> = error("synthetic atomic save failure")
            override suspend fun recordRecoveryOutcome(account: String, reservation: HealthImportRecoveryReservation,
                outcome: HealthImportRecoveryOutcome, accepted: Boolean?, businessCode: Int?, readbackStatus: String?): Boolean =
                if (outcome == HealthImportRecoveryOutcome.VERIFIED) false
                else ledger.recordRecoveryOutcome(account, reservation, outcome, accepted, businessCode, readbackStatus)
        }
        val cloud = Cloud()
        cloud.onUpload = { submitted ->
            cloud.persisted += submitted.map { it.cloudRow() }
            MiHealthImportUploadResult(true, 0, "ok")
        }
        val first = runner(cloud, failing).recoverOne(account, plan(listOf(target)), true)
        assertEquals("UPLOAD_UNVERIFIED", first.pendingRecovery?.status)
        assertEquals(1, first.attemptedRawRows)
        assertEquals(0, first.verifiedRawRows)
        assertEquals(setOf(target.identity.stableKey), ledger.pendingIdentities(account, setOf(target.identity.stableKey)))
        assertTrue(ledger.verifiedFingerprints(account).isEmpty())
        val second = runner(cloud, failing).recoverOne(account, plan(listOf(target)), true)
        assertEquals("READ_FAILED", second.pendingRecovery?.status)
        assertEquals(0, second.attemptedRawRows)
        assertEquals(0, second.verifiedRawRows)
        assertEquals(1, cloud.uploads.size)
        assertEquals(setOf(target.identity.stableKey), ledger.pendingIdentities(account, setOf(target.identity.stableKey)))
    }

    @Test
    fun failedOutcomePersistenceForAnOlderConsumedMatchDoesNotClearPendingOrUpload(): Unit = runBlocking {
        val target = row(day)
        val (store, ledger) = fixture(listOf(target))
        val identity = setOf(target.identity.stableKey)
        val snapshot = ledger.pendingSnapshots(account, identity).getValue(target.identity.stableKey)
        val reservation = ledger.reserveSingleRecovery(account, snapshot, MiHealthImportRunner.journalEntry(target))!!
        assertTrue(ledger.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.ACCEPTED,
            accepted = true, businessCode = 0))
        val reopened = HealthImportLedger(store)
        val failing = object : HealthImportJournal by reopened {
            override suspend fun reconcileObservedPending(account: String,
                entries: List<HealthImportPendingReconciliation>): Set<String> = error("synthetic atomic save failure")
        }
        val cloud = Cloud().apply { persisted += target.cloudRow() }
        repeat(2) {
            val result = runner(cloud, failing).recoverOne(account, plan(listOf(target)), true)
            assertEquals("READ_FAILED", result.pendingRecovery?.status)
            assertEquals(0, result.attemptedRawRows)
            assertEquals(0, result.verifiedRawRows)
            assertEquals(identity, reopened.pendingIdentities(account, identity))
        }
        assertTrue(cloud.uploads.isEmpty())
        assertTrue(reopened.verifiedFingerprints(account).isEmpty())
        assertEquals(HealthImportRecoveryOutcome.ACCEPTED.name,
            reopened.recoveryAttempts(account, identity).values.single().outcome)
    }

    @Test
    fun consumedLegacyRowShowsStoredAcceptanceAndReadbackWithoutSendingAnotherPost(): Unit = runBlocking {
        val targets = listOf(row(day), row(day + 60))
        val (store, ledger) = fixture(targets, legacy = true)
        val cloud = Cloud()
        val first = runner(cloud, ledger).recoverOne(account, plan(targets), true)
        assertEquals("UPLOAD_UNVERIFIED", first.pendingRecovery?.status)
        assertEquals(1, first.attemptedRawRows)
        val next = runner(cloud, HealthImportLedger(store)).recoverOne(account, plan(targets), true)
        assertEquals("RETRY_ALREADY_CONSUMED", next.pendingRecovery?.status)
        assertEquals(true, next.pendingRecovery?.previousAttempt?.accepted)
        assertEquals(0, next.pendingRecovery?.previousAttempt?.businessCode)
        assertEquals(MiRecoveryReadbackStatus.MISSING, next.pendingRecovery?.previousAttempt?.readbackStatus)
        assertEquals(true, next.pendingRecovery?.previousAttempt?.legacyWireMetadataUnknown)
        assertEquals(MiHealthImportVerificationStatus.MISSING, next.pendingRecovery?.precheckStatus)
        assertEquals(0, next.attemptedRawRows)
        assertEquals(null, next.uploadResponse)
        assertEquals(1, cloud.uploads.size)
        assertEquals(2, ledger.pendingIdentities(account, targets.map { it.identity.stableKey }.toSet()).size)
    }

    @Test
    fun nativeOnlyPostReadbackCanVerifyEvenOnPartialHistoryWithoutASecondUpload(): Unit = runBlocking {
        val target = row(day)
        val (_, ledger) = fixture(listOf(target))
        val cloud = Cloud().apply {
            onRead = { emptyList() }
            onHistory = { if (uploads.isEmpty()) MiHealthImportHistoryRead(emptyList(), 1, true)
                else MiHealthImportHistoryRead(listOf(target.cloudRow()), 2, false, MiHistoryStopReason.PAGE_LIMIT) }
        }
        val result = runner(cloud, ledger).recoverOne(account, plan(listOf(target)), true)
        assertEquals("VERIFIED", result.pendingRecovery?.status)
        assertEquals(1, result.newlyVerifiedRawRows)
        assertEquals(1, cloud.uploads.size)
        assertEquals(2, result.pendingRecovery?.nativeReadbackPages)
        assertEquals(false, result.pendingRecovery?.nativeReadbackComplete)
        assertEquals(MiHealthImportVerificationStatus.VERIFIED, result.pendingRecovery?.nativeReadbackStatus)
        assertTrue(ledger.pendingIdentities(account, setOf(target.identity.stableKey)).isEmpty())
        assertEquals(1, ledger.ownedVerifiedFingerprints(account).size)
    }

    @Test
    fun nativePositiveCannotEraseAnEarlierReadbackTombstoneOtherSourceOrDifferentValue(): Unit = runBlocking {
        val target = row(day)
        for (conflict in listOf(target.cloudRow().copy(deleted = true), target.cloudRow().copy(sid = "other"),
            row(day, 96).cloudRow())) {
            val (_, ledger) = fixture(listOf(target))
            val cloud = Cloud().apply {
                onRead = { index -> if (index == 3) listOf(conflict) else emptyList() }
                onHistory = { if (uploads.isEmpty()) MiHealthImportHistoryRead(emptyList(), 1, true)
                    else MiHealthImportHistoryRead(listOf(target.cloudRow()), 1, true) }
            }
            val result = runner(cloud, ledger).recoverOne(account, plan(listOf(target)), true)
            assertEquals("UPLOAD_UNVERIFIED", result.pendingRecovery?.status)
            assertEquals(0, result.verifiedRawRows)
            assertEquals(1, result.readbackAmbiguousRows)
            assertEquals(1, cloud.uploads.size)
            assertEquals(1, ledger.pendingIdentities(account, setOf(target.identity.stableKey)).size)
        }
    }

    @Test
    fun laterWindowMatchCannotEraseAnEarlierConflictingReadback(): Unit = runBlocking {
        val target = row(day)
        val (_, ledger) = fixture(listOf(target))
        val cloud = Cloud().apply {
            onRead = { index -> when { index < 3 -> emptyList()
                index == 3 -> listOf(row(day, 96).cloudRow())
                else -> listOf(target.cloudRow()) } }
        }
        val result = runner(cloud, ledger).recoverOne(account, plan(listOf(target)), true)
        assertEquals("UPLOAD_UNVERIFIED", result.pendingRecovery?.status)
        assertEquals(1, result.readbackAmbiguousRows)
        assertEquals(0, result.newlyVerifiedRawRows)
    }

    @Test
    fun nativeIncompleteAbsenceRemainsUnqueriedAndPreservesConsumedAllowance(): Unit = runBlocking {
        val target = row(day)
        val (_, ledger) = fixture(listOf(target))
        val cloud = Cloud().apply {
            onHistory = { if (uploads.isEmpty()) MiHealthImportHistoryRead(emptyList(), 1, true)
                else MiHealthImportHistoryRead(emptyList(), 32, false, MiHistoryStopReason.PAGE_LIMIT) }
        }
        val result = runner(cloud, ledger).recoverOne(account, plan(listOf(target)), true)
        assertEquals("UPLOAD_UNVERIFIED", result.pendingRecovery?.status)
        assertEquals(0, result.readbackMissingRows)
        assertEquals(1, result.readbackUnqueriedRows)
        assertEquals(null, result.pendingRecovery?.nativeReadbackStatus)
        assertEquals("UNQUERIED", ledger.recoveryAttempts(account, setOf(target.identity.stableKey)).values.single().readbackStatus)
        assertEquals(1, cloud.uploads.size)
    }

    @Test
    fun nativeReadbackAuthErrorCannotEscapeOrRetainAnOutdatedMissingClassification(): Unit = runBlocking {
        val target = row(day)
        val (_, ledger) = fixture(listOf(target))
        val cloud = Cloud().apply {
            onHistory = { if (uploads.isEmpty()) MiHealthImportHistoryRead(emptyList(), 1, true)
                else throw MiApiException.AuthExpired() }
        }
        val result = runner(cloud, ledger).recoverOne(account, plan(listOf(target)), true)
        assertEquals("UPLOAD_UNVERIFIED", result.pendingRecovery?.status)
        assertEquals(true, result.pendingRecovery?.nativeReadbackFailed)
        assertEquals(MiHealthImportTransportFailure.AUTH, result.readResponse?.transportFailure)
        assertEquals(1, result.readbackUnqueriedRows)
        assertEquals(0, result.readbackMissingRows)
        assertEquals(1, cloud.uploads.size)
    }

    @Test
    fun readFailuresCannotEraseAnObservedPostUploadConflict(): Unit = runBlocking {
        val target = row(day)
        for ((conflict, field, status) in listOf(
            Triple(target.cloudRow().copy(deleted = true), "deleted", "AMBIGUOUS"),
            Triple(target.cloudRow().copy(sid = "other"), "sid", "AMBIGUOUS"),
            Triple(row(day, 96).cloudRow(), "spo2", "MISMATCH"),
        )) {
            val (_, ledger) = fixture(listOf(target))
            val cloud = Cloud().apply {
                onRead = { index -> when {
                    index < 3 -> emptyList()
                    index == 3 -> listOf(conflict)
                    else -> throw MiApiException.AuthExpired()
                } }
                onHistory = { if (uploads.isEmpty()) MiHealthImportHistoryRead(emptyList(), 1, true)
                    else throw MiApiException.AuthExpired() }
            }
            val result = runner(cloud, ledger).recoverOne(account, plan(listOf(target)), true)
            assertEquals("UPLOAD_UNVERIFIED", result.pendingRecovery?.status)
            assertEquals(0, result.verifiedRawRows)
            assertEquals(0, result.readbackUnqueriedRows)
            assertEquals(if (status == "AMBIGUOUS") 1 else 0, result.readbackAmbiguousRows)
            assertEquals(if (status == "MISMATCH") 1 else 0, result.readbackMismatchedRows)
            assertEquals(1, result.readbackMismatchFields[field])
            assertEquals(true, result.pendingRecovery?.nativeReadbackFailed)
            assertEquals(MiHealthImportTransportFailure.AUTH, result.readResponse?.transportFailure)
            assertEquals(status, ledger.recoveryAttempts(account, setOf(target.identity.stableKey)).values.single().readbackStatus)
            assertEquals(1, cloud.uploads.size)
            assertEquals(1, ledger.pendingIdentities(account, setOf(target.identity.stableKey)).size)
        }
    }

    @Test
    fun incompleteNativeAbsenceNeverPersistsATransientMissingEvenIfFinalSaveFails(): Unit = runBlocking {
        val target = row(day)
        for (failFinalSave in listOf(false, true)) {
            val (_, ledger) = fixture(listOf(target))
            var postNativeRead = false
            val nativeSavedStatuses = mutableListOf<String?>()
            val journal = object : HealthImportJournal by ledger {
                override suspend fun recordRecoveryOutcome(account: String, reservation: HealthImportRecoveryReservation,
                    outcome: HealthImportRecoveryOutcome, accepted: Boolean?, businessCode: Int?, readbackStatus: String?): Boolean {
                    if (postNativeRead) {
                        nativeSavedStatuses += readbackStatus
                        if (failFinalSave) throw IllegalStateException("synthetic storage failure")
                    }
                    return ledger.recordRecoveryOutcome(account, reservation, outcome, accepted, businessCode, readbackStatus)
                }
            }
            val cloud = Cloud().apply {
                onRead = { index -> if (index < 3) emptyList() else throw MiApiException.AuthExpired() }
                onHistory = { if (uploads.isEmpty()) MiHealthImportHistoryRead(emptyList(), 1, true)
                    else {
                        postNativeRead = true
                        MiHealthImportHistoryRead(emptyList(), 32, false, MiHistoryStopReason.PAGE_LIMIT)
                    } }
            }
            val result = runner(cloud, journal).recoverOne(account, plan(listOf(target)), true)
            assertEquals(listOf<String?>("UNQUERIED"), nativeSavedStatuses.toList())
            assertEquals("UNQUERIED", ledger.recoveryAttempts(account, setOf(target.identity.stableKey)).values.single().readbackStatus)
            assertEquals(0, result.readbackMissingRows)
            assertEquals(1, result.readbackUnqueriedRows)
            assertEquals(1, cloud.uploads.size)
            assertTrue(ledger.pendingSnapshots(account, setOf(target.identity.stableKey)).values.single().recoveryConsumed)
        }
    }

    @Test
    fun readOnlyReconciliationChecksPendingSnapshotAndDoesNotClearAConcurrentChange(): Unit = runBlocking {
        val target = row(day)
        val (_, ledger) = fixture(listOf(target))
        val racing = object : HealthImportJournal by ledger {
            override suspend fun reconcileObservedPending(account: String,
                entries: List<HealthImportPendingReconciliation>): Set<String> {
                ledger.markPending(account, listOf(MiHealthImportRunner.journalEntry(target).copy(contentFingerprint = "changed")))
                return ledger.reconcileObservedPending(account, entries)
            }
        }
        val cloud = Cloud().apply { persisted += target.cloudRow() }
        val result = runner(cloud, racing).recoverOne(account, plan(listOf(target)), true)
        assertEquals("RESERVATION_CHANGED", result.pendingRecovery?.status)
        assertEquals(0, result.verifiedRawRows)
        assertEquals("changed", ledger.pendingSnapshots(account, setOf(target.identity.stableKey)).values.single().contentFingerprint)
        assertTrue(cloud.uploads.isEmpty())
    }

    @Test fun heartRecoveryIsolatesSourceConflictsToTheirKnownLocalDay(): Unit = runBlocking {
        val target = heartRow(day + 1000)
        val localDay = (target.identity.time + target.zoneOffsetSeconds).floorDiv(86_400)
        for (affected in listOf(setOf(localDay + 1), setOf(localDay), null)) {
            val (_, ledger) = fixture(listOf(target))
            val cloud = Cloud("heart_rate").apply { onUpload = { persisted += it.map { r -> r.cloudRow() }; MiHealthImportUploadResult(true, 0, "ok") } }
            val plan = plan(listOf(target)).copy(metric = HealthInboundMetric.HEART_RATE,
                issues = listOf(MiHealthImportIssue("TARGET_TIME_COLLISION", setOf("conflict"), "synthetic", affectedLocalDays = affected)))
            val result = runner(cloud, ledger).recoverOne(account, plan, true)
            assertEquals(if (affected == setOf(localDay + 1)) 1 else 0, cloud.uploads.size)
            assertEquals(if (affected == setOf(localDay + 1)) "VERIFIED" else "SOURCE_CHANGED", result.pendingRecovery?.status)
        }
    }

    @Test fun sharedHistoryCannotBypassFreshPrewriteConflict(): Unit = runBlocking {
        val rows = listOf(heartRow(day + 1000), heartRow(day + 1060))
        val (_, ledger) = fixture(rows)
        val cloud = Cloud("heart_rate").apply {
            onUpload = { persisted += it.map { r -> r.cloudRow() }; MiHealthImportUploadResult(true, 0, "ok") }
            onRead = { index -> if (index >= 4) persisted + rows.last().cloudRow().copy(deleted = true) else persisted.toList() }
        }
        val result = MiPendingImportRecovery(cloud, ledger).recover(account, plan(rows).copy(metric = HealthInboundMetric.HEART_RATE), true)
        assertEquals(1, result.verifiedRawRows)
        assertEquals(1, cloud.uploads.size)
        assertEquals("TOMBSTONE_PRESENT", result.pendingRecovery?.status)
        assertFalse(ledger.pendingSnapshots(account, setOf(rows.last().identity.stableKey)).values.single().recoveryConsumed)
    }

    @Test fun boundedActionPreservesRemainingPendingAndReportsPartial(): Unit = runBlocking {
        val rows = listOf(heartRow(day + 1000), heartRow(day + 1060))
        val (_, ledger) = fixture(rows)
        val cloud = Cloud("heart_rate").apply { onUpload = { persisted += it.map { r -> r.cloudRow() }; MiHealthImportUploadResult(true, 0, "ok") } }
        val result = MiPendingImportRecovery(cloud, ledger).recover(account, plan(rows).copy(metric = HealthInboundMetric.HEART_RATE), true, maxRows = 1)
        assertEquals(1, result.verifiedRawRows)
        assertEquals(1, result.affectedRows)
        assertEquals("PENDING", result.failureReason)
        assertEquals(1, ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()).size)
    }

    @Test fun exactHeartCollisionBlocksOnlyItsOwnTargetEvenOnTheSameDay(): Unit = runBlocking {
        val target = heartRow(day + 1000)
        for (collisionTime in listOf(target.identity.time, target.identity.time + 60)) {
            val (_, ledger) = fixture(listOf(target))
            val cloud = Cloud("heart_rate").apply { onUpload = { persisted += it.map { r -> r.cloudRow() }; MiHealthImportUploadResult(true, 0, "ok") } }
            val plan = plan(listOf(target)).copy(metric = HealthInboundMetric.HEART_RATE,
                issues = listOf(MiHealthImportIssue("TARGET_TIME_COLLISION", setOf("conflict"), "synthetic",
                    affectedLocalDays = setOf((target.identity.time + target.zoneOffsetSeconds).floorDiv(86_400)),
                    affectedTarget = target.identity.copy(time = collisionTime))))
            val result = runner(cloud, ledger).recoverOne(account, plan, true)
            assertEquals(if (collisionTime == target.identity.time) "SOURCE_CHANGED" else "VERIFIED", result.pendingRecovery?.status)
            assertEquals(if (collisionTime == target.identity.time) 0 else 1, cloud.uploads.size)
        }
    }

    private fun heartRow(time: Long) = row(time).copy(
        identity = row(time).identity.copy(key = "heart_rate"),
        value = buildJsonObject { put("time", time); put("bpm", 70) })

    @Test fun heartRecoveryProcessesLegacyPendingWithoutMinuteNormalization(): Unit = runBlocking {
        val rows = (0 until 3).map { heartRow(day + 1000 + it * 60) }
        val (_, ledger) = fixture(rows, legacy = true)
        val cloud = Cloud("heart_rate").apply {
            onHistory = { MiHealthImportHistoryRead(persisted.toList(), 114, true) }
            onUpload = { persisted += it.map { r -> r.cloudRow() }; MiHealthImportUploadResult(true, 0, "ok") }
        }
        val result = MiPendingImportRecovery(cloud, ledger).recover(account,
            plan(rows).copy(metric = HealthInboundMetric.HEART_RATE), true)
        assertEquals(3, result.newlyVerifiedRawRows)
        assertEquals(3, result.attemptedRawRows)
        assertEquals(null, result.failureReason)
        assertEquals(listOf(128), cloud.historyLimits)
        assertTrue(ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()).isEmpty())
        assertEquals(rows.map { it.identity }, cloud.uploads.flatten().map { it.identity })
    }

    @Test fun recoveryStopsAtUnverifiedRowAndCannotSkipItOnNextAction(): Unit = runBlocking {
        val rows = (0 until 3).map { heartRow(day + 1000 + it * 60) }
        val (_, ledger) = fixture(rows)
        val cloud = Cloud("heart_rate").apply {
            onUpload = { if (uploads.size == 1) persisted += it.map { r -> r.cloudRow() }; MiHealthImportUploadResult(true, 0, "ok") }
        }
        val plan = plan(rows).copy(metric = HealthInboundMetric.HEART_RATE)
        val first = MiPendingImportRecovery(cloud, ledger).recover(account, plan, true)
        assertEquals(1, first.newlyVerifiedRawRows)
        assertEquals(2, first.attemptedRawRows)
        assertEquals(2, first.affectedRows)
        val again = MiPendingImportRecovery(cloud, ledger).recover(account, plan, true)
        assertEquals("RETRY_ALREADY_CONSUMED", again.pendingRecovery?.status)
        assertEquals(2, cloud.uploads.size)
    }

    @Test fun heartRecoveryBlocksIncompleteHistoryAndChangedSources(): Unit = runBlocking {
        val target = heartRow(day)
        val (_, ledger) = fixture(listOf(target))
        val cloud = Cloud("heart_rate").apply { onHistory = { MiHealthImportHistoryRead(emptyList(), 128, false, MiHistoryStopReason.PAGE_LIMIT) } }
        val plan = plan(listOf(target)).copy(metric = HealthInboundMetric.HEART_RATE)
        assertEquals("HISTORY_INCOMPLETE", runner(cloud, ledger).recoverOne(account, plan, true).pendingRecovery?.status)
        assertTrue(cloud.uploads.isEmpty())
        assertFalse(ledger.pendingSnapshots(account, setOf(target.identity.stableKey)).values.single().recoveryConsumed)
        val changed = target.copy(value = buildJsonObject { put("time", day); put("bpm", 71) })
        assertEquals("SOURCE_CHANGED", runner(cloud, ledger).recoverOne(account, plan.copy(rows = listOf(changed)), true).pendingRecovery?.status)
    }

    @Test fun heartRecoveryDoesNotUseSpo2MinuteAlias(): Unit = runBlocking {
        val target = heartRow(day + 1)
        val (_, ledger) = fixture(listOf(target))
        val cloud = Cloud("heart_rate").apply { persisted += heartRow(day).cloudRow() }
        val result = runner(cloud, ledger).recoverOne(account,
            plan(listOf(target)).copy(metric = HealthInboundMetric.HEART_RATE), true)
        assertEquals(0, result.verifiedRawRows)
        assertEquals(1, cloud.uploads.size)
    }

    private fun row(time: Long, spo2: Int = 98) = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, MI_HEALTH_CONNECT_IMPORT_SID, "spo2", time),
        buildJsonObject { put("time", time); put("spo2", spo2) }, 28_800, setOf("synthetic-source:$time"))
    private fun plan(rows: List<MiHealthImportRow>) = MiHealthImportPlan(HealthInboundMetric.OXYGEN_SATURATION, rows, emptyList(), rows.size)
    private fun MiHealthImportRow.cloudRow() = MiHealthImportCloudRow(identity.sid, identity.key,
        identity.time, identity.tag, value, zoneOffsetSeconds = zoneOffsetSeconds)
    private fun runner(cloud: Cloud, ledger: HealthImportJournal) = MiPendingSpO2Recovery(cloud, ledger, readbackPause = {})

    private suspend fun fixture(rows: List<MiHealthImportRow>, legacy: Boolean = false): Pair<MemoryStore, HealthImportLedger> {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        ledger.markPending(account, rows.map { MiHealthImportRunner.journalEntry(it).copy(
            zoneOffsetSeconds = if (legacy) null else it.zoneOffsetSeconds) })
        return store to ledger
    }

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    private class Cloud(private val expectedKey: String = "spo2") : MiHealthImportCloud {
        val persisted = mutableListOf<MiHealthImportCloudRow>()
        val uploads = mutableListOf<List<MiHealthImportRow>>()
        val events = mutableListOf<String>()
        val historyLimits = mutableListOf<Int>()
        val readWindows = mutableListOf<Pair<Long, Long>>()
        var reads = 0
        var respectWindow = false
        var onRead: (suspend (Int) -> List<MiHealthImportCloudRow>)? = null
        var onHistory: (suspend () -> MiHealthImportHistoryRead)? = null
        var onUpload: suspend (List<MiHealthImportRow>) -> MiHealthImportUploadResult = { MiHealthImportUploadResult(true, 0, "ok") }
        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long,
            untilEpochSeconds: Long): List<MiHealthImportCloudRow> {
            assertEquals(MiHealthImportChannel.RAW, channel)
            assertEquals(expectedKey, key)
            assertTrue(untilEpochSeconds > fromEpochSeconds)
            events += "read"
            readWindows += fromEpochSeconds to untilEpochSeconds
            reads++
            val rows = onRead?.invoke(reads) ?: persisted.toList()
            return if (respectWindow) rows.filter { it.time >= fromEpochSeconds && it.time < untilEpochSeconds } else rows
        }
        override suspend fun readRawRowsWithoutStart(key: String, untilEpochSeconds: Long,
            maxPages: Int): MiHealthImportHistoryRead {
            assertEquals(expectedKey, key)
            events += "history"
            historyLimits += maxPages
            return onHistory?.invoke() ?: MiHealthImportHistoryRead(persisted.toList(), 1, true)
        }
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
            assertTrue(experimentalEnabled)
            assertEquals(1, rows.size)
            assertEquals(MiHealthImportChannel.RAW, rows.single().identity.channel)
            events += "upload"
            uploads += rows.toList()
            return onUpload(rows)
        }
    }
}
