package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercise the real persisted ownership format, including pre-.3 snapshots. */
class HealthImportLedgerOwnershipTest {
    private val account = "synthetic-account"
    private val entry = HealthImportJournalEntry("RAW|default|sleep|1700000000|", "fingerprint-a", setOf("source:one"))
    private val digest = HealthImportLedger.identityDigest(entry.targetIdentity)

    @Test
    fun anObservedMatchIsNotOwnedAndCannotUseTheLegacyException() = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, listOf(entry))
        assertEquals(mapOf(digest to "fingerprint-a"), ledger.verifiedFingerprints(account))
        assertTrue(ledger.ownedVerifiedFingerprints(account).isEmpty())
        assertTrue(ledger.legacyVerifiedFingerprints(account).isEmpty())
    }

    @Test
    fun aSubmittedAndVerifiedRowRemainsOwnedAcrossEqualReadOnlyChecks() = runBlocking {
        val store = MemoryStore()
        val first = HealthImportLedger(store)
        first.markVerified(account, listOf(entry.copy(ownedUpload = true)))
        val reopened = HealthImportLedger(store)
        reopened.markVerified(account, listOf(entry))
        assertEquals(mapOf(digest to "fingerprint-a"), reopened.ownedVerifiedFingerprints(account))
    }

    @Test
    fun externallyChangedContentCannotInheritTheOldOwnership() = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, listOf(entry.copy(ownedUpload = true)))
        ledger.markVerified(account, listOf(entry.copy(contentFingerprint = "fingerprint-b")))
        assertEquals(mapOf(digest to "fingerprint-b"), ledger.verifiedFingerprints(account))
        assertTrue(ledger.ownedVerifiedFingerprints(account).isEmpty())
        assertTrue(ledger.legacyVerifiedFingerprints(account).isEmpty())
    }

    @Test
    fun aLegacyFingerprintStaysLegacyUntilAnActualNewUploadIsVerified() = runBlocking {
        val accountDigest = HealthRecordIds.version("hc-import-account", account).toString()
        val key = stringPreferencesKey("hc_import_verified_v1:$accountDigest:$digest")
        val store = MemoryStore(mutablePreferencesOf(key to entry.contentFingerprint))
        val ledger = HealthImportLedger(store)
        assertEquals(mapOf(digest to "fingerprint-a"), ledger.legacyVerifiedFingerprints(account))
        ledger.markVerified(account, listOf(entry))
        assertEquals(mapOf(digest to "fingerprint-a"), ledger.legacyVerifiedFingerprints(account))
        assertTrue(ledger.ownedVerifiedFingerprints(account).isEmpty())
        ledger.markVerified(account, listOf(entry.copy(contentFingerprint = "enriched", ownedUpload = true)))
        assertTrue(ledger.legacyVerifiedFingerprints(account).isEmpty())
        assertEquals(mapOf(digest to "enriched"), ledger.ownedVerifiedFingerprints(account))
    }

    @Test
    fun permittingRetriesDoesNotGrantOwnershipOrDiscardVerifiedFingerprints() = runBlocking {
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, listOf(entry))
        ledger.markPending(account, listOf(entry.copy(contentFingerprint = "new-pending")))
        ledger.resetPendingImports()
        assertTrue(ledger.pendingIdentities(account, setOf(entry.targetIdentity)).isEmpty())
        assertEquals(mapOf(digest to "fingerprint-a"), ledger.verifiedFingerprints(account))
        assertTrue(ledger.ownedVerifiedFingerprints(account).isEmpty())
    }

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
