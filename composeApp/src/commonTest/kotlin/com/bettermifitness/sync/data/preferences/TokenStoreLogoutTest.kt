package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.mifitness.miclient.auth.MiCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All of these stores share one preferences file, so a logout must not wipe the file wholesale. */
class TokenStoreLogoutTest {
    private val store = MemoryStore()
    private val tokenStore = TokenStore(store)
    private val importLedger = HealthImportLedger(store)
    private val bodyLedger = BodySyncLedger(store)

    @Test
    fun logoutWipesTheSessionAndKeepsSettings() = runBlocking<Unit> {
        tokenStore.saveCredentials(credentials())
        tokenStore.setAutoSync(true)

        tokenStore.clear()

        assertNull(tokenStore.loadCredentials())
        assertTrue(tokenStore.autoSync.first())
    }

    @Test
    fun defaultsEnableScaleBodyCompositionAndAllZeppImports() = runBlocking<Unit> {
        val enabled = tokenStore.enabledMetrics.first()
        assertTrue("weight" in enabled)
        assertTrue(SyncPreferences.BODY_COMPOSITION_IMPORT_KEY in enabled)
        assertTrue(enabled.containsAll(SyncPreferences.INBOUND_METRIC_KEYS))
        assertFalse(tokenStore.sync.inboundTestMode.first())
    }

    @Test
    fun upgradeEnablesRequestedDefaultsOnceAndPreservesOtherChoices() = runBlocking<Unit> {
        val legacyStore = MemoryStore()
        legacyStore.updateData { it.toMutablePreferences().apply {
            this[androidx.datastore.preferences.core.stringSetPreferencesKey("enabled_metrics")] = setOf("sleep")
        } }
        val upgraded = TokenStore(legacyStore)
        val enabled = upgraded.enabledMetrics.first()
        assertTrue(enabled.containsAll(SyncPreferences.INBOUND_METRIC_KEYS))
        assertTrue(SyncPreferences.BODY_COMPOSITION_IMPORT_KEY in enabled)
        assertFalse("workouts" in enabled)
        upgraded.setMetricEnabled(SyncPreferences.SLEEP_IMPORT_KEY, false)
        assertFalse(SyncPreferences.SLEEP_IMPORT_KEY in TokenStore(legacyStore).enabledMetrics.first())
    }

    @Test
    fun reLoginPreservesSelectionsRangeAndAutoSyncButClearsOldActivity() = runBlocking<Unit> {
        tokenStore.saveCredentials(credentials())
        tokenStore.setAutoSync(true)
        tokenStore.setSyncRangeDays(30)
        tokenStore.setMetricEnabled(SyncPreferences.SPO2_IMPORT_KEY, false)
        val enabled = tokenStore.enabledMetrics.first()
        tokenStore.sync.updateLastSyncOutcome("not_logged_in", "old rejection")
        tokenStore.updateLastSync("yesterday")
        tokenStore.clear()
        tokenStore.saveCredentials(credentials())
        val restored = TokenStore(store)
        assertEquals(enabled, restored.enabledMetrics.first())
        assertEquals(30, restored.syncRangeDays.first())
        assertTrue(restored.autoSync.first())
        assertNull(restored.lastSyncTime.first())
        assertNull(restored.sync.lastSyncMessage.first())
        assertNull(restored.sync.lastSyncStatus.first())
    }

    @Test
    fun logoutKeepsTheDeviceIdentity() = runBlocking<Unit> {
        tokenStore.saveCredentials(credentials())

        tokenStore.clear()

        assertEquals("device", tokenStore.credentials.loadDeviceId())
    }

    @Test
    fun logoutKeepsWhatThisAppUploaded() = runBlocking<Unit> {
        tokenStore.saveCredentials(credentials())
        importLedger.markVerified(ACCOUNT, listOf(entry(UPLOADED, ownedUpload = true)))

        tokenStore.clear()

        val digest = HealthImportLedger.identityDigest(UPLOADED)
        assertEquals(mapOf(digest to "content"), importLedger.ownedVerifiedFingerprints(ACCOUNT))
    }

    @Test
    fun logoutKeepsTheLockOnAnUncertainUpload() = runBlocking<Unit> {
        tokenStore.saveCredentials(credentials())
        importLedger.markPending(ACCOUNT, listOf(entry(UNCERTAIN)))

        tokenStore.clear()

        assertEquals(setOf(UNCERTAIN), importLedger.pendingIdentities(ACCOUNT, setOf(UNCERTAIN)))
    }

