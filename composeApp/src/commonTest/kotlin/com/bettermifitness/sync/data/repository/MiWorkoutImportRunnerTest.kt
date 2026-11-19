package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import com.bettermifitness.sync.health.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class MiWorkoutImportRunnerTest {
    private val cloud = Cloud()
    private val journal = Journal()
    private val runner = MiWorkoutImportRunner(cloud, journal)
    private fun source(index: Int = 0, type: String = "indoor_running") = InboundWorkout(
        HealthRecordOrigin("source-$index", "com.huami.watch.hmwatchmanager", null, 1, 1000),
        WorkoutSession(1_800_000_000 + index * 7200L, 1_800_001_800 + index * 7200L, type,
            distanceMeters = 3000.4, caloriesKcal = 200.4, tzIn15Min = 32,
            heartRateSeries = listOf(WorkoutTimedSample(1_800_000_010 + index * 7200L, 120.0))), 1800)

    @Test
    fun batchUploadsEveryEligibleRecordAndRepeatWritesNone() = runTest {
        val rows = (0..3).map { source(it) }
        assertEquals(4, runner.run("a", rows).uploaded)
        assertEquals(4, runner.run("a", rows).existing)
        assertEquals(4, cloud.uploads)
    }

    @Test
    fun writeBudgetIsRespectedAndZeroBudgetIsReadOnlyForMissingRows() = runTest {
        val rows = (0..3).map { source(it) }
        assertEquals(4, runner.run("a", rows, 0).pending)
        assertTrue(journal.rows.isEmpty())
        val result = runner.run("a", rows, 1)
        assertEquals(1, result.uploaded)
        assertEquals(3, result.pending)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun sourceContentRevisionUsesSameIdentityAndPreservesNativeFields() = runTest {
        runner.run("a", listOf(source()))
        cloud.rows[0] = cloud.rows[0].copy(value = JsonObject(cloud.rows[0].value + ("server_extra" to JsonPrimitive(7))))
        val changed = source().copy(session = source().session.copy(caloriesKcal = 190.0))
        assertEquals(1, runner.run("a", listOf(changed)).revised)
        assertEquals(1, cloud.rows.size)
        assertEquals(JsonPrimitive(7), cloud.rows.single().value["server_extra"])
    }

    @Test
    fun changedIdentityNeverLeavesASecondCopy() = runTest {
        runner.run("a", listOf(source()))
        val changed = source().copy(session = source().session.copy(startTime = source().session.startTime + 1),
            activeDurationSeconds = 1799)
        assertEquals(1, runner.run("a", listOf(changed)).conflicts)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun transportFailureAfterPersistenceIsVerified() = runTest {
        cloud.uploadError = IllegalStateException()
        assertEquals(1, runner.run("a", listOf(source())).uploaded)
        assertTrue(journal.rows.values.single().verified)
    }

    @Test
    fun unconfirmedWriteIsNotRetried() = runTest {
        cloud.persist = false
        assertEquals(1, runner.run("a", listOf(source())).pending)
        assertEquals(1, runner.run("a", listOf(source())).pending)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun pendingWithDifferentSourceContentStaysLocked() = runTest {
        cloud.persist = false
        runner.run("a", listOf(source()))
        val changed = source().copy(session = source().session.copy(caloriesKcal = 190.0))
        assertEquals(1, runner.run("a", listOf(changed)).pending)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun cancellationLeavesReservationAndNextRunReconciles() = runTest {
        cloud.uploadError = CancellationException()
        assertFailsWith<CancellationException> { runner.run("a", listOf(source())) }
        assertFalse(journal.rows.values.single().verified)
        cloud.uploadError = null
        assertEquals(1, runner.run("a", listOf(source())).existing)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun existingWatchAndDuplicateCloudRowsAreConflicts() = runTest {
        val target = MiWorkoutImportMapper.map(source())!!
        cloud.rows += parseMiWorkoutExperimentRow(target.row()).copy(sid = "watch")
        assertEquals(1, runner.run("a", listOf(source())).conflicts)
        assertEquals(0, cloud.uploads)
        cloud.rows.clear()
        cloud.rows += listOf(parseMiWorkoutExperimentRow(target.row()), parseMiWorkoutExperimentRow(target.row()))
        assertEquals(1, runner.run("a", listOf(source())).conflicts)
    }

    @Test
    fun unknownSportAndSourceOverlapAreReported() = runTest {
        assertEquals(1, runner.run("a", listOf(source(type = "unmapped"))).unsupported)
        val result = runner.run("a", listOf(source(), source().copy(origin = source().origin.copy(recordId = "other"))))
        assertEquals(2, result.conflicts)
        assertEquals(0, cloud.uploads)
    }

    @Test
    fun missingVerifiedHistoryIsRestoredOnceWhenTheZeppSourceStillMatches() = runTest {
        runner.run("a", listOf(source()))
        cloud.rows.clear()
        assertEquals(1, runner.run("a", listOf(source())).restored)
        assertEquals(1, runner.run("a", listOf(source())).existing)
        assertEquals(2, cloud.uploads)
    }

    @Test
    fun mapperUsesRealActiveDurationAndSampledHeartRate() {
        val row = MiWorkoutImportMapper.map(source().copy(activeDurationSeconds = 1700))!!
        assertEquals(JsonPrimitive(1700L), row.value["valid_duration"])
        assertEquals(JsonPrimitive(120), row.value["avg_hrm"])
        assertEquals(JsonPrimitive(3000), row.value["distance"])
        assertFalse("vitality" in row.value || "train_effect" in row.value)
        assertNull(MiWorkoutImportMapper.map(source().copy(session = source().session.copy(tzIn15Min = null))))
    }

    private class Journal : WorkoutImportJournal {
        val rows = mutableMapOf<Pair<String, String>, WorkoutImportEntry>()
        override suspend fun load(account: String, sourceKey: String) = rows[account to sourceKey]
        override suspend fun reserve(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry?): Boolean {
            if (rows[account to target.sourceKey] != previous) return false
            rows[account to target.sourceKey] = WorkoutImportEntry(target)
            return true
        }
        override suspend fun verified(account: String, target: MiWorkoutImportTarget) {
            check(rows[account to target.sourceKey]?.target == target)
            rows[account to target.sourceKey] = WorkoutImportEntry(target, true)
        }
    }

    private class Cloud : MiWorkoutImportCloud {
        val rows = mutableListOf<MiWorkoutExperimentCloudRow>()
        var persist = true
        var uploads = 0
        var uploadError: Exception? = null
        override suspend fun read(from: Long, until: Long) = rows.toList()
        override suspend fun upload(target: MiWorkoutImportTarget, value: JsonObject) {
            uploads++
            if (persist) {
                rows.removeAll { target.sameIdentity(it) }
                rows += parseMiWorkoutExperimentRow(target.row(value))
            }
            uploadError?.let { throw it }
        }
    }
}
