package com.bettermifitness.sync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloud
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportMapper
import com.bettermifitness.sync.data.api.MiHealthImportPlan
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiHealthImportUploadResult
import com.bettermifitness.sync.data.api.MiSleepVitalsMapper
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.data.preferences.SleepFileLedger
import com.bettermifitness.sync.health.HealthInboundHeartRateRecord
import com.bettermifitness.sync.health.HealthInboundHeartRateSample
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthInboundSleepStage
import com.bettermifitness.sync.health.HealthInboundSleepStageKind
import com.bettermifitness.sync.health.HealthRecordOrigin
import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.fds.FdsClient
import com.mifitness.miclient.fds.SportFileTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MiSleepFilesRunnerTest {
    private val account = "synthetic-account"
    private val source = "com.huami.watch.hmwatchmanager"
    private val start = 1_800_000_000L
    private fun origin(id: String) = HealthRecordOrigin(id, source, null, 1, 1)
    private val sleep = HealthInboundSleepRecord(origin("night"), start * 1_000, (start + 600) * 1_000, 0, 0,
        listOf(HealthInboundSleepStage(start * 1_000, (start + 600) * 1_000, HealthInboundSleepStageKind.LIGHT)))
    private fun plan(value: Long = 60): MiHealthImportPlan {
        val hearts = listOf(HealthInboundHeartRateRecord(origin("heart"), start * 1_000, (start + 600) * 1_000, 0, 0,
            listOf(HealthInboundHeartRateSample((start + 60) * 1_000, value))))
        return MiSleepVitalsMapper.enrich(MiHealthImportMapper.sleep(listOf(sleep), source),
            listOf(sleep), source, hearts, emptyList())
    }

    @Test fun normalWorkflowVerifiesFileBeforeSummaryAndRepeatsWithoutWriting() = runTest {
        val fixture = Fixture()
        val prepared = fixture.prepare(plan())
        assertEquals(1, fixture.files.uploads)
        assertTrue(fixture.cloud.uploads.isEmpty())
        assertEquals(JsonPrimitive(2), prepared.rawRows.single().value["version"])
        val summary = prepared.rawRows.single()
        assertEquals(JsonPrimitive(start), summary.value["bedtime"])
        assertEquals(JsonPrimitive(start + 600), summary.value["wake_up_time"])
        fixture.sync(prepared)
        fixture.sync(fixture.prepare(plan()))
        assertEquals(1, fixture.files.uploads)
        assertEquals(1, fixture.cloud.uploads.size)
    }

    @Test fun rawRunnerCannotBypassTheRequiredFileReadback() = runTest {
        val fixture = Fixture()
        assertFailsWith<IllegalStateException> { fixture.sync(plan()) }
        assertEquals(0, fixture.files.uploads)
        assertTrue(fixture.cloud.uploads.isEmpty())
    }

    @Test fun lateSourceDataUsesANewFileReferenceAndPreservesHrvAndCloudExtensions() = runTest {
        val fixture = Fixture()
        fixture.sync(fixture.prepare(plan()))
        val original = fixture.cloud.stored.single()
        fixture.cloud.stored = listOf(original.copy(value = JsonObject(original.value + mapOf(
            "avg_hrv" to JsonPrimitive(45), "source_extension" to JsonPrimitive("retained")))))
        val updated = fixture.prepare(plan(70))
        assertTrue(updated.rawRows.single().value["protoTime"] != original.value["protoTime"])
        fixture.sync(updated)
        val current = fixture.cloud.stored.single().value
        assertEquals(JsonPrimitive(45), current["avg_hrv"])
        assertEquals(JsonPrimitive("retained"), current["source_extension"])
        assertEquals(JsonPrimitive(70), current["avg_hr"])
        assertEquals(2, fixture.files.bodies.size)
    }

    @Test fun lostResponseWithPersistedBodyIsConfirmedWithoutResending() = runTest {
        val fixture = Fixture()
        fixture.files.loseResponse = true
        assertEquals(1, fixture.prepare(plan()).rawRows.size)
        assertEquals(1, fixture.prepare(plan()).rawRows.size)
        assertEquals(1, fixture.files.uploads)
    }

    @Test fun unconfirmedBodyIsNotReplayedOrBypassedByChangedSource() = runTest {
        val fixture = Fixture()
        fixture.files.storeWrites = false
        assertTrue(fixture.prepare(plan()).rawRows.isEmpty())
        assertTrue(fixture.prepare(plan()).rawRows.isEmpty())
        assertTrue(fixture.prepare(plan(70)).rawRows.isEmpty())
        assertEquals(1, fixture.files.uploads)
        assertTrue(fixture.cloud.uploads.isEmpty())
    }

    @Test fun addressFailureBeforeBodyAttemptCanRetryNormally() = runTest {
        val fixture = Fixture()
        fixture.files.failPreparation = true
        assertTrue(fixture.prepare(plan()).rawRows.isEmpty())
        fixture.files.failPreparation = false
        assertEquals(1, fixture.prepare(plan()).rawRows.size)
        assertEquals(1, fixture.files.uploads)
    }

    @Test fun pendingSummaryBlocksNewFileGeneration() = runTest {
        val fixture = Fixture()
        val original = fixture.prepare(plan()).rawRows.single()
        fixture.cloud.stored = listOf(original.toCloud())
        fixture.imports.markPending(account, listOf(MiHealthImportRunner.journalEntry(original)))
        assertTrue(fixture.prepare(plan(70)).rawRows.isEmpty())
        assertEquals(1, fixture.files.uploads)
    }

    @Test fun verifiedFileLossIsReadTwiceThenRecreatedAtANewReference() = runTest {
        val fixture = Fixture()
        fixture.sync(fixture.prepare(plan()))
        val before = fixture.cloud.stored.single().value["protoTime"]
        fixture.files.bodies.clear()
        val repaired = fixture.prepare(plan())
        assertEquals(2, fixture.files.uploads)
        assertTrue(repaired.rawRows.single().value["protoTime"] != before)
        fixture.sync(repaired)
        assertEquals(2, fixture.cloud.uploads.size)
    }

    @Test fun existingDifferentBytesAreNeverOverwritten() = runTest {
        val fixture = Fixture()
        fixture.files.bodies[FdsClient.SportFileRequest("default", start + 601, 0, 8, 0, true)] = byteArrayOf(1, 2)
        assertTrue(fixture.prepare(plan()).rawRows.isEmpty())
        assertEquals(0, fixture.files.uploads)
    }

    @Test fun foreignSleepOrTombstonePreventsFileWrites() = runTest {
        for (deleted in listOf(false, true)) {
            val fixture = Fixture()
            fixture.cloud.stored = listOf(plan().rawRows.single().toCloud().copy(
                sid = if (deleted) "default" else "other", deleted = deleted))
            assertTrue(fixture.prepare(plan()).rawRows.isEmpty())
            assertEquals(0, fixture.files.uploads)
        }
    }

    @Test fun cancellationKeepsTheBodyReservation() = runTest {
        val fixture = Fixture()
        fixture.files.cancelWrite = true
        assertFailsWith<CancellationException> { fixture.prepare(plan()) }
        fixture.files.cancelWrite = false
        assertTrue(fixture.prepare(plan()).rawRows.isEmpty())
        assertEquals(1, fixture.files.uploads)
    }

    @Test fun fileFailureIsReportedAsUnresolvedWhileOtherSleepsCanContinue() = runTest {
        val fixture = Fixture()
        fixture.files.storeWrites = false
        val prepared = fixture.prepare(plan())
        assertTrue(prepared.issues.any { it.code == "SLEEP_FILE_UNVERIFIED" })
        assertFailsWith<MiHealthImportRunException> { fixture.sync(prepared) }
        assertTrue(fixture.cloud.uploads.isEmpty())
    }

    private inner class Fixture {
        val store = MemoryStore()
        val imports = HealthImportLedger(store)
        val cloud = Cloud()
        val files = Files()
        suspend fun prepare(plan: MiHealthImportPlan) = MiSleepFilesRunner(cloud, SleepFileLedger(store), imports, files)
            .prepare(account, plan)
        suspend fun sync(plan: MiHealthImportPlan) = MiHealthImportWorkflow(cloud, imports, readbackPause = {}).run(account, plan)
    }

    private class Files : SportFileTransport {
        val bodies = mutableMapOf<FdsClient.SportFileRequest, ByteArray>()
        var uploads = 0
        var storeWrites = true
        var loseResponse = false
        var failPreparation = false
        var cancelWrite = false
        private val prepared = mutableMapOf<String, FdsClient.SportFileRequest>()
        override suspend fun prepareSportFile(request: FdsClient.SportFileRequest): FdsClient.UploadMeta {
            if (failPreparation) error("synthetic address failure")
            assertTrue(request.daily)
            val key = "file-${prepared.size}"
            prepared[key] = request
            return FdsClient.UploadMeta("https://synthetic.invalid/$key", "PUT", key)
        }
        override suspend fun uploadPreparedSportFile(meta: FdsClient.UploadMeta, bytes: ByteArray) {
            uploads++
            if (cancelWrite) throw CancellationException("synthetic cancellation")
            if (storeWrites) bodies[prepared.getValue(meta.objectKey)] = bytes.copyOf()
            if (loseResponse) error("synthetic response loss")
        }
        override suspend fun downloadSportFile(request: FdsClient.SportFileRequest) = bodies[request]?.copyOf()
            ?: throw MiApiException.Server(404, "synthetic missing")
    }

    private class Cloud : MiHealthImportCloud {
        var stored = emptyList<MiHealthImportCloudRow>()
        val uploads = mutableListOf<List<MiHealthImportRow>>()
        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long, untilEpochSeconds: Long) =
            stored.filter { it.key == key && it.time >= fromEpochSeconds && it.time < untilEpochSeconds }
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
            assertTrue(experimentalEnabled)
            uploads += rows
            stored = stored.filterNot { existing -> rows.any { it.identity.time == existing.time } } + rows.map { it.toCloud() }
            return MiHealthImportUploadResult(true)
        }
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }

    private companion object {
        fun MiHealthImportRow.toCloud() = MiHealthImportCloudRow(identity.sid, identity.key, identity.time,
            identity.tag, value, zoneOffsetSeconds = zoneOffsetSeconds)
    }
}
