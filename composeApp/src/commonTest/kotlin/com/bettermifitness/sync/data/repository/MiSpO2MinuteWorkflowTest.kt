package com.bettermifitness.sync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthRecordIds
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/** Exercises real persisted original/observed identities through all three entry points. */
class MiSpO2MinuteWorkflowTest {
    private val account = "synthetic-account"
    private val minute = 20_000L * 86_400
    private fun row(time: Long = minute + 17, value: Int = 98) = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, MI_HEALTH_CONNECT_IMPORT_SID, "spo2", time),
        buildJsonObject { put("time", time); put("spo2", value) }, 28_800, setOf("synthetic:$time"))
    private fun plan(vararg rows: MiHealthImportRow) = MiHealthImportPlan(
        HealthInboundMetric.OXYGEN_SATURATION, rows.toList(), emptyList(), rows.size)
    private fun cloudRow(row: MiHealthImportRow, normalized: Boolean = true): MiHealthImportCloudRow {
        val time = if (normalized) row.identity.time.floorDiv(60) * 60 else row.identity.time
        return MiHealthImportCloudRow(row.identity.sid, row.identity.key, time, null,
            buildJsonObject { put("time", time); put("spo2", row.value.getValue("spo2")) },
            zoneOffsetSeconds = row.zoneOffsetSeconds)
    }
    private suspend fun pending(ledger: HealthImportLedger, vararg rows: MiHealthImportRow) =
        ledger.markPending(account, rows.map(MiHealthImportRunner::journalEntry))
    private suspend fun assertAliases(ledger: HealthImportLedger, source: MiHealthImportRow, actual: MiHealthImportCloudRow) {
        val fingerprints = ledger.verifiedFingerprints(account)
        val fingerprint = HealthRecordIds.version(canonicalMiHealthImportJson(actual.value)).toString()
        assertEquals(fingerprint, fingerprints[HealthImportLedger.identityDigest(source.identity.stableKey)])
        assertEquals(fingerprint, fingerprints[HealthImportLedger.identityDigest(actual.importIdentity().stableKey)])
        assertTrue(ledger.pendingIdentities(account, setOf(source.identity.stableKey)).isEmpty())
        assertTrue(ledger.ownedVerifiedFingerprints(account).isEmpty())
    }

    @Test fun legacyPendingReconciliationIsReadOnlyAndIdempotentAfterRestart(): Unit = runBlocking {
        val source = row()
        val store = Store()
        val ledger = HealthImportLedger(store)
        ledger.markPending(account, listOf(MiHealthImportRunner.journalEntry(source).copy(zoneOffsetSeconds = null)))
        val cloud = Cloud().apply { stored += cloudRow(source) }
        val first = MiPendingSpO2Reconciler(cloud, ledger).reconcile(account, plan(source), true)
        assertEquals("COMPLETE", first.pendingReconcile?.status)
        assertEquals(1, first.pendingReconcile?.minuteReconciledRows)
        assertEquals(0, first.attemptedRawRows)
        assertAliases(ledger, source, cloud.stored.single())
        val second = MiPendingSpO2Reconciler(cloud, HealthImportLedger(store)).reconcile(account, plan(source), true)
        assertEquals("NO_PENDING", second.pendingReconcile?.status)
        assertEquals(0, cloud.uploads)
    }

    @Test fun consumedRecoveryCanReconcileMinuteWithoutRestoringAllowance(): Unit = runBlocking {
        val source = row()
        val ledger = HealthImportLedger(Store())
        pending(ledger, source)
        val ids = setOf(source.identity.stableKey)
        val reservation = assertNotNull(ledger.reserveSingleRecovery(account,
            ledger.pendingSnapshots(account, ids).values.single(), MiHealthImportRunner.journalEntry(source)))
        ledger.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.READBACK_UNVERIFIED, true, 0, "MISSING")
        val cloud = Cloud().apply { stored += cloudRow(source) }
        val result = MiPendingSpO2Recovery(cloud, ledger) {}.recoverOne(account, plan(source), true)
        assertEquals("ALREADY_PRESENT", result.pendingRecovery?.status)
        assertEquals(0, cloud.uploads)
        val saved = ledger.recoveryAttempts(account, ids).values.single()
        assertEquals(reservation.attempt.token, saved.token)
        assertEquals("VERIFIED", saved.outcome)
        assertEquals(true, saved.accepted)
        assertEquals(0, saved.businessCode)
        assertAliases(ledger, source, cloud.stored.single())
    }

    @Test fun normalPrecheckResolvesBothPendingAndUnjournaledMinuteRowsWithoutUpload(): Unit = runBlocking {
        for (wasPending in listOf(true, false)) {
            val source = row()
            val ledger = HealthImportLedger(Store())
            if (wasPending) pending(ledger, source)
            val cloud = Cloud().apply { stored += cloudRow(source) }
            val result = MiHealthImportRunner(cloud, ledger) {}.run(account, plan(source))
            assertEquals(1, result.alreadyPresentRawCount)
            assertEquals(0, result.newlyUploadedRawCount)
            assertEquals(0, cloud.uploads)
            assertAliases(ledger, source, cloud.stored.single())
        }
    }

    @Test fun normalAndRecoveryPostCanVerifyMinuteReadbackWithoutSecondUpload(): Unit = runBlocking {
        for (recovery in listOf(false, true)) {
            val source = row()
            val ledger = HealthImportLedger(Store())
            val cloud = Cloud().apply { persistOnUpload = true }
            if (recovery) {
                pending(ledger, source)
                val result = MiPendingSpO2Recovery(cloud, ledger) {}.recoverOne(account, plan(source), true)
                assertEquals("VERIFIED", result.pendingRecovery?.status)
                assertEquals(1, result.newlyVerifiedRawRows)
                val attempt = ledger.recoveryAttempts(account, setOf(source.identity.stableKey)).values.single()
                assertEquals("VERIFIED", attempt.outcome)
                assertEquals(true, attempt.accepted)
            } else {
                val result = MiHealthImportRunner(cloud, ledger) {}.run(account, plan(source))
                assertEquals(1, result.newlyUploadedRawCount)
            }
            assertEquals(1, cloud.uploads)
            assertAliases(ledger, source, cloud.stored.single())
        }
    }

    @Test fun singleTestAndRecoveryUseFullPlanToBlockSameMinuteSourceCollision(): Unit = runBlocking {
        val sources = arrayOf(row(), row(minute + 27))
        for (singleTest in listOf(false, true)) {
            val cloud = Cloud()
            val ledger = HealthImportLedger(Store())
            assertFailsWith<MiHealthImportRunException> {
                MiHealthImportRunner(cloud, ledger) {}.run(account, plan(*sources), testMode = singleTest)
            }
            assertEquals(0, cloud.uploads)
            assertTrue(ledger.pendingIdentities(account, sources.map { it.identity.stableKey }.toSet()).isEmpty())
        }
        val ledger = HealthImportLedger(Store())
        pending(ledger, *sources)
        val cloud = Cloud()
        val result = MiPendingSpO2Recovery(cloud, ledger) {}.recoverOne(account, plan(*sources), true)
        assertEquals("SOURCE_COLLISION", result.pendingRecovery?.status)
        assertEquals(0, cloud.uploads)
        assertTrue(ledger.recoveryAttempts(account, sources.map { it.identity.stableKey }.toSet()).isEmpty())
        cloud.stored += cloudRow(sources.first())
        assertEquals(0, MiPendingSpO2Reconciler(cloud, ledger).reconcile(account, plan(*sources), true).verifiedRawRows)
    }

    @Test fun occupiedMinuteWithDifferentValueOtherSourceOrDeletionBlocksNewWrite(): Unit = runBlocking {
        val source = row()
        val actual = cloudRow(source)
        for (observation in listOf(cloudRow(row(value = 97)), actual.copy(sid = "other"),
            actual.copy(deleted = true), actual.copy(zoneOffsetSeconds = null))) {
            val ledger = HealthImportLedger(Store())
            val cloud = Cloud().apply { stored += observation }
            val failure = assertFailsWith<MiHealthImportRunException> {
                MiHealthImportRunner(cloud, ledger) {}.run(account, plan(source))
            }
            assertEquals(MiHealthImportFailureReason.CONFLICT, failure.reason)
            pending(ledger, source)
            assertEquals("CONFLICT", MiPendingSpO2Recovery(cloud, ledger) {}.recoverOne(account, plan(source), true).pendingRecovery?.status)
            assertEquals(0, cloud.uploads)
        }
    }

    @Test fun readConflictCannotDisappearWhenLaterNativeQueryReturnsMinuteMatch(): Unit = runBlocking {
        val source = row()
        val ledger = HealthImportLedger(Store())
        pending(ledger, source)
        val cloud = Cloud().apply {
            stored += cloudRow(source).copy(deleted = true)
            historyRows = listOf(cloudRow(source))
        }
        val result = MiPendingSpO2Reconciler(cloud, ledger).reconcile(account, plan(source), true)
        assertEquals(0, result.verifiedRawRows)
        assertEquals(setOf(source.identity.stableKey), ledger.pendingIdentities(account, setOf(source.identity.stableKey)))
        assertEquals(0, cloud.uploads)
    }

    @Test fun postReadConflictCannotDisappearOnLaterPolls(): Unit = runBlocking {
        val source = row()
        for (recovery in listOf(false, true)) {
            val ledger = HealthImportLedger(Store())
            val cloud = Cloud().apply { conflictFirstPostRead = true; persistOnUpload = true }
            if (recovery) {
                pending(ledger, source)
                assertEquals(0, MiPendingSpO2Recovery(cloud, ledger) {}.recoverOne(account, plan(source), true).verifiedRawRows)
            } else assertFailsWith<MiHealthImportRunException> {
                MiHealthImportRunner(cloud, ledger) {}.run(account, plan(source))
            }
            assertEquals(1, cloud.uploads)
            assertEquals(setOf(source.identity.stableKey), ledger.pendingIdentities(account, setOf(source.identity.stableKey)))
            assertTrue(ledger.verifiedFingerprints(account).isEmpty())
        }
    }

    @Test fun newSourceWindowCannotReuseAnAlreadyBoundCloudMinute(): Unit = runBlocking {
        val original = row()
        val laterSource = row(minute + 27)
        val ledger = HealthImportLedger(Store())
        val cloud = Cloud().apply { stored += cloudRow(original) }
        MiHealthImportRunner(cloud, ledger) {}.run(account, plan(original))
        assertFailsWith<MiHealthImportRunException> { MiHealthImportRunner(cloud, ledger) {}.run(account, plan(laterSource)) }
        cloud.stored.clear() // Query omission must not permit overwriting the old minute either.
        assertFailsWith<MiHealthImportRunException> { MiHealthImportRunner(cloud, ledger) {}.run(account, plan(laterSource)) }
        pending(ledger, laterSource)
        assertEquals("RESERVATION_CHANGED", MiPendingSpO2Recovery(cloud, ledger) {}.recoverOne(account, plan(laterSource), true).pendingRecovery?.status)
        assertEquals(0, cloud.uploads)
    }

    @Test fun postMinuteLocalAuthShapedFailureKeepsPendingAndCannotEscapeAsAnAuthRetry(): Unit = runBlocking {
        val source = row()
        val ledger = HealthImportLedger(Store())
        var reconciliationCalls = 0
        val localFailure = MiApiException.AuthExpired()
        val failingJournal = object : HealthImportJournal by ledger {
            override suspend fun reconcileObservedPending(account: String,
                entries: List<HealthImportPendingReconciliation>): Set<String> {
                reconciliationCalls++
                throw localFailure
            }
        }
        val cloud = Cloud().apply { persistOnUpload = true }
        val failure = assertFailsWith<MiHealthImportRunException> {
            MiHealthImportRunner(cloud, failingJournal) {}.run(account, plan(source))
        }
        assertEquals(MiHealthImportFailureReason.UNVERIFIED, failure.reason)
        assertSame(localFailure, failure.cause)
        assertEquals(MiHealthImportStage.READBACK, failure.diagnostic?.stage)
        assertEquals(1, failure.diagnostic?.attemptedRawRows)
        assertEquals(0, failure.diagnostic?.verifiedRawRows)
        assertEquals(1, reconciliationCalls)
        assertEquals(1, cloud.uploads)
        assertEquals(listOf(cloudRow(source)), cloud.stored)
        assertEquals(setOf(source.identity.stableKey), ledger.pendingIdentities(account, setOf(source.identity.stableKey)))
        assertTrue(ledger.verifiedFingerprints(account).isEmpty())
        assertTrue(ledger.ownedVerifiedFingerprints(account).isEmpty())
    }

    @Test fun rejectedSourcePlanCannotResolvePendingOrCreateMinuteAliases(): Unit = runBlocking {
        val source = row()
        // Rejected input may conceal another target in this minute, even if its
        // record key differs from the valid target chosen by single-row mode.
        val rejectedPlan = plan(source).copy(issues = listOf(MiHealthImportIssue(
            "INVALID_SOURCE", setOf("synthetic-rejected-source"), "Unusable source",
            MiHealthImportIssueSeverity.REJECTED)))
        for (wasPending in listOf(false, true)) for (singleTest in listOf(false, true)) {
            val ledger = HealthImportLedger(Store())
            val ids = setOf(source.identity.stableKey)
            if (wasPending) pending(ledger, source)
            val before = ledger.pendingSnapshots(account, ids)
            val cloud = Cloud().apply { stored += cloudRow(source) }
            val failure = assertFailsWith<MiHealthImportRunException> {
                MiHealthImportRunner(cloud, ledger) {}.run(account, rejectedPlan, testMode = singleTest)
            }
            assertEquals(0, failure.verifiedRawCount)
            assertEquals(0, cloud.uploads)
            assertEquals(before, ledger.pendingSnapshots(account, ids))
            assertEquals(if (wasPending) ids else emptySet(), ledger.pendingIdentities(account, ids))
            assertTrue(ledger.verifiedFingerprints(account).isEmpty())
            assertTrue(ledger.ownedVerifiedFingerprints(account).isEmpty())
        }
    }

    private inner class Cloud : MiHealthImportCloud {
        val stored = mutableListOf<MiHealthImportCloudRow>()
        var uploads = 0
        var persistOnUpload = false
        var historyRows: List<MiHealthImportCloudRow>? = null
        var conflictFirstPostRead = false
        private var postReads = 0
        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long,
            untilEpochSeconds: Long): List<MiHealthImportCloudRow> {
            if (uploads > 0 && ++postReads == 1 && conflictFirstPostRead) return stored.map { it.copy(deleted = true) }
            return stored.filter { it.time >= fromEpochSeconds && it.time < untilEpochSeconds }
        }
        override suspend fun readRawRowsWithoutStart(key: String, untilEpochSeconds: Long, maxPages: Int) =
            MiHealthImportHistoryRead(historyRows ?: stored.toList(), 1, true)
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
            uploads++
            if (persistOnUpload) stored += rows.map { cloudRow(it) }
            return MiHealthImportUploadResult(true, 0, "ok")
        }
    }
    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }
}
