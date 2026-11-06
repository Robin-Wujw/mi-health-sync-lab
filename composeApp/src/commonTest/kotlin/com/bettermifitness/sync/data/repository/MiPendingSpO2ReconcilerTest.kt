package com.bettermifitness.sync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthRecordIds
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MiPendingSpO2ReconcilerTest {
    private val account = "synthetic-reconcile-account"
    private val day = 20_000L * 86_400L

    @Test
    fun comparisonUsesExistingReadsWithoutTreatingANearbyValueAsRecovery(): Unit = runBlocking {
        val matched = row(day + 600)
        val missing = row(day + 610)
        val rows = listOf(matched, missing)
        val fixture = fixture(listOf(missing))
        val ids = setOf(missing.identity.stableKey)
        val before = fixture.ledger.pendingSnapshots(account, ids)
        val cloud = Cloud().apply { persisted += matched.cloudRow() }
        val result = reconcile(cloud, fixture.guarded, plan(rows))
        val details = assertPartition(result)
        val comparison = assertNotNull(details.readComparison)
        assertEquals(2, comparison.sourceTargets)
        assertEquals(1, comparison.exactMatched.targets)
        assertEquals(1, comparison.exactMatched.minuteAlignedTargets)
        assertEquals(1, comparison.missing.targets)
        assertEquals(0, comparison.missing.minuteAlignedTargets)
        assertEquals(1, comparison.missingSameMinuteCandidateTargets)
        assertEquals(1, comparison.missingSameValueCandidateTargets)
        assertEquals(0, details.reconciledRows)
        assertEquals(0, result.verifiedRawRows)
        assertEquals(listOf("read", "history"), cloud.events)
        assertEquals(0, fixture.guarded.commits)
        assertEquals(before, fixture.ledger.pendingSnapshots(account, ids))
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun incompleteHistoryCannotBecomeMissingInComparisonEvenWithANearbyObservation(): Unit = runBlocking {
        val matched = row(day + 600)
        val unqueried = row(day + 610)
        val fixture = fixture(listOf(unqueried))
        val cloud = Cloud().apply {
            persisted += matched.cloudRow()
            onHistory = { _, _ -> MiHealthImportHistoryRead(listOf(matched.cloudRow()), 32, false) }
        }
        val result = reconcile(cloud, fixture.guarded, plan(listOf(matched, unqueried)))
        val details = assertPartition(result)
        val comparison = assertNotNull(details.readComparison)
        assertEquals(1, details.unqueriedRows)
        assertEquals(0, comparison.missing.targets)
        assertEquals(0, comparison.missingSameMinuteCandidateTargets)
        assertEquals(1, comparison.otherTargets)
        assertEquals(1, comparison.exactMatched.targets)
        assertEquals(0, fixture.guarded.commits)
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun latestUserCountShapeCanReconcileDuringOrdinarySyncWithoutAnyUpload(): Unit = runBlocking {
        val rows = (0 until 191).map { row(day + 1_000 + it) }
        val fixture = fixture(rows.drop(63), legacy = true)
        val delegate = Cloud().apply {
            onRead = { _, _, _ -> rows.take(63).map { it.cloudRow() } }
            onHistory = { _, _ -> MiHealthImportHistoryRead(rows.drop(63).map { it.cloudRow() }, 2, true) }
        }
        val cloud = MiHistoryObservingCloud(delegate)
        val result = MiPendingSpO2Reconciler(cloud, fixture.guarded)
            .reconcile(account, plan(rows), userRequested = false, automatic = true)
        assertEquals(128, assertPartition(result).reconciledRows)
        assertFalse(result.pendingReconcile!!.userRequested)
        cloud.beginImportPrecheck(rows)
        val ordinary = MiHealthImportRunner(cloud, fixture.ledger).run(account, plan(rows))
        assertEquals(191, ordinary.alreadyPresentRawCount)
        assertEquals(0, ordinary.newlyUploadedRawCount)
        assertEquals(0, delegate.uploadCalls)
        assertTrue(fixture.ledger.ownedVerifiedFingerprints(account).isEmpty())
    }

    @Test
    fun cappedInitialHistoryUsesAnOlderEndpointAndRetainsUnresolvedPending(): Unit = runBlocking {
        val rows = listOf(row(day + 100), row(day + 200))
        val fixture = fixture(rows)
        val cloud = Cloud().apply {
            onHistory = { until, limit ->
                if (until == rows.last().identity.time + 1)
                    MiHealthImportHistoryRead(emptyList(), limit, false, MiHistoryStopReason.PAGE_LIMIT)
                else MiHealthImportHistoryRead(listOf(rows.first().cloudRow()), 1, false, MiHistoryStopReason.PAGE_LIMIT)
            }
        }
        val result = reconcile(cloud, fixture.guarded, plan(rows))
        val diagnostic = assertPartition(result)
        assertEquals(1, diagnostic.reconciledRows)
        assertEquals(1, diagnostic.unqueriedRows)
        assertEquals(1, diagnostic.historyFollowUpQueries)
        assertEquals(33, diagnostic.historyPagesRead)
        assertEquals(0, diagnostic.missingRows)
        assertEquals(setOf(rows.last().identity.stableKey),
            fixture.ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()))
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun earlierBroadConflictCannotBeErasedByNativeMatchAndLaterWindowOmission(): Unit = runBlocking {
        val target = row(day + 100)
        for (conflict in listOf(target.cloudRow().copy(deleted = true), target.cloudRow().copy(sid = "watch"),
            target.cloudRow().copy(sid = null), row(day + 100, 95).cloudRow())) {
            val fixture = fixture(listOf(target))
            val delegate = Cloud().apply {
                onRead = { count, _, _ -> if (count == 1) listOf(conflict) else emptyList() }
                onHistory = { _, _ -> MiHealthImportHistoryRead(listOf(target.cloudRow()), 1, true) }
            }
            val cloud = MiHistoryObservingCloud(delegate)
            val result = MiPendingSpO2Reconciler(cloud, fixture.guarded)
                .reconcile(account, plan(listOf(target)), userRequested = false, automatic = true)
            assertEquals(0, assertPartition(result).reconciledRows)
            cloud.beginImportPrecheck(listOf(target))
            assertFailsWith<MiHealthImportRunException> {
                MiHealthImportRunner(cloud, fixture.ledger).run(account, plan(listOf(target)))
            }
            assertEquals(setOf(target.identity.stableKey), fixture.ledger.pendingIdentities(account, setOf(target.identity.stableKey)))
            assertEquals(0, delegate.uploadCalls)
        }
    }

    @Test
    fun laterOverlappingBroadConflictMustReachReconcilerBeforePrecheckGuardStarts(): Unit = runBlocking {
        val first = row(day + 100)
        val second = row(day + 86_400 + 100)
        val rows = listOf(first, second)
        val fixture = fixture(rows)
        val delegate = Cloud().apply {
            onRead = { count, _, _ -> if (count == 1) listOf(first.cloudRow())
                else if (count == 2) listOf(first.cloudRow().copy(deleted = true), second.cloudRow()) else emptyList() }
            onHistory = { _, _ -> MiHealthImportHistoryRead(rows.map { it.cloudRow() }, 1, true) }
        }
        val cloud = MiHistoryObservingCloud(delegate)
        val result = MiPendingSpO2Reconciler(cloud, fixture.guarded)
            .reconcile(account, plan(rows), userRequested = false, automatic = true)
        assertEquals(1, assertPartition(result).deletedRows)
        assertEquals(1, result.pendingReconcile!!.reconciledRows)
        assertEquals(setOf(first.identity.stableKey), fixture.ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()))
        assertEquals(0, delegate.uploadCalls)
    }

    @Test
    fun a195Source132PendingPlanReconcilesAllNativeMatchesWithZeroHealthUploads(): Unit = runBlocking {
        val rows = (0 until 195).map { row(day + 1_000 + it) }
        val fixture = fixture(rows.drop(63), legacy = true)
        val cloud = Cloud().apply {
            onRead = { _, _, _ -> rows.take(63).map { it.cloudRow() } }
            onHistory = { _, _ -> MiHealthImportHistoryRead(rows.drop(63).map { r ->
                r.cloudRow().copy(value = buildJsonObject {
                    r.value.forEach { (key, value) -> put(key, value) }
                    put("server_detail", 1)
                })
            }, 2, true) }
        }
        val result = reconcile(cloud, fixture.guarded, plan(rows))
        val details = assertPartition(result)
        assertEquals("COMPLETE", details.status)
        assertEquals(195, result.inputRecords)
        assertEquals(195, result.sourceRawRows)
        assertEquals(132, result.plannedRawRows)
        assertEquals(132, details.candidateRows)
        assertEquals(132, details.reconciledRows)
        assertEquals(0, details.unresolvedRows)
        assertEquals(132, result.alreadyPresentRawRows)
        assertEquals(0, result.newlyVerifiedRawRows)
        assertEquals(1, fixture.guarded.commits)
        assertEquals(listOf("read", "history"), cloud.events)
        assertEquals(32, cloud.nativeLimits.single())
        assertTrue(fixture.ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()).isEmpty())
        assertTrue(fixture.ledger.ownedVerifiedFingerprints(account).isEmpty())
        assertTrue(fixture.ledger.recoveryAttempts(account, rows.map { it.identity.stableKey }.toSet()).isEmpty())
        assertEquals(132, fixture.ledger.verifiedFingerprints(account).size)
        val actual = cloud.onHistory!!.invoke(day + 2_000, 32).rows.first()
        assertEquals(HealthRecordIds.version(canonicalMiHealthImportJson(actual.value)).toString(),
            fixture.ledger.verifiedFingerprints(account)[HealthImportLedger.identityDigest(rows[63].identity.stableKey)])
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun explicitRequestSupportedMetricAndExistingPendingAreRequired(): Unit = runBlocking {
        val target = row(day)
        val fixture = fixture(listOf(target))
        val cloud = Cloud()
        assertEquals("NOT_REQUESTED", MiPendingSpO2Reconciler(cloud, fixture.guarded)
            .reconcile(account, plan(listOf(target)), false).pendingReconcile?.status)
        assertEquals("UNSUPPORTED", reconcile(cloud, fixture.guarded,
            plan(listOf(target)).copy(metric = HealthInboundMetric.HEART_RATE)).pendingReconcile?.status)
        assertEquals("UNSUPPORTED", reconcile(cloud, fixture.guarded,
            plan(listOf(target.copy(identity = target.identity.copy(sid = "other-source"))))).pendingReconcile?.status)
        assertEquals("NO_PENDING", reconcile(cloud, fixture.guarded, plan(listOf(row(day + 1)))).pendingReconcile?.status)
        assertTrue(cloud.events.isEmpty())
        assertEquals(0, fixture.guarded.commits)
    }

    @Test
    fun completeBroadMatchesNeedNoNativeTraversalAndDoNotTouchUnselectedPending(): Unit = runBlocking {
        val selected = row(day)
        val outside = row(day - 86_400)
        val fixture = fixture(listOf(selected, outside))
        val cloud = Cloud().apply { persisted += selected.cloudRow() }
        val result = reconcile(cloud, fixture.guarded, plan(listOf(selected)))
        assertEquals("COMPLETE", assertPartition(result).status)
        assertEquals(listOf("read"), cloud.events)
        assertEquals(setOf(outside.identity.stableKey), fixture.ledger.pendingIdentities(account,
            setOf(selected.identity.stableKey, outside.identity.stableKey)))
    }

    @Test
    fun partialNativeHistoryConfirmsVisibleMatchesButLeavesAbsentTargetsUnqueried(): Unit = runBlocking {
        val rows = (0..2).map { row(day + it) }
        val fixture = fixture(rows)
        val cloud = Cloud().apply { onHistory = { _, _ -> MiHealthImportHistoryRead(listOf(rows[0].cloudRow()), 32, false) } }
        val result = reconcile(cloud, fixture.guarded, plan(rows))
        val details = assertPartition(result)
        assertEquals("PARTIAL", details.status)
        assertEquals(1, details.reconciledRows)
        assertEquals(2, details.unqueriedRows)
        assertEquals(0, details.missingRows)
        assertEquals(false, details.historyComplete)
        assertEquals(32, details.historyPagesRead)
        assertEquals(rows.drop(1).map { it.identity.stableKey }.toSet(), fixture.ledger.pendingIdentities(account,
            rows.map { it.identity.stableKey }.toSet()))
    }

    @Test
    fun completedHistoryMissingIsReportedWithoutClearingPendingOrSpendingRecoveryBudget(): Unit = runBlocking {
        val rows = (0..2).map { row(day + it) }
        val fixture = fixture(rows)
        val result = reconcile(Cloud(), fixture.guarded, plan(rows))
        val details = assertPartition(result)
        assertEquals("INCOMPLETE", details.status)
        assertEquals(3, details.missingRows)
        assertEquals(0, details.unqueriedRows)
        assertEquals(true, details.historyComplete)
        assertEquals(0, fixture.guarded.commits)
        assertEquals(3, fixture.ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()).size)
        assertTrue(fixture.ledger.recoveryAttempts(account, rows.map { it.identity.stableKey }.toSet()).isEmpty())
    }

    @Test
    fun aLaterNativeMatchCannotEraseBroadDeletionOtherSourceOrContentConflict(): Unit = runBlocking {
        val rows = (0..3).map { row(day + it) }
        val fixture = fixture(rows)
        val cloud = Cloud().apply {
            onRead = { _, _, _ -> listOf(rows[0].cloudRow().copy(deleted = true),
                rows[1].cloudRow().copy(sid = "different-source"),
                rows[2].cloudRow().copy(sid = null), row(day + 3, 97).cloudRow()) }
            onHistory = { _, _ -> MiHealthImportHistoryRead(rows.map { it.cloudRow() }, 1, true) }
        }
        val details = assertPartition(reconcile(cloud, fixture.guarded, plan(rows)))
        assertEquals(1, details.deletedRows)
        assertEquals(2, details.otherSourceRows)
        assertEquals(1, details.cloudConflictRows)
        assertEquals(0, details.reconciledRows)
        assertEquals(0, fixture.guarded.commits)
    }

    @Test
    fun laterOverlappingWindowAndNativeConflictsOverrideEarlierBroadMatches(): Unit = runBlocking {
        val rows = listOf(row(day), row(day + 86_400), row(day + 86_401))
        val fixture = fixture(rows)
        val cloud = Cloud().apply {
            onRead = { call, _, _ -> if (call == 1) listOf(rows[0].cloudRow())
                else listOf(rows[0].cloudRow().copy(deleted = true), rows[1].cloudRow()) }
            onHistory = { _, _ -> MiHealthImportHistoryRead(listOf(rows[1].cloudRow().copy(sid = "other"),
                rows[2].cloudRow()), 1, false) }
        }
        val details = assertPartition(reconcile(cloud, fixture.guarded, plan(rows)))
        assertEquals("PARTIAL", details.status)
        assertEquals(1, details.deletedRows)
        assertEquals(1, details.otherSourceRows)
        assertEquals(1, details.reconciledRows)
        assertEquals(setOf(rows[0].identity.stableKey, rows[1].identity.stableKey), fixture.ledger.pendingIdentities(account,
            rows.map { it.identity.stableKey }.toSet()))
    }

    @Test
    fun sourceCollisionChangesAndUnknownTimezoneAreDisjointAndNeverQueried(): Unit = runBlocking {
        val original = (0..4).map { row(day + it) }
        val fixture = fixture(original)
        val changed = listOf(original[0], row(day, 97),
            row(day + 1, 97), original[2].copy(sourceRecordKeys = setOf("replaced-record-id")),
            original[3].copy(zoneOffsetSeconds = 0), original[4].copy(dayAggregationEligible = false))
        val cloud = Cloud()
        val details = assertPartition(reconcile(cloud, fixture.guarded, plan(changed)))
        assertEquals(1, details.sourceCollisionRows)
        assertEquals(4, details.sourceChangedRows)
        assertTrue(cloud.events.isEmpty())
        assertEquals(0, fixture.guarded.commits)
    }

    @Test
    fun rejectedMapperMaterialBlocksReconciliationWithoutGuessingItsTarget(): Unit = runBlocking {
        val target = row(day)
        val fixture = fixture(listOf(target))
        val cloud = Cloud().apply { persisted += target.cloudRow() }
        val rejected = plan(listOf(target)).copy(issues = listOf(MiHealthImportIssue(
            "CONFLICTING_SOURCE_REVISIONS", setOf("source-removed-by-mapper"), "synthetic")))
        val details = assertPartition(reconcile(cloud, fixture.guarded, rejected))
        assertEquals(1, details.sourceChangedRows)
        assertEquals(0, fixture.guarded.commits)
        assertTrue(cloud.events.isEmpty())
    }

    @Test
    fun equalTargetsUnionTheirSourceAssociationsBeforeComparingThePendingSnapshot(): Unit = runBlocking {
        val a = row(day).copy(sourceRecordKeys = setOf("one-source-record"))
        val b = a.copy(sourceRecordKeys = setOf("second-source-record"))
        val fixture = fixture(listOf(a.copy(sourceRecordKeys = a.sourceRecordKeys + b.sourceRecordKeys)))
        val cloud = Cloud().apply { persisted += a.cloudRow() }
        val details = assertPartition(reconcile(cloud, fixture.guarded, plan(listOf(a, b))))
        assertEquals(1, details.reconciledRows)
        assertEquals(1, details.candidateRows)
    }

    @Test
    fun lateReadAuthenticationFailureCannotCommitEarlierMatchingRows(): Unit = runBlocking {
        val rows = listOf(row(day), row(day + 86_400))
        val fixture = fixture(rows)
        val cloud = Cloud().apply {
            onRead = { call, _, _ -> if (call == 1) listOf(rows[0].cloudRow()) else throw MiApiException.AuthExpired() }
        }
        assertFailsWith<MiApiException.AuthExpired> { reconcile(cloud, fixture.guarded, plan(rows)) }
        assertEquals(0, fixture.guarded.commits)
        assertEquals(2, fixture.ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()).size)
    }

    @Test
    fun nativeAuthenticationFailureAndCancellationHappenBeforeAnyLocalMutation(): Unit = runBlocking {
        val rows = listOf(row(day), row(day + 1))
        for (failure in listOf(MiApiException.AuthExpired(), CancellationException("synthetic cancellation"))) {
            val fixture = fixture(rows)
            val cloud = Cloud().apply {
                onRead = { _, _, _ -> listOf(rows[0].cloudRow()) }
                onHistory = { _, _ -> throw failure }
            }
            val caught = runCatching { reconcile(cloud, fixture.guarded, plan(rows)) }.exceptionOrNull()
            assertTrue(caught === failure)
            assertEquals(0, fixture.guarded.commits)
            assertEquals(2, fixture.ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()).size)
        }
    }

    @Test
    fun nativeReadCanRecoverBroadFailuresButTwoReadFailuresRemainUnqueried(): Unit = runBlocking {
        val target = row(day)
        for (nativeSucceeds in listOf(true, false)) {
            val fixture = fixture(listOf(target))
            val cloud = Cloud().apply {
                onRead = { _, _, _ -> error("synthetic failed read") }
                onHistory = { _, _ -> if (nativeSucceeds) MiHealthImportHistoryRead(listOf(target.cloudRow()), 1, true)
                    else error("synthetic failed native read") }
            }
            val result = reconcile(cloud, fixture.guarded, plan(listOf(target)))
            val details = assertPartition(result)
            assertEquals(1, details.broadReadFailureGroups)
            assertEquals(!nativeSucceeds, details.historyReadFailed)
            assertEquals(if (nativeSucceeds) 1 else 0, details.reconciledRows)
            assertEquals(if (nativeSucceeds) 0 else 1, details.unqueriedRows)
            assertEquals(0, details.missingRows)
        }
    }

    @Test
    fun pendingChangedAfterReadIsRejectedByAtomicCommitAndItsNewValueIsPreserved(): Unit = runBlocking {
        val target = row(day)
        val revised = row(day, 97)
        val fixture = fixture(listOf(target))
        val cloud = Cloud().apply {
            onHistory = { _, _ ->
                fixture.ledger.markPending(account, listOf(MiHealthImportRunner.journalEntry(revised)))
                MiHealthImportHistoryRead(listOf(target.cloudRow()), 1, true)
            }
        }
        val details = assertPartition(reconcile(cloud, fixture.guarded, plan(listOf(target))))
        assertEquals(1, details.journalChangedRows)
        assertEquals(0, details.reconciledRows)
        assertEquals(MiHealthImportRunner.contentFingerprint(revised), fixture.ledger.pendingSnapshots(account,
            setOf(target.identity.stableKey)).values.single().contentFingerprint)
        assertTrue(fixture.ledger.verifiedFingerprints(account).isEmpty())
    }

    @Test
    fun localSaveFailureIncludingAuthShapedFailureReturnsCountsWithoutRetryingOrClaimingSuccess(): Unit = runBlocking {
        val rows = listOf(row(day), row(day + 1))
        for (failure in listOf(IllegalStateException("synthetic save failure"), MiApiException.AuthExpired())) {
            val fixture = fixture(rows)
            fixture.guarded.commitFailure = failure
            val cloud = Cloud().apply { persisted += rows.map { it.cloudRow() } }
            val result = reconcile(cloud, fixture.guarded, plan(rows))
            val details = assertPartition(result)
            assertEquals("LOCAL_SAVE_FAILED", details.status)
            assertEquals(2, details.persistenceFailedRows)
            assertEquals(0, result.verifiedRawRows)
            assertEquals(1, fixture.guarded.commits)
            assertEquals(2, fixture.ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()).size)
        }
    }

    @Test
    fun observingConsumedRecoveryUpdatesItsResultWithoutRestoringItsBudget(): Unit = runBlocking {
        val target = row(day)
        val fixture = fixture(listOf(target))
        val id = setOf(target.identity.stableKey)
        val snapshot = fixture.ledger.pendingSnapshots(account, id).values.single()
        val reserved = assertNotNull(fixture.ledger.reserveSingleRecovery(account, snapshot, MiHealthImportRunner.journalEntry(target)))
        fixture.ledger.recordRecoveryOutcome(account, reserved, HealthImportRecoveryOutcome.REJECTED, accepted = false, businessCode = 100)
        val cloud = Cloud().apply { persisted += target.cloudRow() }
        assertEquals(1, assertPartition(reconcile(cloud, fixture.guarded, plan(listOf(target)))).reconciledRows)
        val after = fixture.ledger.recoveryAttempts(account, id).values.single()
        assertEquals(HealthImportRecoveryOutcome.VERIFIED.name, after.outcome)
        assertEquals(false, after.accepted)
        assertEquals(100, after.businessCode)
        fixture.ledger.markPending(account, listOf(MiHealthImportRunner.journalEntry(target)))
        val pendingAgain = fixture.ledger.pendingSnapshots(account, id).values.single()
        assertTrue(pendingAgain.recoveryConsumed)
        assertEquals(null, fixture.ledger.reserveSingleRecovery(account, pendingAgain, MiHealthImportRunner.journalEntry(target)))
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun readOnlyReconciliationReportsOldestConsumedHistoryWithoutAdvancingOrChangingItsAllowance(): Unit = runBlocking {
        val oldest = row(day)
        val newerConsumed = row(day + 1)
        val unused = row(day + 2)
        // Deliberately unordered: the displayed history follows recovery's oldest-target policy.
        val rows = listOf(unused, newerConsumed, oldest)
        val fixture = fixture(rows, legacy = true)
        val ids = rows.map { it.identity.stableKey }.toSet()
        val original = fixture.ledger.pendingSnapshots(account, ids)
        val oldestReservation = assertNotNull(fixture.ledger.reserveSingleRecovery(account,
            original.getValue(oldest.identity.stableKey), MiHealthImportRunner.journalEntry(oldest)))
        fixture.ledger.recordRecoveryOutcome(account, oldestReservation, HealthImportRecoveryOutcome.READBACK_UNVERIFIED,
            accepted = true, businessCode = 0, readbackStatus = "MISSING")
        val newerReservation = assertNotNull(fixture.ledger.reserveSingleRecovery(account,
            original.getValue(newerConsumed.identity.stableKey), MiHealthImportRunner.journalEntry(newerConsumed)))
        fixture.ledger.recordRecoveryOutcome(account, newerReservation, HealthImportRecoveryOutcome.REJECTED,
            accepted = false, businessCode = 100, readbackStatus = "AMBIGUOUS")
        val beforeSnapshots = fixture.ledger.pendingSnapshots(account, ids)
        val beforeAttempts = fixture.ledger.recoveryAttempts(account, ids)
        val cloud = Cloud().apply { onHistory = { _, _ -> MiHealthImportHistoryRead(emptyList(), 6, true) } }
        repeat(2) {
            val result = reconcile(cloud, fixture.guarded, plan(rows))
            val details = assertPartition(result)
            val previous = assertNotNull(details.oldestRecoveryAttempt)
            assertEquals(true, previous.metadataAvailable)
            assertEquals(MiRecoveryStoredOutcome.READBACK_UNVERIFIED, previous.outcome)
            assertEquals(true, previous.accepted)
            assertEquals(0, previous.businessCode)
            assertEquals(MiRecoveryReadbackStatus.MISSING, previous.readbackStatus)
            assertEquals(true, previous.legacyWireMetadataUnknown)
            assertEquals(3, details.candidateRows)
            assertEquals(3, details.missingRows)
            assertEquals(0, details.reconciledRows)
            assertEquals(0, result.attemptedRawRows)
            assertEquals(ids, fixture.ledger.pendingIdentities(account, ids))
            assertEquals(beforeSnapshots, fixture.ledger.pendingSnapshots(account, ids))
            assertEquals(beforeAttempts, fixture.ledger.recoveryAttempts(account, ids))
        }
        assertEquals(0, fixture.guarded.commits)
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun oldestUnconsumedCandidateDoesNotBorrowANewerTargetsRecoveryHistory(): Unit = runBlocking {
        val oldest = row(day)
        val newer = row(day + 1)
        val rows = listOf(newer, oldest)
        val fixture = fixture(rows)
        val ids = rows.map { it.identity.stableKey }.toSet()
        val snapshot = fixture.ledger.pendingSnapshots(account, ids).getValue(newer.identity.stableKey)
        val reservation = assertNotNull(fixture.ledger.reserveSingleRecovery(account, snapshot,
            MiHealthImportRunner.journalEntry(newer)))
        fixture.ledger.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.READBACK_UNVERIFIED,
            accepted = true, businessCode = 0, readbackStatus = "MISSING")
        val before = fixture.ledger.pendingSnapshots(account, ids)
        val cloud = Cloud()
        val result = reconcile(cloud, fixture.guarded, plan(rows))
        assertEquals(null, assertPartition(result).oldestRecoveryAttempt)
        assertEquals(before, fixture.ledger.pendingSnapshots(account, ids))
        assertFalse(fixture.ledger.pendingSnapshots(account, ids).getValue(oldest.identity.stableKey).recoveryConsumed)
        assertEquals(0, fixture.guarded.commits)
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun unreadableOldestConsumedMetadataIsUnknownWithoutResettingRecoveryOrChoosingAnotherRow(): Unit = runBlocking {
        val oldest = row(day)
        val newer = row(day + 1)
        val rows = listOf(oldest, newer)
        val fixture = fixture(rows)
        val ids = rows.map { it.identity.stableKey }.toSet()
        val oldestSnapshot = fixture.ledger.pendingSnapshots(account, ids).getValue(oldest.identity.stableKey)
        assertNotNull(fixture.ledger.reserveSingleRecovery(account, oldestSnapshot, MiHealthImportRunner.journalEntry(oldest)))
        val before = fixture.ledger.pendingSnapshots(account, ids)
        val unreadable = object : HealthImportJournal by fixture.guarded {
            override suspend fun pendingSnapshots(account: String, identities: Set<String>): Map<String, HealthImportPendingSnapshot> =
                fixture.ledger.pendingSnapshots(account, identities).mapValues { (identity, snapshot) ->
                    if (identity == oldest.identity.stableKey) snapshot.copy(recoveryAttempt = null) else snapshot
                }
        }
        val cloud = Cloud()
        val result = MiPendingSpO2Reconciler(cloud, unreadable).reconcile(account, plan(rows), userRequested = true)
        val previous = assertNotNull(assertPartition(result).oldestRecoveryAttempt)
        assertFalse(previous.metadataAvailable)
        assertEquals(null, previous.accepted)
        assertEquals(null, previous.businessCode)
        assertEquals(null, previous.readbackStatus)
        assertEquals(before, fixture.ledger.pendingSnapshots(account, ids))
        assertEquals(0, fixture.guarded.commits)
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun broadGroupsAndNativePagesAreBoundedWithoutDroppingAnUnqueriedTarget(): Unit = runBlocking {
        val rows = (0..32).map { row(day + it * 86_400L) }
        val fixture = fixture(rows)
        val cloud = Cloud().apply { onHistory = { _, _ -> MiHealthImportHistoryRead(listOf(rows.last().cloudRow()), 32, false) } }
        val details = assertPartition(reconcile(cloud, fixture.guarded, plan(rows)))
        assertEquals(32, details.broadReadGroups)
        assertEquals(1, details.broadReadLimitedGroups)
        assertEquals(33, details.historySelectedRows)
        assertEquals(1, details.reconciledRows)
        assertEquals(32, details.unqueriedRows)
        assertEquals(32, cloud.events.count { it == "read" })
        assertEquals(listOf(32), cloud.nativeLimits)
        assertEquals(rows.last().identity.time + 1, cloud.nativeEnds.single())
    }

    @Test
    fun invalidTimeBoundsNeverReachCloudRequestsOrDisappearFromThePartition(): Unit = runBlocking {
        val rows = listOf(row(-1), row(Long.MAX_VALUE))
        val fixture = fixture(rows)
        val cloud = Cloud()
        val details = assertPartition(reconcile(cloud, fixture.guarded, plan(rows)))
        assertEquals(2, details.sourceChangedRows)
        assertTrue(cloud.events.isEmpty())
        assertEquals(0, fixture.guarded.commits)
    }

    private fun assertPartition(result: MiHealthImportDiagnostic): MiPendingReconcileDiagnostic {
        val d = assertNotNull(result.pendingReconcile)
        assertEquals(d.candidateRows, d.reconciledRows + d.sourceChangedRows + d.sourceCollisionRows +
            d.cloudConflictRows + d.deletedRows + d.otherSourceRows + d.missingRows + d.unqueriedRows +
            d.journalChangedRows + d.persistenceFailedRows)
        assertEquals(d.candidateRows - d.reconciledRows, d.unresolvedRows)
        assertEquals(d.reconciledRows, result.verifiedRawRows)
        assertEquals(0, result.attemptedRawRows)
        assertEquals(0, result.attemptedDailyRows)
        assertEquals(0, result.plannedDailyRows)
        assertEquals(0, result.newlyVerifiedRawRows)
        return d
    }

    private fun row(time: Long, spo2: Int = 98) = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, MI_HEALTH_CONNECT_IMPORT_SID, "spo2", time),
        buildJsonObject { put("time", time); put("spo2", spo2) }, 28_800, setOf("synthetic-source:$time"))
    private fun plan(rows: List<MiHealthImportRow>) = MiHealthImportPlan(HealthInboundMetric.OXYGEN_SATURATION,
        rows, emptyList(), rows.size)
    private fun MiHealthImportRow.cloudRow() = MiHealthImportCloudRow(identity.sid, identity.key,
        identity.time, identity.tag, value, zoneOffsetSeconds = zoneOffsetSeconds)
    private suspend fun reconcile(cloud: Cloud, journal: HealthImportJournal, plan: MiHealthImportPlan) =
        MiPendingSpO2Reconciler(cloud, journal).reconcile(account, plan, true).also { assertEquals(0, cloud.uploadCalls) }

    private data class Fixture(val ledger: HealthImportLedger, val guarded: GuardedJournal)
    private suspend fun fixture(rows: List<MiHealthImportRow>, legacy: Boolean = false): Fixture {
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markPending(account, rows.map { MiHealthImportRunner.journalEntry(it).copy(
            zoneOffsetSeconds = if (legacy) null else it.zoneOffsetSeconds) })
        return Fixture(ledger, GuardedJournal(ledger))
    }

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    /** Any accidental write-ahead reservation or unsafe legacy clear fails these tests immediately. */
    private class GuardedJournal(private val delegate: HealthImportJournal) : HealthImportJournal by delegate {
        var commits = 0
        var commitFailure: Throwable? = null
        override suspend fun markPending(account: String, entries: List<HealthImportJournalEntry>): Unit =
            error("reconciliation must not create or reset upload reservations")
        override suspend fun markVerified(account: String, entries: List<HealthImportJournalEntry>): Unit =
            error("reconciliation must use atomic snapshot comparison")
        override suspend fun reserveSingleRecovery(account: String, expected: HealthImportPendingSnapshot,
            entry: HealthImportJournalEntry): HealthImportRecoveryReservation? = error("reconciliation must not consume recovery budget")
        override suspend fun reconcileObservedPending(account: String,
            entries: List<HealthImportPendingReconciliation>): Set<String> {
            commits++
            commitFailure?.let { throw it }
            return delegate.reconcileObservedPending(account, entries)
        }
    }

    private class Cloud : MiHealthImportCloud {
        val persisted = mutableListOf<MiHealthImportCloudRow>()
        val events = mutableListOf<String>()
        val nativeLimits = mutableListOf<Int>()
        val nativeEnds = mutableListOf<Long>()
        var uploadCalls = 0
        var reads = 0
        var onRead: (suspend (Int, Long, Long) -> List<MiHealthImportCloudRow>)? = null
        var onHistory: (suspend (Long, Int) -> MiHealthImportHistoryRead)? = null
        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long,
            untilEpochSeconds: Long): List<MiHealthImportCloudRow> {
            assertEquals(MiHealthImportChannel.RAW, channel)
            assertEquals("spo2", key)
            assertTrue(untilEpochSeconds > fromEpochSeconds)
            reads++
            events += "read"
            return onRead?.invoke(reads, fromEpochSeconds, untilEpochSeconds) ?: persisted.filter {
                it.time >= fromEpochSeconds && it.time < untilEpochSeconds
            }
        }
        override suspend fun readRawRowsWithoutStart(key: String, untilEpochSeconds: Long,
            maxPages: Int): MiHealthImportHistoryRead {
            assertEquals("spo2", key)
            events += "history"
            nativeLimits += maxPages
            nativeEnds += untilEpochSeconds
            return onHistory?.invoke(untilEpochSeconds, maxPages) ?: MiHealthImportHistoryRead(
                persisted.filter { it.time < untilEpochSeconds }, 1, true)
        }
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
            uploadCalls++
            error("read-only reconciliation must never call the upload endpoint")
        }
    }
}
