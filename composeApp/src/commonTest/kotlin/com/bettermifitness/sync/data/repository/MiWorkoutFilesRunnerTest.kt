package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import com.bettermifitness.sync.health.*
import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.fds.FdsClient
import com.mifitness.miclient.fds.SportFileTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiWorkoutFilesRunnerTest {
    private val source = InboundWorkout(HealthRecordOrigin("source", "com.huami.watch.hmwatchmanager", null, 1, 1000),
        WorkoutSession(1_800_000_000, 1_800_001_800, "outdoor_running", tzIn15Min = 32,
            heartRateSeries = listOf(WorkoutTimedSample(1_800_000_010, 120.0)),
            route = listOf(WorkoutRoutePoint(1_800_000_010, 1.2, 103.8))), 1800)
    private val target = MiWorkoutImportMapper.map(source)!!
    private val journal = Journal()
    private val cloud = Cloud(target)
    private val files = Files()
    private fun runner() = MiWorkoutFilesRunner(cloud, journal, files)
    private fun seed() { journal.rows[target.sourceKey] = WorkoutImportEntry(target, true) }

    @Test
    fun explicitCurveRefreshUsesAnotherCompatibleFileIdentityWithoutChangingExerciseOrOriginalObjects() = runTest {
        seed()
        runner().run("account", listOf(source))
        val old = files.stored.mapValues { it.value.copyOf() }
        val extra = JsonPrimitive(31)
        cloud.row = cloud.row.copy(value = JsonObject(cloud.row.value + ("train_load" to extra)))
        val dense = (source.session.startTime until source.session.endTime).map { WorkoutTimedSample(it, 130.0) }
        val refresh = MiWorkoutFilesRunner(cloud, journal, files, originalHeartRate = { dense })
            .run("account", listOf(source), refreshOutdoorCurve = true)
        assertEquals(2, refresh.verified)
        assertEquals(2, refresh.bodyUploads)
        assertEquals(1, refresh.referenceWrites)
        assertTrue(old.all { (key, bytes) -> bytes.contentEquals(files.stored.getValue(key)) })
        assertEquals(2, files.stored.getValue("2:0")[6].toInt())
        assertEquals(dense.size, com.mifitness.miclient.gps.NativeSportFileWriter
            .decodeHeartRate(files.stored.getValue("2:0")).size)
        assertEquals(JsonPrimitive(1), cloud.row.value["sport_type"])
        assertEquals(JsonPrimitive(2), cloud.row.value["proto_type"])
        assertEquals(extra, cloud.row.value["train_load"])
        assertTrue(target.sameIdentity(cloud.row))
        val repeat = MiWorkoutFilesRunner(cloud, journal, files, originalHeartRate = { dense })
            .run("account", listOf(source))
        assertEquals(2, repeat.verified)
        assertEquals(0, repeat.bodyUploads)
        assertEquals(0, repeat.referenceWrites)
        assertEquals(JsonPrimitive(2), cloud.row.value["proto_type"])
    }

    @Test
    fun curveRefreshCannotOverwriteUnknownExistingAlternateFile() = runTest {
        seed()
        files.stored["2:0"] = byteArrayOf(1, 2, 3)
        val result = MiWorkoutFilesRunner(cloud, journal, files, originalHeartRate = { source.session.heartRateSeries })
            .run("account", listOf(source), refreshOutdoorCurve = true)
        assertEquals(1, result.pending)
        assertEquals(0, files.uploads)
        assertEquals(0, cloud.uploads)
        assertEquals(listOf<Byte>(1, 2, 3), files.stored.getValue("2:0").toList())
    }

    @Test
    fun curveRefreshRequiresExplicitRawSourceAndNeverInventsDenseObservations() = runTest {
        seed()
        assertEquals(1, runner().run("account", listOf(source), refreshOutdoorCurve = true).pending)
        assertEquals(0, files.uploads)
        assertEquals(0, cloud.uploads)
    }

    @Test
    fun uncertainAlternateFileAttemptRemainsPendingAcrossRefreshesWithoutReplay() = runTest {
        seed()
        files.persist = false
        val first = MiWorkoutFilesRunner(cloud, journal, files, originalHeartRate = { source.session.heartRateSeries })
            .run("account", listOf(source), refreshOutdoorCurve = true)
        assertEquals(1, first.pending)
        assertEquals(1, files.uploads)
        val next = MiWorkoutFilesRunner(cloud, journal, files, originalHeartRate = { source.session.heartRateSeries })
            .run("account", listOf(source), refreshOutdoorCurve = true)
        assertEquals(1, next.pending)
        assertEquals(1, files.uploads)
        assertEquals(0, cloud.uploads)
    }

    @Test
    fun compatibleProtocolAcceptanceDoesNotRelaxExerciseSourceOrTimeChecks() {
        val compatible = parseMiWorkoutExperimentRow(target.row(JsonObject(target.value + mapOf(
            "did" to JsonPrimitive(target.sid), "proto_type" to JsonPrimitive(2), "version" to JsonPrimitive(1)))))
        assertTrue(target.matches(compatible))
        assertFalse(target.matches(compatible.copy(sid = "foreign")))
        assertFalse(target.matches(compatible.copy(time = compatible.time + 1)))
        assertFalse(target.matches(compatible.copy(value = JsonObject(compatible.value + ("sport_type" to JsonPrimitive(2))))))
        assertFalse(target.matches(compatible.copy(value = JsonObject(compatible.value + ("did" to JsonPrimitive("foreign"))))))
    }

    @Test
    fun uploadsBothFilesPromotesThenFreshRunnerWritesNothing() = runTest {
        seed()
        assertEquals(2, runner().run("account", listOf(source)).verified)
        assertEquals(2, files.uploads)
        assertEquals(1, cloud.uploads)
        assertEquals(JsonPrimitive(1), cloud.row.value["proto_type"])
        assertEquals(JsonPrimitive(1), cloud.row.value["version"])
        assertEquals(2, runner().run("account", listOf(source)).verified)
        assertEquals(2, files.uploads)
        assertEquals(1, cloud.uploads)
        assertTrue(journal.rows.values.all { it.verified })
    }

    @Test
    fun originalZeppHeartRateUsesTheSameOwnedSummaryAndRepeatsWithoutWrites() = runTest {
        seed()
        val original=(source.session.startTime until source.session.endTime).map {WorkoutTimedSample(it,130.0)}
        val enriched=MiWorkoutFilesRunner(cloud,journal,files,originalHeartRate={original})
        assertEquals(2,enriched.run("account",listOf(source)).verified)
        assertEquals(original.size,com.mifitness.miclient.gps.NativeSportFileWriter.decodeHeartRate(files.stored.getValue("2:0")).size)
        val repeated=MiWorkoutFilesRunner(cloud,journal,files,originalHeartRate={original}).run("account",listOf(source))
        assertEquals(0,repeated.bodyUploads)
        assertEquals(0,repeated.referenceWrites)
        assertEquals(target.identity,cloud.row.let {"WORKOUT|${it.sid}|${it.key}|${it.time}"})
    }

    @Test
    fun lateAltitudeDoesNotSwitchRunningProtocolOrUploadTheSameCurveAgain() = runTest {
        seed()
        assertEquals(2, runner().run("account", listOf(source)).verified)
        val later = source.copy(session = source.session.copy(route = source.session.route.map { it.copy(altitudeMeters = 42.0) }))
        assertEquals(2, runner().run("account", listOf(later)).verified)
        assertEquals(2, files.uploads)
        assertEquals(1, cloud.uploads)
        assertEquals(JsonPrimitive(1), cloud.row.value["proto_type"])
    }

    @Test
    fun outdoorSourceAltitudeUsesNativeRunningFilesAndKeepsTheSameSummaryIdentity() = runTest {
        val actual = source.copy(session = source.session.copy(route = source.session.route.map { it.copy(altitudeMeters = 42.0) }))
        seed()
        val result = runner().run("account", listOf(actual))
        assertEquals(2, result.verified)
        assertEquals(2, result.bodyUploads)
        assertEquals(1, result.referenceWrites)
        assertEquals(JsonPrimitive(1), cloud.row.value["proto_type"])
        assertEquals(1, files.stored.getValue("0")[6].toInt())
        val repeated = runner().run("account", listOf(actual))
        assertEquals(0, repeated.bodyUploads)
        assertEquals(0, repeated.referenceWrites)
    }

    @Test
    fun correctedFileIdentityKeepsOldDefaultDeviceReservationUntouched() = runTest {
        seed()
        val old = WorkoutImportEntry(target.copy(sourceKey = "FILES:${target.sourceKey}"))
        journal.rows[old.target.sourceKey] = old
        assertEquals(2, runner().run("account", listOf(source)).verified)
        assertEquals(old, journal.rows[old.target.sourceKey])
        assertEquals(JsonPrimitive("xiaomiwear_app_manually"), cloud.row.value["did"])
        assertEquals(2, files.uploads)
    }

    @Test
    fun sourceRevisionWithoutContentChangeDoesNotUploadFileBodiesAgain() = runTest {
        seed()
        runner().run("account", listOf(source))
        val revised = source.copy(origin = source.origin.copy(lastModifiedEpochMillis = 2000))
        val nextTarget = MiWorkoutImportMapper.map(revised)!!
        journal.rows[target.sourceKey] = WorkoutImportEntry(nextTarget, true)
        assertEquals(2, runner().run("account", listOf(revised)).verified)
        assertEquals(2, files.uploads)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun addressRejectionLeavesNewStateAndCanSafelyResume() = runTest {
        seed()
        files.prepareError = MiApiException.Server(-6, "synthetic rejection")
        val rejected = runner().run("account", listOf(source))
        assertEquals(listOf("FILE_PREPARE:SERVER_-6:BODY_UNKNOWN"), rejected.failures)
        assertEquals(0, files.uploads)
        assertTrue(journal.rows.values.last().fileStates.values.all { it == WorkoutFileStage.NEW })
        files.prepareError = null
        assertEquals(2, runner().run("account", listOf(source)).verified)
        assertEquals(2, files.uploads)
    }

    @Test
    fun bodyAcknowledgmentLostStillVerifiesByIndependentRead() = runTest {
        seed()
        files.uploadError = IllegalStateException("lost ack")
        assertEquals(2, runner().run("account", listOf(source)).verified)
        assertEquals(2, files.uploads)
    }

    @Test
    fun uncertainMissingBodyIsNeverUploadedAgain() = runTest {
        seed()
        files.persist = false
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertEquals(1, files.uploads)
        assertEquals(0, cloud.uploads)
    }

    @Test
    fun explicitMissingFileRepairHasOneDurableAttemptAndNormalRunsNeverUseIt() = runTest {
        seed()
        files.persist = false
        files.uploadError = MiApiException.Server(403, "synthetic rejection")
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertEquals(1, files.uploads)
        files.persist = true
        files.uploadError = null
        assertEquals(2, runner().run("account", listOf(source), repairMissingFiles = true).verified)
        assertEquals(3, files.uploads)
        assertEquals(2, runner().run("account", listOf(source), repairMissingFiles = true).verified)
        assertEquals(3, files.uploads)
    }

    @Test
    fun rejectedRepairCannotBeRepeatedEvenByAnotherRepairAction() = runTest {
        seed()
        files.persist = false
        files.uploadError = MiApiException.Server(403, "synthetic rejection")
        runner().run("account", listOf(source))
        assertEquals(1, runner().run("account", listOf(source), repairMissingFiles = true).pending)
        assertEquals(1, runner().run("account", listOf(source), repairMissingFiles = true).pending)
        assertEquals(2, files.uploads)
    }

    @Test
    fun cancellationAfterFirstFileIsReadBackAndOnlyUnattemptedSecondFileIsSent() = runTest {
        seed()
        files.uploadError = CancellationException()
        assertFailsWith<CancellationException> { runner().run("account", listOf(source)) }
        assertEquals(1, files.uploads)
        files.uploadError = null
        assertEquals(2, runner().run("account", listOf(source)).verified)
        assertEquals(2, files.uploads)
    }

    @Test
    fun cancellationDuringPromotionDoesNotReplaySummary() = runTest {
        seed()
        cloud.error = CancellationException()
        assertFailsWith<CancellationException> { runner().run("account", listOf(source)) }
        cloud.error = null
        assertEquals(2, runner().run("account", listOf(source)).verified)
        assertEquals(1, cloud.uploads)
        assertEquals(2, files.uploads)
    }

    @Test
    fun cancellationBeforePromotionReservationAllowsPromotionOnNextRun() = runTest {
        seed()
        cloud.failReadAt = 2 // initial read, after file I/O, final read
        assertFailsWith<CancellationException> { runner().run("account", listOf(source)) }
        cloud.failReadAt = null
        assertEquals(2, runner().run("account", listOf(source)).verified)
        assertEquals(1, cloud.uploads)
        assertEquals(2, files.uploads)
    }

    @Test
    fun rejectedPromotionRemainsPendingWithoutBodyOrSummaryReplay() = runTest {
        seed()
        cloud.persist = false
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertEquals(1, cloud.uploads)
        assertEquals(2, files.uploads)
    }

    @Test
    fun legacyPendingEntriesAreReadOnlyAndCanOnlyBeAdoptedFromMatchingBytes() = runTest {
        seed()
        cloud.failReadAt = 2
        assertFailsWith<CancellationException> { runner().run("account", listOf(source)) }
        val key = journal.rows.keys.single { it.startsWith("FILES:") }
        val existing = journal.rows.getValue(key)
        journal.rows[key] = WorkoutImportEntry(existing.target)
        cloud.failReadAt = null
        assertEquals(2, runner().run("account", listOf(source)).verified)
        assertEquals(2, files.uploads)
    }

    @Test
    fun changedSourceCannotOverwriteAnUncertainFileAttempt() = runTest {
        seed()
        files.persist = false
        runner().run("account", listOf(source))
        val changed = source.copy(session = source.session.copy(heartRateSeries = listOf(WorkoutTimedSample(1_800_000_010, 125.0))))
        val revised = MiWorkoutImportMapper.map(changed)!!
        journal.rows[target.sourceKey] = WorkoutImportEntry(revised, true)
        cloud.row = parseMiWorkoutExperimentRow(revised.row())
        assertEquals(1, runner().run("account", listOf(changed)).pending)
        assertEquals(1, files.uploads)
    }

    @Test
    fun missingOrChangedSummaryNeverCreatesFiles() = runTest {
        assertEquals(0, runner().run("account", listOf(source)).verified)
        seed()
        cloud.row = cloud.row.copy(sid = "foreign-watch")
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertEquals(0, files.uploads)
        assertFalse(journal.rows.keys.any { it.startsWith("FILES:") })
    }

    private class Journal : WorkoutImportJournal {
        val rows = linkedMapOf<String, WorkoutImportEntry>()
        override suspend fun load(account: String, sourceKey: String) = rows[sourceKey]
        override suspend fun reserve(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry?) = error("unused")
        override suspend fun reserveFiles(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry?,
            states: Map<String, WorkoutFileStage>): Boolean {
            if (rows[target.sourceKey] != previous) return false
            rows[target.sourceKey] = WorkoutImportEntry(target, fileProtocolVersion = 2, fileStates = states)
            return true
        }
        override suspend fun updateFiles(account: String, expected: WorkoutImportEntry, updated: WorkoutImportEntry): Boolean {
            if (rows[expected.target.sourceKey] != expected) return false
            rows[expected.target.sourceKey] = updated
            return true
        }
        override suspend fun verified(account: String, target: MiWorkoutImportTarget) {
            val entry = rows.getValue(target.sourceKey)
            check(entry.target == target && entry.promotionAttempted && entry.fileStates.values.all { it == WorkoutFileStage.VERIFIED })
            rows[target.sourceKey] = entry.copy(verified = true)
        }
    }

    private class Cloud(target: MiWorkoutImportTarget) : MiWorkoutImportCloud {
        var row = parseMiWorkoutExperimentRow(target.row())
        var uploads = 0
        var reads = 0
        var persist = true
        var error: Exception? = null
        var failReadAt: Int? = null
        override suspend fun read(from: Long, until: Long): List<MiWorkoutExperimentCloudRow> {
            reads++
            if (reads == failReadAt) throw CancellationException()
            return listOf(row)
        }
        override suspend fun upload(target: MiWorkoutImportTarget, value: JsonObject) {
            uploads++
            if (persist) row = parseMiWorkoutExperimentRow(target.row(value))
            error?.let { throw it }
        }
    }

    private class Files : SportFileTransport {
        val stored = mutableMapOf<String, ByteArray>()
        var prepareError: Exception? = null
        var uploadError: Exception? = null
        var uploads = 0
        var persist = true
        override suspend fun prepareSportFile(request: FdsClient.SportFileRequest): FdsClient.UploadMeta {
            prepareError?.let { throw it }
            assertEquals("xiaomiwear_app_manually", request.sid)
            return FdsClient.UploadMeta(fileKey(request), "PUT", "synthetic-key")
        }
        override suspend fun uploadPreparedSportFile(meta: FdsClient.UploadMeta, bytes: ByteArray) {
            uploads++
            if (persist) stored[meta.url] = bytes.copyOf()
            uploadError?.let { throw it }
        }
        override suspend fun downloadSportFile(request: FdsClient.SportFileRequest) =
            stored[fileKey(request)] ?: throw MiApiException.Server(404, "synthetic missing file")
        private fun fileKey(request: FdsClient.SportFileRequest) =
            if (request.protoType == 2) "2:${request.fileType}" else request.fileType.toString()
    }
}
