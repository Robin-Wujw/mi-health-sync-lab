package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Local state transitions only; the caller must supply an actually observed semantic match. */
class HealthImportLedgerReconciliationTest {
    private val account = "synthetic-reconciliation-account"

    @Test
    fun batchStoresObservedContentWithoutGrantingOwnershipAndLeavesUnmatchedPending(): Unit = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        val entries = (1..3).map(::entry)
        ledger.markPending(account, entries)
        val snapshots = ledger.pendingSnapshots(account, entries.ids())
        val changes = entries.take(2).map { e ->
            HealthImportPendingReconciliation(snapshots.getValue(e.targetIdentity),
                e.copy(ownedUpload = true), "observed-with-server-extras-${e.targetIdentity}")
        }

        assertEquals(entries.take(2).ids(), ledger.reconcileObservedPending(account, changes))
        assertEquals(setOf(entries.last().targetIdentity), ledger.pendingIdentities(account, entries.ids()))
        assertEquals(changes.associate { HealthImportLedger.identityDigest(it.expected.targetIdentity) to it.observedContentFingerprint },
            ledger.verifiedFingerprints(account))
        assertTrue(ledger.ownedVerifiedFingerprints(account).isEmpty())
        assertTrue(ledger.legacyVerifiedFingerprints(account).isEmpty())
        assertTrue(ledger.recoveryAttempts(account, entries.ids()).isEmpty())
    }

    @Test
    fun stalePendingOrChangedSourceContentAndZoneCannotClearTheCurrentEntry(): Unit = runBlocking {
        val mutations = listOf<(HealthImportJournalEntry) -> HealthImportJournalEntry>(
            { it.copy(contentFingerprint = "revised-value") },
            { it.copy(sourceRecordKeys = setOf("changed-source:record")) },
            { it.copy(zoneOffsetSeconds = 0) },
        )
        for (mutation in mutations) {
            val ledger = HealthImportLedger(MemoryStore())
            val original = entry(1)
            ledger.markPending(account, listOf(original))
            val before = ledger.pendingSnapshots(account, setOf(original.targetIdentity)).getValue(original.targetIdentity)
            val updated = mutation(original)
            ledger.markPending(account, listOf(updated))
            assertTrue(ledger.reconcileObservedPending(account,
                listOf(HealthImportPendingReconciliation(before, original, "observed"))).isEmpty())
            assertEquals(setOf(original.targetIdentity), ledger.pendingIdentities(account, setOf(original.targetIdentity)))
            assertTrue(ledger.verifiedFingerprints(account).isEmpty())
            val current = ledger.pendingSnapshots(account, setOf(original.targetIdentity)).getValue(original.targetIdentity)
            assertTrue(current.matches(updated))
        }
    }

    @Test
    fun aNewOrReplacedRecoveryReservationInvalidatesTheEarlierReadSnapshot(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val source = entry(1)
        val ids = setOf(source.targetIdentity)
        ledger.markPending(account, listOf(source))
        val before = ledger.pendingSnapshots(account, ids).getValue(source.targetIdentity)
        val reserved = assertNotNull(ledger.reserveSingleRecovery(account, before, source))
        assertTrue(ledger.reconcileObservedPending(account,
            listOf(HealthImportPendingReconciliation(before, source, "observed"))).isEmpty())

        val consumed = ledger.pendingSnapshots(account, ids).getValue(source.targetIdentity)
        store.edit { it[recoveryKey(source)] = Json.encodeToString(reserved.attempt.copy(token = "different-attempt")) }
        assertTrue(ledger.reconcileObservedPending(account,
            listOf(HealthImportPendingReconciliation(consumed, source, "observed"))).isEmpty())
        assertEquals(ids, ledger.pendingIdentities(account, ids))
        assertTrue(ledger.verifiedFingerprints(account).isEmpty())
    }

    @Test
    fun consumedAttemptKeepsItsLatestBusinessMetadataAndCannotBeRearmedAfterReconciliation(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val source = entry(1)
        val ids = setOf(source.targetIdentity)
        ledger.markPending(account, listOf(source))
        val reservation = assertNotNull(ledger.reserveSingleRecovery(account,
            ledger.pendingSnapshots(account, ids).getValue(source.targetIdentity), source))
        val beforeResponse = ledger.pendingSnapshots(account, ids).getValue(source.targetIdentity)
        assertTrue(ledger.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.REJECTED,
            accepted = false, businessCode = 100, readbackStatus = "MISSING"))

        assertEquals(ids, ledger.reconcileObservedPending(account,
            listOf(HealthImportPendingReconciliation(beforeResponse, source, "observed-exact-cloud"))))
        val actual = ledger.recoveryAttempts(account, ids).getValue(source.targetIdentity)
        assertEquals(reservation.attempt.token, actual.token)
        assertEquals(HealthImportRecoveryOutcome.VERIFIED.name, actual.outcome)
        assertEquals("VERIFIED", actual.readbackStatus)
        assertEquals(false, actual.accepted)
        assertEquals(100, actual.businessCode)
        assertTrue(ledger.pendingIdentities(account, ids).isEmpty())

        val reopened = HealthImportLedger(store)
        reopened.resetPendingImports()
        reopened.markPending(account, listOf(source))
        val after = reopened.pendingSnapshots(account, ids).getValue(source.targetIdentity)
        assertTrue(after.recoveryConsumed)
        assertNull(reopened.reserveSingleRecovery(account, after, source))
    }

    @Test
    fun changedContentCannotReuseAnOlderRecoveryAttemptAsProofOfItsOutcome(): Unit = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        val source = entry(1)
        val ids = setOf(source.targetIdentity)
        ledger.markPending(account, listOf(source))
        assertNotNull(ledger.reserveSingleRecovery(account,
            ledger.pendingSnapshots(account, ids).getValue(source.targetIdentity), source))
        val revised = source.copy(contentFingerprint = "revised-after-reservation")
        ledger.markPending(account, listOf(revised))
        val current = ledger.pendingSnapshots(account, ids).getValue(source.targetIdentity)
        assertTrue(current.matches(revised))
        assertTrue(ledger.reconcileObservedPending(account,
            listOf(HealthImportPendingReconciliation(current, revised, "observed-revision"))).isEmpty())
        assertEquals(ids, ledger.pendingIdentities(account, ids))
        assertEquals(HealthImportRecoveryOutcome.RESERVED.name,
            ledger.recoveryAttempts(account, ids).getValue(source.targetIdentity).outcome)
    }

    @Test
    fun sameObservedContentPreservesOwnedAndLegacyProvenanceButChangedContentDoesNot(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val ownedSame = entry(1)
        val legacySame = entry(2)
        val ownedChanged = entry(3)
        ledger.markVerified(account, listOf(ownedSame.copy(contentFingerprint = "same-cloud", ownedUpload = true),
            ownedChanged.copy(contentFingerprint = "old-cloud", ownedUpload = true)))
        store.edit { it[verifiedKey(legacySame)] = "legacy-cloud" }
        val sources = listOf(ownedSame, legacySame, ownedChanged)
        ledger.markPending(account, sources)
        val snapshots = ledger.pendingSnapshots(account, sources.ids())
        val observed = listOf("same-cloud", "legacy-cloud", "new-cloud")
        assertEquals(sources.ids(), ledger.reconcileObservedPending(account, sources.mapIndexed { index, source ->
            HealthImportPendingReconciliation(snapshots.getValue(source.targetIdentity), source, observed[index])
        }))
        assertEquals(mapOf(HealthImportLedger.identityDigest(ownedSame.targetIdentity) to "same-cloud"),
            ledger.ownedVerifiedFingerprints(account))
        assertEquals(mapOf(HealthImportLedger.identityDigest(legacySame.targetIdentity) to "legacy-cloud"),
            ledger.legacyVerifiedFingerprints(account))
    }

    @Test
    fun duplicateTargetCandidatesAreAllSkippedWhileIndependentMatchesCanCommit(): Unit = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        val sources = (1..2).map(::entry)
        ledger.markPending(account, sources)
        val snapshots = ledger.pendingSnapshots(account, sources.ids())
        val first = HealthImportPendingReconciliation(snapshots.getValue(sources[0].targetIdentity), sources[0], "first")
        val second = HealthImportPendingReconciliation(snapshots.getValue(sources[1].targetIdentity), sources[1], "second")
        assertEquals(setOf(sources[1].targetIdentity), ledger.reconcileObservedPending(account,
            listOf(first, first.copy(observedContentFingerprint = "conflicting"), second)))
        assertEquals(setOf(sources[0].targetIdentity), ledger.pendingIdentities(account, sources.ids()))
        assertFalse(HealthImportLedger.identityDigest(sources[0].targetIdentity) in ledger.verifiedFingerprints(account))
    }

    @Test
    fun malformedStateAndForgedSnapshotFieldsFailClosed(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val source = entry(1)
        val ids = setOf(source.targetIdentity)
        ledger.markPending(account, listOf(source))
        val original = ledger.pendingSnapshots(account, ids).getValue(source.targetIdentity)
        val forged = original.copy(contentFingerprint = "forged-fingerprint")
        assertTrue(ledger.reconcileObservedPending(account, listOf(HealthImportPendingReconciliation(forged,
            source.copy(contentFingerprint = "forged-fingerprint"), "observed"))).isEmpty())
        store.edit { it[recoveryKey(source)] = "malformed-budget" }
        val malformed = ledger.pendingSnapshots(account, ids).getValue(source.targetIdentity)
        assertTrue(malformed.recoveryConsumed)
        assertNull(malformed.recoveryAttempt)
        assertTrue(ledger.reconcileObservedPending(account,
            listOf(HealthImportPendingReconciliation(malformed, source, "observed"))).isEmpty())
        assertEquals(ids, ledger.pendingIdentities(account, ids))
        assertTrue(ledger.verifiedFingerprints(account).isEmpty())
    }

    @Test
    fun failedCommitPublishesNeitherVerificationNorPartialPendingRemoval(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val sources = (1..2).map(::entry)
        ledger.markPending(account, sources)
        val reserved = assertNotNull(ledger.reserveSingleRecovery(account,
            ledger.pendingSnapshots(account, sources.ids()).getValue(sources[0].targetIdentity), sources[0]))
        val snapshots = ledger.pendingSnapshots(account, sources.ids())
        store.failNextUpdate = true
        assertFailsWith<IllegalStateException> {
            ledger.reconcileObservedPending(account, sources.map { source ->
                HealthImportPendingReconciliation(snapshots.getValue(source.targetIdentity), source, "observed")
            })
        }
        assertEquals(sources.ids(), ledger.pendingIdentities(account, sources.ids()))
        assertTrue(ledger.verifiedFingerprints(account).isEmpty())
        assertEquals(reserved.attempt, ledger.recoveryAttempts(account, sources.ids()).getValue(sources[0].targetIdentity))
    }

    @Test
    fun concurrentReconciliationsCommitEachSnapshotOnlyOnceAndRespectAccountScope(): Unit = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        val source = entry(1)
        val ids = setOf(source.targetIdentity)
        ledger.markPending(account, listOf(source))
        val snapshot = ledger.pendingSnapshots(account, ids).getValue(source.targetIdentity)
        val candidate = HealthImportPendingReconciliation(snapshot, source, "observed")
        assertTrue(ledger.reconcileObservedPending("different-account", listOf(candidate)).isEmpty())
        assertEquals(ids, ledger.pendingIdentities(account, ids))
        val results = List(8) { async(Dispatchers.Default) {
            ledger.reconcileObservedPending(account, listOf(candidate))
        } }.awaitAll()
        assertEquals(1, results.count { it == ids })
        assertEquals(7, results.count { it.isEmpty() })
    }

    @Test
    fun minuteAliasAtomicallyStoresOriginalAndActualIdentitiesAndPreservesRecoveryMetadata(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val source = entry(1)
        val alias = minuteAlias(source)
        val unrelated = entry(90)
        ledger.markPending(account, listOf(source, unrelated))
        val reservation = assertNotNull(ledger.reserveSingleRecovery(account,
            ledger.pendingSnapshots(account, setOf(source.targetIdentity)).getValue(source.targetIdentity), source))
        val candidate = aliasCandidate(ledger, source)
        assertTrue(ledger.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.TRANSPORT_UNKNOWN,
            accepted = null, businessCode = 0, readbackStatus = "MISSING"))
        assertEquals(setOf(source.targetIdentity), ledger.reconcileObservedPending(account, listOf(candidate)))
        val reopened = HealthImportLedger(store)
        assertEquals(setOf(unrelated.targetIdentity), reopened.pendingIdentities(account, setOf(source.targetIdentity, alias, unrelated.targetIdentity)))
        assertEquals(mapOf(HealthImportLedger.identityDigest(source.targetIdentity) to "observed-minute",
            HealthImportLedger.identityDigest(alias) to "observed-minute"), reopened.verifiedFingerprints(account))
        assertTrue(reopened.ownedVerifiedFingerprints(account).isEmpty())
        assertTrue(reopened.legacyVerifiedFingerprints(account).isEmpty())
        val attempt = reopened.recoveryAttempts(account, setOf(source.targetIdentity)).getValue(source.targetIdentity)
        assertEquals(reservation.attempt.token, attempt.token)
        assertEquals(null, attempt.accepted)
        assertEquals(0, attempt.businessCode)
        assertEquals("VERIFIED", attempt.outcome)
        assertEquals("VERIFIED", attempt.readbackStatus)
        assertTrue(reopened.recoveryAttempts(account, setOf(alias)).isEmpty())
        reopened.markPending(account, listOf(source))
        assertNull(reopened.reserveSingleRecovery(account,
            reopened.pendingSnapshots(account, setOf(source.targetIdentity)).getValue(source.targetIdentity), source))
    }

    @Test
    fun aliasPendingOrConflictingVerifiedStateBlocksOriginalRemoval(): Unit = runBlocking {
        for (pendingAlias in listOf(false, true)) {
            val ledger = HealthImportLedger(MemoryStore())
            val source = entry(1)
            val aliasEntry = source.copy(targetIdentity = minuteAlias(source), contentFingerprint = "other-content")
            ledger.markPending(account, listOf(source))
            val candidate = aliasCandidate(ledger, source)
            if (pendingAlias) ledger.markPending(account, listOf(aliasEntry))
            else ledger.markVerified(account, listOf(aliasEntry.copy(ownedUpload = true)))
            val before = ledger.pendingSnapshots(account, setOf(source.targetIdentity, aliasEntry.targetIdentity))
            val verified = ledger.verifiedFingerprints(account)
            assertTrue(ledger.reconcileObservedPending(account, listOf(candidate)).isEmpty())
            assertEquals(before, ledger.pendingSnapshots(account, before.keys))
            assertEquals(verified, ledger.verifiedFingerprints(account))
        }
    }

    @Test
    fun staleOriginalSnapshotCannotPublishAMinuteAlias(): Unit = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        val source = entry(1)
        ledger.markPending(account, listOf(source))
        val candidate = aliasCandidate(ledger, source)
        val revised = source.copy(sourceRecordKeys = setOf("changed-source"))
        ledger.markPending(account, listOf(revised))
        assertTrue(ledger.reconcileObservedPending(account, listOf(candidate)).isEmpty())
        assertTrue(ledger.verifiedFingerprints(account).isEmpty())
        assertTrue(ledger.pendingSnapshots(account, setOf(source.targetIdentity)).getValue(source.targetIdentity).matches(revised))
    }

    @Test
    fun malformedOrUnverifiedAliasRecoveryCannotBeHiddenByAMatchingFingerprint(): Unit = runBlocking {
        for (outcome in listOf<String?>(null, "RESERVED", "READBACK_UNVERIFIED", "VERIFIED")) {
            val store = MemoryStore()
            val ledger = HealthImportLedger(store)
            val source = entry(1)
            val alias = source.copy(targetIdentity = minuteAlias(source), contentFingerprint = "observed-minute")
            ledger.markPending(account, listOf(source, alias))
            val reservation = assertNotNull(ledger.reserveSingleRecovery(account,
                ledger.pendingSnapshots(account, setOf(alias.targetIdentity)).getValue(alias.targetIdentity), alias))
            ledger.markVerified(account, listOf(alias))
            store.edit { it[recoveryKey(alias)] = outcome?.let { label -> Json.encodeToString(reservation.attempt.copy(
                outcome = label, readbackStatus = if (label == "VERIFIED") "VERIFIED" else "MISSING")) } ?: "broken" }
            val candidate = aliasCandidate(ledger, source)
            val originalRecovery = store.data.valueFor(recoveryKey(alias))
            val committed = ledger.reconcileObservedPending(account, listOf(candidate))
            assertEquals(if (outcome == "VERIFIED") setOf(source.targetIdentity) else emptySet(), committed)
            assertEquals(originalRecovery, store.data.valueFor(recoveryKey(alias)))
            if (outcome != "VERIFIED") assertEquals(setOf(source.targetIdentity),
                ledger.pendingIdentities(account, setOf(source.targetIdentity)))
        }
    }

    @Test
    fun aliasRecoveryWithoutAnExistingMatchingVerificationBlocksEvenWhenMarkedVerified(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val source = entry(1)
        val alias = source.copy(targetIdentity = minuteAlias(source))
        ledger.markPending(account, listOf(source, alias))
        val reserved = assertNotNull(ledger.reserveSingleRecovery(account,
            ledger.pendingSnapshots(account, setOf(alias.targetIdentity)).getValue(alias.targetIdentity), alias))
        ledger.markVerified(account, listOf(alias))
        store.edit {
            it.remove(verifiedKey(alias))
            it[recoveryKey(alias)] = Json.encodeToString(reserved.attempt.copy(outcome = "VERIFIED", readbackStatus = "VERIFIED"))
        }
        assertTrue(ledger.reconcileObservedPending(account, listOf(aliasCandidate(ledger, source))).isEmpty())
        assertEquals(setOf(source.targetIdentity), ledger.pendingIdentities(account, setOf(source.targetIdentity)))
    }

    @Test
    fun competingAliasesInOneBatchAreSkippedWhileIndependentTargetCommits(): Unit = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        val sources = listOf(entry(1), entry(2), entry(90))
        ledger.markPending(account, sources)
        val candidates = sources.map { aliasCandidate(ledger, it) }
        assertEquals(setOf(sources.last().targetIdentity), ledger.reconcileObservedPending(account, candidates))
        assertEquals(sources.take(2).ids(), ledger.pendingIdentities(account, sources.ids()))
        assertFalse(HealthImportLedger.identityDigest(minuteAlias(sources.first())) in ledger.verifiedFingerprints(account))
    }

    @Test
    fun pendingAliasCannotBeClearedEarlierInTheSameBatchToAllowAnotherCandidate(): Unit = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        val source = entry(1)
        val alias = source.copy(targetIdentity = minuteAlias(source))
        ledger.markPending(account, listOf(source, alias))
        val snapshots = ledger.pendingSnapshots(account, setOf(source.targetIdentity, alias.targetIdentity))
        val exact = HealthImportPendingReconciliation(snapshots.getValue(alias.targetIdentity), alias, "observed-minute")
        val normalized = aliasCandidate(ledger, source)
        for (candidates in listOf(listOf(exact, normalized), listOf(normalized, exact))) {
            assertTrue(ledger.reconcileObservedPending(account, candidates).isEmpty())
        }
        assertEquals(setOf(source.targetIdentity, alias.targetIdentity), ledger.pendingIdentities(account, snapshots.keys))
        assertTrue(ledger.verifiedFingerprints(account).isEmpty())
    }

    @Test
    fun arbitraryAliasMappingsAreRejectedWithoutChangingEitherIdentity(): Unit = runBlocking {
        val source = entry(1)
        val alias = minuteAlias(source)
        val invalid = listOf(
            alias.replace("RAW", "DAILY_AGGREGATE"), alias.replace("default", "watch"),
            alias.replace("spo2", "heart_rate"), alias + "daily_report", "RAW|default|spo2|1700000040|",
            "RAW|default|spo2|1699999920|", "RAW|default|spo2|1699999981|", "RAW|default|spo2|01699999980|",
            "RAW|default|spo2|-60|", "invalid",
        )
        for (target in invalid) {
            val ledger = HealthImportLedger(MemoryStore())
            ledger.markPending(account, listOf(source))
            assertTrue(ledger.reconcileObservedPending(account,
                listOf(aliasCandidate(ledger, source).copy(observedTargetIdentity = target))).isEmpty())
            assertTrue(ledger.verifiedFingerprints(account).isEmpty())
            assertEquals(setOf(source.targetIdentity), ledger.pendingIdentities(account, setOf(source.targetIdentity)))
        }
        for (original in listOf(source.copy(targetIdentity = alias), source.copy(targetIdentity = source.targetIdentity.replace("default", "other")),
            source.copy(targetIdentity = source.targetIdentity.replace("spo2", "sleep")), source.copy(zoneOffsetSeconds = null))) {
            val ledger = HealthImportLedger(MemoryStore())
            ledger.markPending(account, listOf(original))
            val snapshot = ledger.pendingSnapshots(account, setOf(original.targetIdentity)).getValue(original.targetIdentity)
            val invalidAlias = if (original.targetIdentity == alias) "RAW|default|spo2|1699999920|" else alias
            assertTrue(ledger.reconcileObservedPending(account,
                listOf(HealthImportPendingReconciliation(snapshot, original, "observed-minute", invalidAlias))).isEmpty())
        }
    }

    @Test
    fun failedAliasTransactionLeavesOriginalPendingRecoveryAndBothFingerprintsUntouched(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val source = entry(1)
        ledger.markPending(account, listOf(source))
        val reservation = assertNotNull(ledger.reserveSingleRecovery(account,
            ledger.pendingSnapshots(account, setOf(source.targetIdentity)).getValue(source.targetIdentity), source))
        val candidate = aliasCandidate(ledger, source)
        val before = ledger.pendingSnapshots(account, setOf(source.targetIdentity))
        store.failNextUpdate = true
        assertFailsWith<IllegalStateException> { ledger.reconcileObservedPending(account, listOf(candidate)) }
        assertEquals(before, ledger.pendingSnapshots(account, before.keys))
        assertTrue(ledger.verifiedFingerprints(account).isEmpty())
        assertEquals(reservation.attempt, ledger.recoveryAttempts(account, before.keys).getValue(source.targetIdentity))
        assertEquals(setOf(source.targetIdentity), ledger.reconcileObservedPending(account, listOf(candidate)))
    }

    @Test
    fun aliasBindingSurvivesRestartAndPreventsASecondOriginalFromBorrowingTheMinute(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val first = entry(1)
        val second = entry(2)
        ledger.markPending(account, listOf(first, second))
        assertEquals(setOf(first.targetIdentity), ledger.reconcileObservedPending(account, listOf(aliasCandidate(ledger, first))))
        val reopened = HealthImportLedger(store)
        // The first source need not appear in the next selected source plan.
        assertTrue(reopened.reconcileObservedPending(account, listOf(aliasCandidate(reopened, second))).isEmpty())
        assertEquals(setOf(second.targetIdentity), reopened.pendingIdentities(account, setOf(second.targetIdentity)))
        assertFalse(HealthImportLedger.identityDigest(second.targetIdentity) in reopened.verifiedFingerprints(account))
    }

    @Test
    fun concurrentDifferentOriginalsCanBindOneObservedMinuteOnlyOnce(): Unit = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        val sources = listOf(entry(1), entry(2))
        ledger.markPending(account, sources)
        val candidates = sources.map { aliasCandidate(ledger, it) }
        val result = candidates.map { candidate -> async(Dispatchers.Default) {
            ledger.reconcileObservedPending(account, listOf(candidate))
        } }.awaitAll()
        assertEquals(1, result.count { it.isNotEmpty() })
        assertEquals(1, ledger.pendingIdentities(account, sources.ids()).size)
        assertEquals(2, ledger.verifiedFingerprints(account).size)
    }

    @Test
    fun nonpendingAliasObservationCannotRemovePendingAndPreservesOnlyMatchingOwnership(): Unit = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        val source = entry(1)
        val alias = source.copy(targetIdentity = minuteAlias(source), contentFingerprint = "observed-minute", ownedUpload = true)
        ledger.markVerified(account, listOf(alias))
        val observation = HealthImportObservedAlias(source.copy(ownedUpload = true), alias.targetIdentity, "observed-minute")
        ledger.markPending(account, listOf(source))
        val before = ledger.pendingSnapshots(account, setOf(source.targetIdentity))
        assertTrue(ledger.recordObservedAliases(account, listOf(observation)).isEmpty())
        assertEquals(before, ledger.pendingSnapshots(account, before.keys))
        assertEquals(setOf(source.targetIdentity), ledger.reconcileObservedPending(account, listOf(aliasCandidate(ledger, source))))
        assertEquals(mapOf(HealthImportLedger.identityDigest(alias.targetIdentity) to "observed-minute"), ledger.ownedVerifiedFingerprints(account))
        assertEquals(setOf(source.targetIdentity), ledger.recordObservedAliases(account, listOf(observation)))
        assertEquals(2, ledger.verifiedFingerprints(account).size)
        assertTrue(ledger.recoveryAttempts(account, setOf(source.targetIdentity, alias.targetIdentity)).isEmpty())
    }

    @Test
    fun nonpendingObservationsRejectSourceOrAliasPendingAndConflictingHistory(): Unit = runBlocking {
        for (targetIsAlias in listOf(false, true)) {
            for (pending in listOf(false, true)) {
                val ledger = HealthImportLedger(MemoryStore())
                val source = entry(1)
                val alias = minuteAlias(source)
                val blocker = source.copy(targetIdentity = if (targetIsAlias) alias else source.targetIdentity,
                    contentFingerprint = "different-history")
                if (pending) ledger.markPending(account, listOf(blocker)) else ledger.markVerified(account, listOf(blocker))
                val beforePending = ledger.pendingSnapshots(account, setOf(source.targetIdentity, alias))
                val beforeVerified = ledger.verifiedFingerprints(account)
                assertTrue(ledger.recordObservedAliases(account,
                    listOf(HealthImportObservedAlias(source, alias, "observed-minute"))).isEmpty())
                assertEquals(beforePending, ledger.pendingSnapshots(account, setOf(source.targetIdentity, alias)))
                assertEquals(beforeVerified, ledger.verifiedFingerprints(account))
            }
        }
    }

    @Test
    fun nonpendingAliasBindingIsAtomicAccountScopedAndCannotBeReusedByAnotherOriginal(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val first = entry(1)
        val second = entry(2)
        fun observed(source: HealthImportJournalEntry) = HealthImportObservedAlias(source, minuteAlias(source), "observed-minute")
        assertTrue(ledger.recordObservedAliases(account, listOf(observed(first), observed(second))).isEmpty())
        assertTrue(ledger.verifiedFingerprints(account).isEmpty())
        store.failNextUpdate = true
        assertFailsWith<IllegalStateException> { ledger.recordObservedAliases(account, listOf(observed(first))) }
        assertTrue(ledger.verifiedFingerprints(account).isEmpty())
        assertEquals(setOf(first.targetIdentity), ledger.recordObservedAliases(account, listOf(observed(first))))
        val reopened = HealthImportLedger(store)
        assertTrue(reopened.recordObservedAliases(account, listOf(observed(second))).isEmpty())
        assertEquals(setOf(first.targetIdentity), reopened.recordObservedAliases(account, listOf(observed(first))))
        assertEquals(setOf(second.targetIdentity), reopened.recordObservedAliases("other-account", listOf(observed(second))))
    }

    @Test
    fun nonpendingAliasObservationNeverResetsEitherTargetsRecoveryAllowance(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val source = entry(1)
        val alias = source.copy(targetIdentity = minuteAlias(source))
        ledger.markPending(account, listOf(source, alias))
        val reservations = listOf(source, alias).map { entry ->
            assertNotNull(ledger.reserveSingleRecovery(account,
                ledger.pendingSnapshots(account, setOf(entry.targetIdentity)).getValue(entry.targetIdentity), entry))
        }
        ledger.markVerified(account, listOf(source, alias).map { it.copy(contentFingerprint = "observed-minute") })
        val observation = HealthImportObservedAlias(source, alias.targetIdentity, "observed-minute")
        assertTrue(ledger.recordObservedAliases(account, listOf(observation)).isEmpty())
        for (reservation in reservations) {
            assertTrue(ledger.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.VERIFIED,
                accepted = false, businessCode = 7, readbackStatus = "VERIFIED"))
        }
        val before = ledger.recoveryAttempts(account, setOf(source.targetIdentity, alias.targetIdentity))
        assertEquals(setOf(source.targetIdentity), ledger.recordObservedAliases(account, listOf(observation)))
        assertEquals(before, ledger.recoveryAttempts(account, before.keys))
        val reopened = HealthImportLedger(store)
        reopened.markPending(account, listOf(source, alias))
        for (entry in listOf(source, alias)) {
            assertNull(reopened.reserveSingleRecovery(account,
                reopened.pendingSnapshots(account, setOf(entry.targetIdentity)).getValue(entry.targetIdentity), entry))
        }
    }

    private suspend fun aliasCandidate(ledger: HealthImportLedger, source: HealthImportJournalEntry) =
        HealthImportPendingReconciliation(ledger.pendingSnapshots(account, setOf(source.targetIdentity)).getValue(source.targetIdentity),
            source, "observed-minute", minuteAlias(source))

    private fun minuteAlias(source: HealthImportJournalEntry): String {
        val time = source.targetIdentity.split('|')[3].toLong()
        return "RAW|default|spo2|${time.floorDiv(60L) * 60L}|"
    }

    private suspend fun Flow<Preferences>.valueFor(key: Preferences.Key<String>): String? = first()[key]

    private fun entry(index: Int) = HealthImportJournalEntry("RAW|default|spo2|${1_700_000_000L + index}|",
        "source-fingerprint-$index", setOf("synthetic-source:record-$index"), zoneOffsetSeconds = 28_800)

    private fun List<HealthImportJournalEntry>.ids() = map { it.targetIdentity }.toSet()

    private fun keySuffix(source: HealthImportJournalEntry) =
        HealthRecordIds.version("hc-import-account", account).toString() + ":" + HealthImportLedger.identityDigest(source.targetIdentity)

    private fun recoveryKey(source: HealthImportJournalEntry) = stringPreferencesKey("hc_import_single_recovery_v1:" + keySuffix(source))
    private fun verifiedKey(source: HealthImportJournalEntry) = stringPreferencesKey("hc_import_verified_v1:" + keySuffix(source))

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        var failNextUpdate = false
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
            val candidate = transform(state.value)
            if (failNextUpdate) {
                failNextUpdate = false
                throw IllegalStateException("Synthetic local persistence failure")
            }
            state.value = candidate
            candidate
        }
    }
}
