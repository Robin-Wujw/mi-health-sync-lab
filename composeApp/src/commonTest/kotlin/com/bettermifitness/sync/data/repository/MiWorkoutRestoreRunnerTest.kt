package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiWorkoutExperimentCloudRow
import com.bettermifitness.sync.data.api.MiWorkoutImportCloud
import com.bettermifitness.sync.data.api.MiWorkoutImportMapper
import com.bettermifitness.sync.data.api.MiWorkoutImportTarget
import com.bettermifitness.sync.data.api.WorkoutSession
import com.bettermifitness.sync.data.api.WorkoutTimedSample
import com.bettermifitness.sync.data.api.parseMiWorkoutExperimentRow
import com.bettermifitness.sync.data.preferences.WorkoutImportEntry
import com.bettermifitness.sync.data.preferences.WorkoutImportJournal
import com.bettermifitness.sync.data.preferences.WorkoutRestoreEntry
import com.bettermifitness.sync.data.preferences.WorkoutRestoreJournal
import com.bettermifitness.sync.health.HealthRecordOrigin
import com.bettermifitness.sync.health.InboundWorkout
import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.fds.FdsClient
import com.mifitness.miclient.fds.SportFileTransport
import com.mifitness.miclient.gps.NativeSportFileWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MiWorkoutRestoreRunnerTest {
    private val source = InboundWorkout(HealthRecordOrigin("source", "com.huami.watch.hmwatchmanager", null, 1, 1000),
        WorkoutSession(1_800_000_000, 1_800_000_020, "outdoor_running", tzIn15Min = 32,
            heartRateSeries = listOf(WorkoutTimedSample(1_800_000_010, 120.0))), 20)
    private val target = MiWorkoutImportMapper.map(source)!!
    private val ownership = Owner()
    private val journal = Journal()
    private val files = Files()
    private val cloud = Cloud()
    private fun runner(settle: suspend () -> Unit = {}) = MiWorkoutRestoreRunner(cloud, ownership, journal, files, settle)
    private val summary = JsonObject(target.value + mapOf("version" to JsonPrimitive(1),
        "did" to JsonPrimitive(target.sid), "proto_type" to JsonPrimitive(1), "training_load" to JsonPrimitive(32)))

    private fun seed() {
        cloud.rows = listOf(parseMiWorkoutExperimentRow(target.row(summary)))
        files.stored["0"] = NativeSportFileWriter.heartRate(target.start, 32, 1,
            listOf(NativeSportFileWriter.HeartPoint(target.start + 10, 120)), sessionEndTime = target.end)
    }

    @Test fun backupDeleteRestorePreservesExtraFieldsFilesAndIdentityAndRepeatDoesNotWrite() = runTest {
        seed()
        val backup = runner().prepare("a", source, listOf(source))
        assertTrue(backup.fullCloudBackup)
        assertEquals(summary, backup.value)
        assertEquals(0, cloud.uploads)
        cloud.rows = emptyList() // Explicit deletion in Mi Fitness, outside this synchronizer.
        assertEquals(WorkoutRestoreStatus.VERIFIED, runner().restore("a", listOf(source)))
        assertEquals(summary, cloud.rows.single().value)
        assertTrue(target.sameIdentity(cloud.rows.single()))
        assertEquals(0, files.uploads)
        assertEquals(1, cloud.uploads)
        assertEquals(WorkoutRestoreStatus.VERIFIED, runner().restore("a", listOf(source)))
        assertEquals(1, cloud.uploads)
        assertTrue(journal.entry!!.verified)
        assertTrue(ownership.entry.verified)
    }

    @Test fun alreadyDeletedWorkoutCanBeRebuiltFromOriginalSourceWithDenseFileOnly() = runTest {
        val dense = source.copy(session = source.session.copy(heartRateSeries =
            (target.start until target.end).map { WorkoutTimedSample(it, 130.0) }))
        val prepared = runner().prepare("a", source, listOf(source), dense)
        assertFalse(prepared.fullCloudBackup)
        assertEquals(WorkoutRestoreStatus.VERIFIED, runner().restore("a", listOf(source)))
        assertEquals(1, files.uploads)
        assertEquals(20, NativeSportFileWriter.decodeHeartRate(files.stored.getValue("0")).size)
        assertEquals(target.value["avg_hrm"], cloud.rows.single().value["avg_hrm"])
    }

    @Test fun presentRecordIsNeverDeletedOrDuplicated() = runTest {
        seed()
        runner().prepare("a", source, listOf(source))
        assertEquals(WorkoutRestoreStatus.NOT_DELETED, runner().restore("a", listOf(source)))
        assertEquals(0, cloud.uploads)
        assertEquals(0, files.uploads)
    }

    @Test fun unknownSummaryWritePersistsAndCannotBeReplayedByNewRunner() = runTest {
        runner().prepare("a", source, listOf(source))
        cloud.persist = false
        cloud.error = IllegalStateException("synthetic response loss")
        assertEquals(WorkoutRestoreStatus.PENDING, runner().restore("a", listOf(source)))
        assertTrue(journal.entry!!.summaryAttempted)
        assertEquals(WorkoutRestoreStatus.PENDING, runner().restore("a", listOf(source)))
        assertEquals(1, cloud.uploads)
    }

    @Test fun responseLostAfterPersistenceIsVerifiedAndNotSentTwice() = runTest {
        runner().prepare("a", source, listOf(source))
        cloud.error = IllegalStateException("synthetic response loss")
        assertEquals(WorkoutRestoreStatus.VERIFIED, runner().restore("a", listOf(source)))
        assertEquals(WorkoutRestoreStatus.VERIFIED, runner().restore("a", listOf(source)))
        assertEquals(1, cloud.uploads)
    }

    @Test fun cancellationAfterPersistenceLeavesAnAttemptThatNextRunOnlyVerifies() = runTest {
        runner().prepare("a", source, listOf(source))
        cloud.error = CancellationException()
        assertFailsWith<CancellationException> { runner().restore("a", listOf(source)) }
        assertTrue(journal.entry!!.summaryAttempted)
        cloud.error = null
        assertEquals(WorkoutRestoreStatus.VERIFIED, runner().restore("a", listOf(source)))
        assertEquals(1, cloud.uploads)
    }

    @Test fun deletedFileIsRestoredFromTheExactBackupBytesOnce() = runTest {
        seed()
        val bytes = files.stored.getValue("0").copyOf()
        runner().prepare("a", source, listOf(source))
        cloud.rows = emptyList()
        files.stored.clear()
        assertEquals(WorkoutRestoreStatus.VERIFIED, runner().restore("a", listOf(source)))
        assertTrue(bytes.contentEquals(files.stored.getValue("0")))
        assertEquals(1, files.uploads)
    }

    @Test fun uncertainFileAttemptIsNeverResetToRetryAndSummaryIsNotSent() = runTest {
        runner().prepare("a", source, listOf(source))
        files.persist = false
        assertEquals(WorkoutRestoreStatus.PENDING, runner().restore("a", listOf(source)))
        assertEquals(WorkoutRestoreStatus.PENDING, runner().restore("a", listOf(source)))
        assertEquals(setOf("0"), journal.entry!!.fileAttempts)
        assertEquals(1, files.uploads)
        assertEquals(0, cloud.uploads)
    }

    @Test fun foreignOrChangedOverlappingRecordBlocksRestoreWithoutUploads() = runTest {
        runner().prepare("a", source, listOf(source))
        cloud.rows = listOf(parseMiWorkoutExperimentRow(target.row()).copy(sid = "other-watch"))
        assertEquals(WorkoutRestoreStatus.CONFLICT, runner().restore("a", listOf(source)))
        assertEquals(0, cloud.uploads)
        assertEquals(0, files.uploads)
    }

    @Test fun lateCloudObservationBetweenAbsenceReadsBlocksAllWrites() = runTest {
        runner().prepare("a", source, listOf(source))
        val late = { cloud.rows = listOf(parseMiWorkoutExperimentRow(target.row())); Unit }
        assertEquals(WorkoutRestoreStatus.NOT_DELETED, runner(late).restore("a", listOf(source)))
        assertEquals(0, files.uploads)
        assertEquals(0, cloud.uploads)
    }

    @Test fun sourceChangesOrDisappearsDoNotAuthorizeBackupUpload() = runTest {
        runner().prepare("a", source, listOf(source))
        val changed = source.copy(origin = source.origin.copy(lastModifiedEpochMillis = 2000))
        assertEquals(WorkoutRestoreStatus.SOURCE_CHANGED, runner().restore("a", listOf(changed)))
        assertEquals(WorkoutRestoreStatus.SOURCE_CHANGED, runner().restore("a", emptyList()))
        assertEquals(0, files.uploads)
        assertEquals(0, cloud.uploads)
    }

    @Test fun unownedOrAmbiguousSourceCannotPrepareRecovery() = runTest {
        ownership.entry = ownership.entry.copy(verified = false)
        assertFailsWith<IllegalStateException> { runner().prepare("a", source, listOf(source)) }
        ownership.entry = ownership.entry.copy(verified = true)
        assertFailsWith<IllegalStateException> { runner().prepare("a", source, listOf(source, source)) }
        assertEquals(0, cloud.uploads)
    }

    @Test fun verifiedRecoveryDoesNotAuthorizeAnotherResurrectionWithoutNewUserPreparation() = runTest {
        runner().prepare("a", source, listOf(source))
        runner().restore("a", listOf(source))
        cloud.rows = emptyList()
        assertEquals(WorkoutRestoreStatus.CONFLICT, runner().restore("a", listOf(source)))
        assertEquals(1, cloud.uploads)
    }

    @Test fun otherAccountHasNoOwnershipForLegacyRecovery() = runTest {
        assertFailsWith<IllegalStateException> { runner().prepare("b", source, listOf(source)) }
        assertEquals(0, cloud.uploads)
    }

    @Test fun fileDisappearingAfterSummaryWriteCannotBeReportedAsCompleteOrReuploaded() = runTest {
        runner().prepare("a", source, listOf(source))
        cloud.onUpload = { files.stored.clear() }
        assertEquals(WorkoutRestoreStatus.PENDING, runner().restore("a", listOf(source)))
        assertFalse(journal.entry!!.verified)
        assertEquals(WorkoutRestoreStatus.PENDING, runner().restore("a", listOf(source)))
        assertEquals(1, files.uploads)
        assertEquals(1, cloud.uploads)
    }

    @Test fun changedExistingFileIsNotOverwrittenToForceRecovery() = runTest {
        seed()
        runner().prepare("a", source, listOf(source))
        cloud.rows = emptyList()
        files.stored["0"] = byteArrayOf(1, 2, 3)
        assertFailsWith<IllegalStateException> { runner().restore("a", listOf(source)) }
        assertEquals(0, cloud.uploads)
        assertEquals(0, files.uploads)
    }

    private inner class Owner : WorkoutImportJournal {
        var entry = WorkoutImportEntry(target, verified = true)
        override suspend fun load(account: String, sourceKey: String) = entry.takeIf { account == "a" && sourceKey == target.sourceKey }
        override suspend fun reserve(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry?) = error("unused")
        override suspend fun verified(account: String, target: MiWorkoutImportTarget) { error("Recovery must not reset original ledger") }
    }

    private class Journal : WorkoutRestoreJournal {
        var entry: WorkoutRestoreEntry? = null
        override suspend fun load(account: String) = entry
        override suspend fun replace(account: String, expected: WorkoutRestoreEntry?, updated: WorkoutRestoreEntry?): Boolean {
            if (entry != expected) return false
            entry = updated
            return true
        }
    }

    private class Cloud : MiWorkoutImportCloud {
        var rows = emptyList<MiWorkoutExperimentCloudRow>()
        var uploads = 0
        var persist = true
        var error: Exception? = null
        var onUpload: () -> Unit = {}
        override suspend fun read(from: Long, until: Long) = rows
        override suspend fun upload(target: MiWorkoutImportTarget, value: JsonObject) {
            uploads++
            if (persist) rows = listOf(parseMiWorkoutExperimentRow(target.row(value)))
            onUpload()
            error?.let { throw it }
        }
    }

    private class Files : SportFileTransport {
        val stored = mutableMapOf<String, ByteArray>()
        var uploads = 0
        var persist = true
        override suspend fun downloadSportFile(request: FdsClient.SportFileRequest): ByteArray = stored[request.fileType.toString()]
            ?: throw MiApiException.Server(404, "synthetic absent file")
        override suspend fun prepareSportFile(request: FdsClient.SportFileRequest) =
            FdsClient.UploadMeta(request.fileType.toString(), "PUT", "synthetic")
        override suspend fun uploadPreparedSportFile(meta: FdsClient.UploadMeta, bytes: ByteArray) {
            uploads++
            if (persist) stored[meta.url] = bytes.copyOf()
        }
    }
}
