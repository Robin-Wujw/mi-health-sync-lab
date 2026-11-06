package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthImportLedgerRecoveryTest {
    private val account = "synthetic-account"
    private val entry = HealthImportJournalEntry("RAW|default|spo2|1700000000|", "fingerprint-a",
        setOf("source:one"), zoneOffsetSeconds = 28_800)
    private val ids = setOf(entry.targetIdentity)

    @Test
    fun legacyPendingRetainsUnknownZoneAndOneConsumedAllowanceAcrossRestartAndReset(): Unit = runBlocking {
        val store = MemoryStore(mutablePreferencesOf(pendingKey() to "123|${entry.contentFingerprint}|${HealthImportLedger.sourceDigest(entry.sourceRecordKeys)}"))
        val ledger = HealthImportLedger(store)
        val before = ledger.pendingSnapshots(account, ids).getValue(entry.targetIdentity)
        assertTrue(before.valid)
        assertNull(before.zoneOffsetSeconds)
        val reservation = assertNotNull(ledger.reserveSingleRecovery(account, before, entry))
        assertTrue(reservation.attempt.legacyWireMetadataUnknown)
        assertTrue(ledger.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.ACCEPTED,
            accepted = true, businessCode = 0))

        val reopened = HealthImportLedger(store)
        reopened.resetPendingImports()
        assertEquals(ids, reopened.pendingIdentities(account, ids))
        // A normal markPending can update its record, but cannot grant another allowance.
        reopened.markPending(account, listOf(entry))
        val after = reopened.pendingSnapshots(account, ids).getValue(entry.targetIdentity)
        assertTrue(after.recoveryConsumed)
        assertEquals(HealthImportRecoveryOutcome.ACCEPTED.name, after.recoveryAttempt?.outcome)
        assertEquals(true, after.recoveryAttempt?.accepted)
        assertEquals(0, after.recoveryAttempt?.businessCode)
        assertNull(reopened.reserveSingleRecovery(account, after, entry))
    }

    @Test
    fun knownContentSourcesAndZoneMustAllMatchAndConcurrentReservationsOnlyConsumeOnce(): Unit = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markPending(account, listOf(entry))
        val snapshot = ledger.pendingSnapshots(account, ids).getValue(entry.targetIdentity)
        assertEquals(28_800, snapshot.zoneOffsetSeconds)
        assertNull(ledger.reserveSingleRecovery(account, snapshot, entry.copy(contentFingerprint = "changed")))
        assertNull(ledger.reserveSingleRecovery(account, snapshot, entry.copy(sourceRecordKeys = setOf("source:two"))))
        assertNull(ledger.reserveSingleRecovery(account, snapshot, entry.copy(zoneOffsetSeconds = 0)))
        assertNull(ledger.reserveSingleRecovery(account, snapshot, entry.copy(zoneOffsetSeconds = null)))
        val reservations = List(8) { async { ledger.reserveSingleRecovery(account, snapshot, entry) } }.awaitAll()
        assertEquals(1, reservations.count { it != null })
        assertFalse(reservations.filterNotNull().single().attempt.legacyWireMetadataUnknown)
    }

    @Test
    fun snapshotChangesBeforeReservationPreventAnyAllowanceFromBeingConsumed(): Unit = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markPending(account, listOf(entry))
        val before = ledger.pendingSnapshots(account, ids).getValue(entry.targetIdentity)
        ledger.markPending(account, listOf(entry.copy(contentFingerprint = "changed")))
        assertNull(ledger.reserveSingleRecovery(account, before, entry))
        assertFalse(ledger.pendingSnapshots(account, ids).getValue(entry.targetIdentity).recoveryConsumed)
    }

    @Test
    fun malformedPendingAndMalformedConsumedMetadataFailClosed(): Unit = runBlocking {
        val store = MemoryStore(mutablePreferencesOf(pendingKey() to "bad|metadata"))
        val ledger = HealthImportLedger(store)
        val invalid = ledger.pendingSnapshots(account, ids).getValue(entry.targetIdentity)
        assertFalse(invalid.valid)
        assertNull(ledger.reserveSingleRecovery(account, invalid, entry))

        ledger.markPending(account, listOf(entry))
        val prefix = pendingKey().name.removePrefix("hc_import_pending_v1:")
        store.edit { prefs ->
            prefs[stringPreferencesKey("hc_import_single_recovery_v1:$prefix")] = "malformed-consumed-metadata"
        }
        val consumed = ledger.pendingSnapshots(account, ids).getValue(entry.targetIdentity)
        assertTrue(consumed.recoveryConsumed)
        assertNull(consumed.recoveryAttempt)
        assertNull(ledger.reserveSingleRecovery(account, consumed, entry))
        ledger.resetPendingImports()
        assertEquals(ids, ledger.pendingIdentities(account, ids))
    }

    @Test
    fun outcomesSurviveReadbackAndVerificationWithoutRearmingOrGrantingObservedOwnership(): Unit = runBlocking {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        ledger.markPending(account, listOf(entry))
        val reservation = assertNotNull(ledger.reserveSingleRecovery(account,
            ledger.pendingSnapshots(account, ids).getValue(entry.targetIdentity), entry))
        assertTrue(ledger.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.REJECTED,
            accepted = false, businessCode = 100))
        assertTrue(ledger.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.REJECTED,
            readbackStatus = "MISSING"))
        val rejected = ledger.recoveryAttempts(account, ids).getValue(entry.targetIdentity)
        assertEquals(false, rejected.accepted)
        assertEquals(100, rejected.businessCode)
        assertEquals("MISSING", rejected.readbackStatus)
        assertTrue(ledger.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.VERIFIED,
            readbackStatus = "VERIFIED"))
        ledger.markVerified(account, listOf(entry))
        assertTrue(ledger.pendingIdentities(account, ids).isEmpty())
        assertTrue(ledger.ownedVerifiedFingerprints(account).isEmpty())
        val reopened = HealthImportLedger(store)
        assertEquals(HealthImportRecoveryOutcome.VERIFIED.name,
            reopened.recoveryAttempts(account, ids).getValue(entry.targetIdentity).outcome)
        assertFalse(reopened.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.TRANSPORT_UNKNOWN))
        reopened.markPending(account, listOf(entry))
        val after = reopened.pendingSnapshots(account, ids).getValue(entry.targetIdentity)
        assertNull(reopened.reserveSingleRecovery(account, after, entry))
    }

    private fun pendingKey() = stringPreferencesKey("hc_import_pending_v1:" +
        HealthRecordIds.version("hc-import-account", account) + ":" + HealthImportLedger.identityDigest(entry.targetIdentity))

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }
}
