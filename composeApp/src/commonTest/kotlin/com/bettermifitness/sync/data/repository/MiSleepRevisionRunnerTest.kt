package com.bettermifitness.sync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.health.HealthInboundMetric
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real ledger with fake cloud: verifies writes, pending persistence and the last check before an update. */
class MiSleepRevisionRunnerTest {
    private val account = "account"
    private val end = 1_700_000_000L
    private val old = sleep(end - 3_600, 2)
    private val revised = sleep(end - 7_200, 3)

    private fun sleep(start: Long, state: Int) = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, "default", "sleep", end),
        Json.parseToJsonElement("""{"bedtime":$start,"bed_timestamp":$start,"wake_up_time":$end,"out_bed_timestamp":$end,
            "duration":${(end - start) / 60},"timezone":32,"sleep_deep_duration":0,"sleep_light_duration":0,
            "sleep_rem_duration":0,"sleep_awake_duration":0,"awake_count":0,
            "items":[{"start_time":$start,"end_time":$end,"state":$state}]}""").jsonObject,
        28_800, setOf("com.huami.watch.hmwatchmanager:private-source-id"))
    private fun plan(row: MiHealthImportRow = revised, issues: List<MiHealthImportIssue> = emptyList()) =
        MiHealthImportPlan(HealthInboundMetric.SLEEP, listOf(row), issues, 1)
    private fun hrvRow(base: MiHealthImportRow, average: Int) = base.copy(
        value = JsonObject(base.value + mapOf("avg_hrv" to JsonPrimitive(average),
            "min_hrv" to JsonPrimitive(average - 10), "max_hrv" to JsonPrimitive(average + 10))),
        sourceRecordKeys = base.sourceRecordKeys + "com.huami.watch.hmwatchmanager:private-sample-id",
        enrichment = MiHealthImportEnrichment.SLEEP_HRV,
    )
    private fun hrvPlan(row: MiHealthImportRow) = MiHealthImportPlan(HealthInboundMetric.HRV, listOf(row), emptyList(), 1)

    @Test
    fun differingSleepIsUpdatedThenRepeatedSyncMakesNoSecondWrite() = runTest {
        val f = fixture()
        val result = f.runner.run(account, plan())
        assertEquals(1, result.diagnostic!!.sleepConflict!!.attemptedRevisionRows)
        assertEquals(1, result.diagnostic!!.sleepConflict!!.verifiedRevisionRows)
        assertEquals(1, result.diagnostic!!.verifiedRevisionRows)
        assertTrue("SLEEP_UPDATED_FROM_ZEPP" in result.warnings)
        assertEquals(revised, f.cloud.uploads.last().single())
        f.runner.run(account, plan())
        assertEquals(2, f.cloud.uploads.size)
        assertTrue(f.ledger.pendingIdentities(account, setOf(revised.identity.stableKey)).isEmpty())
    }

    /** The reported case: a night Zepp revised, whose upload history a logout had erased. */
    @Test
    fun sleepWithoutAnyUploadHistoryIsUpdatedFromZeppAndBecomesOwned() = runTest {
        val f = Fixture()
        f.cloud.stored = listOf(old.asCloud())
        val result = f.runner.run(account, plan())
        val report = result.diagnostic!!.sleepConflict!!
        assertEquals(1, report.ownershipCounts[MiSleepConflictOwnership.NO_HISTORY])
        assertEquals(1, report.verifiedRevisionRows)
        assertEquals(0, result.diagnostic!!.blockedConflictRows)
        assertEquals(revised.value, f.cloud.stored.single().value)
        assertEquals(1, f.ledger.ownedVerifiedFingerprints(account).size)
    }

    @Test
    fun hrvOnASleepWithoutUploadHistoryRepairsTheSleepAndAddsItsSummary() = runTest {
        val f = Fixture()
        f.cloud.stored = listOf(old.asCloud())
        val result = f.runner.run(account, hrvPlan(hrvRow(revised, 60)))
        assertEquals(1, result.diagnostic!!.verifiedRevisionRows)
        assertEquals(hrvRow(revised, 60).value, f.cloud.stored.single().value)
    }

    @Test
    fun sleepThenHrvLeaveTheNightMatchingZeppAndARepeatWritesNothing() = runTest {
        val f = Fixture()
        f.cloud.stored = listOf(hrvRow(old, 40).asCloud())

        f.runner.run(account, plan())
        assertEquals(revised.value["bedtime"], f.cloud.stored.single().value["bedtime"])
        assertEquals(JsonPrimitive(40), f.cloud.stored.single().value["avg_hrv"])

        f.runner.run(account, hrvPlan(hrvRow(revised, 60)))
        assertEquals(hrvRow(revised, 60).value, f.cloud.stored.single().value)
        assertEquals(2, f.cloud.uploads.size)

        f.runner.run(account, plan())
        f.runner.run(account, hrvPlan(hrvRow(revised, 60)))
        assertEquals(2, f.cloud.uploads.size)
    }

    @Test
    fun cloudExtensionsSurviveTheUpdate() = runTest {
        val f = Fixture()
        f.cloud.stored = listOf(old.asCloud().copy(value = JsonObject(old.value + ("server_extension" to JsonPrimitive("kept")))))
        f.runner.run(account, plan())
        assertEquals(JsonPrimitive("kept"), f.cloud.stored.single().value["server_extension"])
        assertEquals(revised.value["items"], f.cloud.stored.single().value["items"])
    }

    @Test
    fun otherNightsStillSyncWhenOneNightCannotBeUpdated() = runTest {
        val f = Fixture()
        val blocked = revised
        val fresh = revised.copy(identity = revised.identity.copy(time = end + 86_400))
        f.cloud.stored = listOf(old.asCloud(), old.asCloud().copy(sid = "watch"))
        val error = assertFailsWith<MiHealthImportRunException> {
            f.runner.run(account, MiHealthImportPlan(HealthInboundMetric.SLEEP, listOf(blocked, fresh), emptyList(), 2))
        }
        assertEquals(MiHealthImportFailureReason.CONFLICT, error.reason)
        assertEquals(listOf(fresh), f.cloud.uploads.flatten())
    }

    @Test
    fun pendingAndSourceTimezoneWarningsStillBlockTheUpdate() = runTest {
        val pending = fixture()
        pending.ledger.markPending(account, listOf(MiHealthImportRunner.journalEntry(revised)))
        val blocked = assertFailsWith<MiHealthImportRunException> { pending.runner.run(account, plan()) }
        assertEquals(MiHealthImportFailureReason.PENDING, blocked.reason)
        assertEquals(1, pending.cloud.uploads.size)
        val uncertain = fixture()
        val issue = MiHealthImportIssue("SOURCE_TIMEZONE_UNAVAILABLE", revised.sourceRecordKeys,
            "warning", MiHealthImportIssueSeverity.WARNING)
        val zoneBlocked = assertFailsWith<MiHealthImportRunException> {
            uncertain.runner.run(account, plan(issues = listOf(issue)))
        }
        assertEquals(0, zoneBlocked.diagnostic!!.sleepConflict!!.eligibleRevisionRows)
        assertEquals(1, uncertain.cloud.uploads.size)
    }

    @Test
    fun rowsFromOtherSourcesAndDeletedRowsAreNeverOverwritten() = runTest {
        for (extra in listOf(old.asCloud().copy(sid = "watch"), old.asCloud().copy(deleted = true))) {
            val f = Fixture()
            f.cloud.stored = listOf(old.asCloud(), extra)
            assertFailsWith<MiHealthImportRunException> { f.runner.run(account, plan()) }
            assertTrue(f.cloud.uploads.isEmpty())
        }
    }

    @Test
    fun finalCloudReadChangeStopsReplacementBeforePendingOrPost() = runTest {
        val f = fixture()
        f.cloud.beforeRead = { count ->
            if (count == 2) f.cloud.stored = listOf(old.asCloud().copy(value = JsonObject(old.value + ("extension" to JsonPrimitive(1)))))
        }
        val error = assertFailsWith<MiHealthImportRunException> { f.runner.run(account, plan()) }
        assertEquals("SLEEP_REVISION_CHANGED", error.details)
        assertEquals(1, f.cloud.uploads.size)
        assertTrue(f.ledger.pendingIdentities(account, setOf(revised.identity.stableKey)).isEmpty())
    }

    @Test
    fun uncertainWriteAppearingBeforeTheLastCheckStopsTheUpdate() = runTest {
        val f = fixture()
        f.cloud.beforeRead = { count ->
            if (count == 2) f.ledger.markPending(account, listOf(MiHealthImportRunner.journalEntry(revised)))
        }
        val error = assertFailsWith<MiHealthImportRunException> { f.runner.run(account, plan()) }
        assertEquals("SLEEP_REVISION_CHANGED", error.details)
        assertEquals(1, f.cloud.uploads.size)
    }

    @Test
    fun cancellationAfterRequestLeavesPendingAndNeverReplays() = runTest {
        val f = fixture()
        f.cloud.onUpload = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { f.runner.run(account, plan()) }
        assertEquals(setOf(revised.identity.stableKey), f.ledger.pendingIdentities(account, setOf(revised.identity.stableKey)))
        assertFailsWith<MiHealthImportRunException> { f.runner.run(account, plan()) }
        assertEquals(2, f.cloud.uploads.size)
    }

    @Test
    fun rejectionAndAuthAfterUploadStayPendingWithoutAutomaticReplay() = runTest {
        for (rejected in listOf(true, false)) {
            val f = fixture()
            f.cloud.onUpload = { if (rejected) MiHealthImportUploadResult(false, 100) else throw MiApiException.AuthExpired() }
            val error = assertFailsWith<MiHealthImportRunException> { f.runner.run(account, plan()) }
            assertEquals(if (rejected) MiHealthImportFailureReason.REJECTED else MiHealthImportFailureReason.UNVERIFIED, error.reason)
            assertEquals(0, error.diagnostic!!.sleepConflict!!.verifiedRevisionRows)
            assertFailsWith<MiHealthImportRunException> { f.runner.run(account, plan()) }
            assertEquals(2, f.cloud.uploads.size)
        }
    }

    @Test
    fun authOnLaterGroupAfterUploadIsWrappedToPreventOuterActionReplay() = runTest {
        val f = Fixture()
        val second = revised.copy(identity = revised.identity.copy(time = end + 86_400))
        f.cloud.beforeRead = { count -> if (count == 3) throw MiApiException.AuthExpired() }
        val error = assertFailsWith<MiHealthImportRunException> {
            f.runner.run(account, MiHealthImportPlan(HealthInboundMetric.SLEEP, listOf(revised, second), emptyList(), 2))
        }
        assertEquals(MiHealthImportFailureReason.UNVERIFIED, error.reason)
        assertEquals(1, f.cloud.uploads.size)
        assertEquals(1, error.verifiedRawCount)
    }

    @Test
    fun inspectFindsConflictWithZeroPendingAndNeverMutatesJournalOrCloud() = runTest {
        val f = fixture()
        val before = f.store.data.first()
        val report = MiSleepConflictInspector(f.cloud, f.ledger).inspect(account, plan())
        assertEquals(1, report.inspectedRows)
        assertEquals(1, report.mismatchedRows)
        assertEquals(1, report.eligibleRevisionRows)
        assertEquals(0, report.pendingRows)
        assertEquals(1, report.ownershipCounts[MiSleepConflictOwnership.OWNED_BASELINE_UNCHANGED])
        assertEquals(before, f.store.data.first())
        assertEquals(1, f.cloud.uploads.size)
        val text = report.copyableText()
        assertTrue("sleep_conflict.mismatch_field.items.state=1" in text)
        assertFalse("private-source-id" in text)
        assertFalse(end.toString() in text)
        assertFalse("[0]" in text)
    }

    @Test
    fun inspectorClassifiesReadFailureAsUnqueriedAndPropagatesCancellation() = runTest {
        val f = fixture()
        f.cloud.beforeRead = { throw IllegalStateException("private-health-data") }
        val report = MiSleepConflictInspector(f.cloud, f.ledger).inspect(account, plan())
        assertEquals(1, report.unqueriedRows)
        assertEquals(0, report.missingRows)
        assertEquals(1, report.readFailureGroups)
        assertFalse("private-health-data" in report.copyableText())
        f.cloud.beforeRead = { throw CancellationException() }
        assertFailsWith<CancellationException> { MiSleepConflictInspector(f.cloud, f.ledger).inspect(account, plan()) }
    }

    private suspend fun fixture(): Fixture = Fixture().also {
        it.runner.run(account, plan(old))
        it.cloud.readCount = 0
    }

    private class Fixture {
        val store = MemoryStore()
        val ledger = HealthImportLedger(store)
        val cloud = Cloud()
        val runner = MiHealthImportRunner(cloud, ledger, readbackPause = {})
    }

    private class Cloud : MiHealthImportCloud {
        var stored = emptyList<MiHealthImportCloudRow>()
        val uploads = mutableListOf<List<MiHealthImportRow>>()
        var readCount = 0
        var beforeRead: suspend (Int) -> Unit = {}
        var onUpload: suspend (List<MiHealthImportRow>) -> MiHealthImportUploadResult = { rows ->
            stored = stored.filterNot { previous ->
                rows.any { it.identity.time == previous.time && it.identity.key == previous.key } } + rows.map { it.asCloud() }
            MiHealthImportUploadResult(true)
        }
        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long,
            untilEpochSeconds: Long): List<MiHealthImportCloudRow> {
            beforeRead(++readCount)
            return stored.filter { it.key == key && it.time >= fromEpochSeconds && it.time < untilEpochSeconds }
        }
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
            assertTrue(experimentalEnabled)
            uploads += rows
            return onUpload(rows)
        }
    }

    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    companion object {
        private fun MiHealthImportRow.asCloud() = MiHealthImportCloudRow(identity.sid, identity.key, identity.time,
            identity.tag, value, zoneOffsetSeconds = zoneOffsetSeconds)
    }
}
