package com.bettermifitness.sync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.WorkoutImportLedger
import com.bettermifitness.sync.data.preferences.WorkoutRestoreEntry
import com.bettermifitness.sync.data.preferences.WorkoutRestoreLedger
import com.bettermifitness.sync.health.HealthRecordOrigin
import com.bettermifitness.sync.health.InboundWorkout
import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.fds.FdsClient
import com.mifitness.miclient.fds.SportFileTransport
import com.mifitness.miclient.gps.NativeSportFileWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercise the production orchestration and real durable ledgers without any repair action. */
class MiWorkoutSyncRunnerTest {
    private val source = InboundWorkout(HealthRecordOrigin("run", "com.huami.watch.hmwatchmanager", null, 1, 1000),
        WorkoutSession(1_800_000_000, 1_800_000_120, "outdoor_running", tzIn15Min = 32,
            heartRateSeries = listOf(WorkoutTimedSample(1_800_000_010, 120.0)),
            route = listOf(WorkoutRoutePoint(1_800_000_010, 1.2, 103.8))), 120)
    private val target = MiWorkoutImportMapper.map(source)!!
    private val dense = (source.session.startTime until source.session.endTime).map { WorkoutTimedSample(it, 120.0) }
    private val store = MemoryStore()
    private val journal = WorkoutImportLedger(store)
    private val restoration = WorkoutRestoreLedger(store)
    private val cloud = Cloud()
    private val files = Files()
    private var raw: List<WorkoutTimedSample>? = dense
    private fun runner() = MiWorkoutSyncRunner(cloud, journal, { files }, { raw }, restoration)

    @Test fun ordinarySyncCompletesCurveAndRestoresADeletedOwnedSummaryWithoutReuploadingFiles() = runTest {
        assertTrue(runner().run("a", listOf(source)).complete)
        assertEquals(JsonPrimitive(2), cloud.rows.single().value["proto_type"])
        assertEquals(dense.size, NativeSportFileWriter.decodeHeartRate(files.stored.getValue("2:0")).size)
        val oldBodies = files.uploads
        val repeat = runner().run("a", listOf(source))
        assertEquals(0, repeat.fileBodyUploads)
        assertEquals(0, repeat.fileReferenceWrites)
        cloud.rows.clear()
        val restored = runner().run("a", listOf(source))
        assertEquals(1, restored.restored)
        assertTrue(restored.complete)
        assertEquals(oldBodies, files.uploads)
        assertTrue(target.sameIdentity(cloud.rows.single()))
        assertEquals(JsonPrimitive(1), cloud.rows.single().value["sport_type"])
    }

    @Test fun laterOriginalHeartRateAutomaticallyReplacesTheLegacySparseFileIdentity() = runTest {
        raw = null
        runner().run("a", listOf(source))
        val old = files.stored.getValue("1:0").copyOf()
        raw = dense
        assertTrue(runner().run("a", listOf(source)).complete)
        assertTrue(old.contentEquals(files.stored.getValue("1:0")))
        assertEquals(JsonPrimitive(2), cloud.rows.single().value["proto_type"])
        assertEquals(dense.size, NativeSportFileWriter.decodeHeartRate(files.stored.getValue("2:0")).size)
    }

    @Test fun backgroundSourceWithoutRouteKeepsTheExistingCloudRouteAndWritesNothing() = runTest {
        runner().run("a", listOf(source))
        val route = files.stored.getValue("2:2").copyOf()
        val withoutRoute = source.copy(session = source.session.copy(route = emptyList()), routeConsentRequired = true)
        val result = runner().run("a", listOf(withoutRoute))
        assertTrue(result.complete)
        assertEquals(0, result.fileBodyUploads)
        assertTrue(route.contentEquals(files.stored.getValue("2:2")))
    }

    @Test fun confirmedDeletionOfFilesAndSummaryIsReconciledThroughTheSamePipeline() = runTest {
        runner().run("a", listOf(source))
        cloud.rows.clear()
        files.stored.clear()
        val restored = runner().run("a", listOf(source))
        assertEquals(1, restored.restored)
        assertEquals(2, restored.fileBodyUploads)
        assertTrue(restored.complete)
        assertEquals(0, runner().run("a", listOf(source)).fileBodyUploads)
    }

    @Test fun summaryExtrasAndFileReferencesSurviveDeletionAndProcessObjectRecreation() = runTest {
        runner().run("a", listOf(source))
        cloud.rows[0] = cloud.rows[0].copy(value = JsonObject(cloud.rows[0].value + ("train_load" to JsonPrimitive(17))))
        runner().run("a", listOf(source))
        val before = cloud.rows.single().value
        cloud.rows.clear()
        val reopened = MiWorkoutSyncRunner(cloud, WorkoutImportLedger(store), { files }, { dense }, WorkoutRestoreLedger(store))
        assertTrue(reopened.run("a", listOf(source)).complete)
        assertEquals(before, cloud.rows.single().value)
    }

