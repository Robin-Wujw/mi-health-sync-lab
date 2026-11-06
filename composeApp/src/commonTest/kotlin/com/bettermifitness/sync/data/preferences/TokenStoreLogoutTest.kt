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
    fun logoutWipesTheSessionAndSettings() = runBlocking<Unit> {
        tokenStore.saveCredentials(credentials())
        tokenStore.setAutoSync(true)

        tokenStore.clear()

        assertNull(tokenStore.loadCredentials())
        assertFalse(tokenStore.autoSync.first())
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