    @Test
    fun logoutKeepsTheBodyJournal() = runBlocking<Unit> {
        tokenStore.saveCredentials(credentials())
        bodyLedger.noteExport(ACCOUNT, "exported")
        bodyLedger.noteAttempt(ACCOUNT, "uploaded", "pending")

        tokenStore.clear()

        assertEquals("exported", bodyLedger.lastExport(ACCOUNT))
        assertEquals("pending", bodyLedger.attempt(ACCOUNT, "uploaded"))
    }

    @Test
    fun onlyDocumentedRateLimitRejectionCanRetryAfterDurableCooldown() = runBlocking<Unit> {
        var timestamp = 1000L
        val ledger = BodySyncLedger(store) { timestamp }
        assertEquals(BodyUploadDecision.Send(1), ledger.beginUpload(ACCOUNT, "limited"))
        assertEquals(BodyUploadDecision.VerifyOnly, ledger.beginUpload(ACCOUNT, "limited"))
        ledger.noteRateLimit(ACCOUNT, "limited", sendCount = 1)
        val reopened = BodySyncLedger(store) { timestamp }
        assertEquals(BodyUploadDecision.CoolingDown, reopened.beginUpload(ACCOUNT, "limited"))
        timestamp += 900
        assertEquals(BodyUploadDecision.Send(2), reopened.beginUpload(ACCOUNT, "limited"))
        reopened.noteRateLimit(ACCOUNT, "limited", sendCount = 2)
        timestamp += 1800
        assertEquals(BodyUploadDecision.Send(3), reopened.beginUpload(ACCOUNT, "limited"))
        reopened.noteRateLimit(ACCOUNT, "limited", sendCount = 3)
        timestamp += 2700
        assertEquals(BodyUploadDecision.RetryLimit, reopened.beginUpload(ACCOUNT, "limited"))
    }

    @Test
    fun fullBodyJournalNeverPrunesAnUnknownWrite() = runBlocking<Unit> {
        repeat(128) { index ->
            assertEquals(BodyUploadDecision.Send(1), bodyLedger.beginUpload(ACCOUNT, "unknown-$index"))
        }
        assertEquals(BodyUploadDecision.AtCapacity, bodyLedger.beginUpload(ACCOUNT, "new"))
        assertEquals(BodyUploadDecision.VerifyOnly, bodyLedger.beginUpload(ACCOUNT, "unknown-0"))
        bodyLedger.noteAttempt(ACCOUNT, "unknown-1", "verified")
        assertEquals(BodyUploadDecision.Send(1), bodyLedger.beginUpload(ACCOUNT, "new"))
        assertEquals("pending", bodyLedger.attempt(ACCOUNT, "unknown-0"))
    }

    @Test
    fun everyJournalPreferenceSurvivesAndNothingElseDoes() {
        for (name in listOf(
            "hc_import_pending_v1:a", "hc_import_verified_v1:a", "hc_import_ownership_v2:a",
            "hc_import_single_recovery_v1:a", "hc_import_spo2_alias_source_v1:a",
        )) assertTrue(HealthImportLedger.ownsPreference(name), name)
        assertTrue(BodySyncLedger.ownsPreference("body_import_attempt:a"))
        assertTrue(BodySyncLedger.ownsPreference("body_export:a"))
        for (name in listOf("bearer_token", "mi_user_id", "enabled_metrics", "sync_range_days")) {
            assertFalse(HealthImportLedger.ownsPreference(name), name)
            assertFalse(BodySyncLedger.ownsPreference(name), name)
        }
    }

    @Test
    fun anotherAccountCannotSeeTheHistoryLeftBehind() = runBlocking<Unit> {
        importLedger.markVerified(ACCOUNT, listOf(entry(UPLOADED, ownedUpload = true)))

        tokenStore.clear()

        assertEquals(emptyMap(), importLedger.verifiedFingerprints("another-account"))
    }

    private fun credentials() = MiCredentials(
        userId = ACCOUNT,
        ssecurity = "ssecurity",
        serviceToken = "service-token",
        passToken = "pass-token",
        deviceId = "device",
        region = "sg",
    )

    private fun entry(identity: String, ownedUpload: Boolean = false) =
        HealthImportJournalEntry(identity, "content", setOf("source"), ownedUpload)

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private companion object {
        const val ACCOUNT = "account"
        const val UPLOADED = "RAW|default|sleep|1700000000|"
        const val UNCERTAIN = "RAW|default|sleep|1700086400|"
    }
}