    @Test fun anUnknownRestoreWriteIsOnlyVerifiedOnFollowingRuns() = runTest {
        runner().run("a", listOf(source))
        cloud.rows.clear()
        cloud.persist = false
        val before = cloud.uploads
        assertEquals(1, runner().run("a", listOf(source)).pending)
        assertEquals(1, runner().run("a", listOf(source)).pending)
        assertEquals(before + 1, cloud.uploads)
    }

    @Test fun anUnknownFileRepairIsNotResetOrResentByAnotherNormalRun() = runTest {
        runner().run("a", listOf(source))
        files.stored.clear()
        files.persist = false
        val before = files.uploads
        assertEquals(1, runner().run("a", listOf(source)).failedFiles)
        assertEquals(1, runner().run("a", listOf(source)).failedFiles)
        assertEquals(before + 1, files.uploads)
    }

    @Test fun foreignSummariesAndOverlappingSourcesCannotBeOverwritten() = runTest {
        cloud.rows += parseMiWorkoutExperimentRow(target.row()).copy(sid = "foreign-watch")
        assertEquals(1, runner().run("a", listOf(source)).conflicts)
        assertEquals(0, cloud.uploads)
        assertEquals(0, files.uploads)
        cloud.rows.clear()
        runner().run("a", listOf(source))
        val before = files.uploads
        val duplicate = source.copy(origin = source.origin.copy(recordId = "other"))
        assertEquals(2, runner().run("a", listOf(source, duplicate)).conflicts)
        assertEquals(before, files.uploads)
    }

    @Test fun aChangedRawSourceCannotBypassAnUncertainWriteAtTheSameFileIdentity() = runTest {
        files.persist = false
        assertEquals(1, runner().run("a", listOf(source)).failedFiles)
        val before = files.uploads
        raw = dense.map { it.copy(value = 121.0) }
        assertEquals(1, runner().run("a", listOf(source)).failedFiles)
        assertEquals(before, files.uploads)
    }

    @Test fun legacyUncertainRestorationIsReconciledBeforeAutomaticRestorationCanWrite() = runTest {
        runner().run("a", listOf(source))
        val pending = WorkoutRestoreEntry(target, cloud.rows.single().value, emptyMap(), 2, true, summaryAttempted = true)
        assertTrue(restoration.replace("a", null, pending))
        cloud.rows.clear()
        val before = cloud.uploads
        assertEquals(1, runner().run("a", listOf(source)).pending)
        assertEquals(before, cloud.uploads)
        assertEquals(pending, restoration.load("a"))
    }

    @Test fun readOnlyBudgetNeverRunsFileMaintenanceOrCreatesACloudRecord() = runTest {
        assertEquals(1, runner().run("a", listOf(source), 0).pending)
        assertEquals(0, cloud.uploads)
        assertEquals(0, files.uploads)
        runner().run("a", listOf(source))
        files.stored.clear()
        val before = files.uploads
        runner().run("a", listOf(source), 0)
        assertEquals(before, files.uploads)
    }

    private class Cloud : MiWorkoutImportCloud {
        val rows = mutableListOf<MiWorkoutExperimentCloudRow>()
        var uploads = 0
        var persist = true
        override suspend fun read(from: Long, until: Long) = rows.filter { it.time in from until until }.toList()
        override suspend fun upload(target: MiWorkoutImportTarget, value: JsonObject) {
            uploads++
            if (persist) {
                rows.removeAll { target.sameIdentity(it) }
                rows += parseMiWorkoutExperimentRow(target.row(value))
            }
        }
    }

    private class Files : SportFileTransport {
        val stored = mutableMapOf<String, ByteArray>()
        var uploads = 0
        var persist = true
        private fun key(request: FdsClient.SportFileRequest) = "${request.protoType}:${request.fileType}"
        override suspend fun downloadSportFile(request: FdsClient.SportFileRequest) = stored[key(request)]
            ?: throw MiApiException.Server(404, "synthetic missing file")
        override suspend fun prepareSportFile(request: FdsClient.SportFileRequest) = FdsClient.UploadMeta(key(request), "PUT", "key")
        override suspend fun uploadPreparedSportFile(meta: FdsClient.UploadMeta, bytes: ByteArray) {
            uploads++
            if (persist) stored[meta.url] = bytes.copyOf()
        }
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }
}
