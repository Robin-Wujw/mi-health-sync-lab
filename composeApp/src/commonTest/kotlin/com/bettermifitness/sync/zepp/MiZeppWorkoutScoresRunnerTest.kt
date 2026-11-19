package com.bettermifitness.sync.zepp

import com.bettermifitness.sync.data.api.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiZeppWorkoutScoresRunnerTest {
    private val journal = MiZeppImportRunnerTest.Journal()
    private val base = buildJsonObject {
        put("start_time", 1_800_000_000); put("end_time", 1_800_001_800)
        put("version", 1); put("distance", 4000); put("native_file_field", "keep")
    }
    private val target = MiWorkoutImportTarget("synthetic-source", "1", "outdoor_running", "running",
        1_800_000_000, 1_800_001_800, 28_800, base, "xiaomiwear_app_manually")
    private val source = ZeppWorkoutScores(target.start, target.end, "1800000000", 80.0, 3.2, 1.5, target.end + 1)
    private val cloud = Cloud()
    private val runner = MiZeppWorkoutScoresRunner(cloud, journal)

    @Test fun addsScoresToOwnedSessionAndPreservesAllNativeFiles() = runTest {
        assertEquals(1, runner.run("a", listOf(source), listOf(target)).uploaded)
        assertEquals(JsonPrimitive(3.2), cloud.rows.single().value["train_effect"])
        assertEquals(JsonPrimitive(80), cloud.rows.single().value["train_load"])
        assertEquals(JsonPrimitive(1), cloud.rows.single().value["version"])
        assertEquals(JsonPrimitive("keep"), cloud.rows.single().value["native_file_field"])
        assertEquals(1, runner.run("a", listOf(source), listOf(target)).verified)
        assertEquals(1, cloud.uploads)
    }
    @Test fun neverCreatesASummaryWhenNoOwnedWorkoutMatches() = runTest {
        val result = runner.run("a", listOf(source), emptyList())
        assertEquals(1, result.skipped)
        assertEquals(0, result.conflicts)
        assertEquals(0, result.pending)
        assertEquals(0, cloud.uploads)
    }
    @Test fun rejectsAmbiguousStartsAndContradictoryEndTimes() = runTest {
        assertEquals(1, runner.run("a", listOf(source), listOf(target, target.copy(sourceKey = "other"))).conflicts)
        assertEquals(1, runner.run("a", listOf(source.copy(endSeconds = source.endSeconds!! + 1)), listOf(target)).conflicts)
        assertEquals(0, cloud.uploads)
    }
    @Test fun leavesNativeWatchScoresUntouched() = runTest {
        cloud.rows[0] = cloud.rows[0].copy(value = JsonObject(base + ("train_load" to JsonPrimitive(90))))
        assertEquals(1, runner.run("a", listOf(source), listOf(target)).conflicts)
        assertEquals(0, cloud.uploads)
    }
    @Test fun ownsAndRevisesOnlyItsPreviouslyVerifiedScores() = runTest {
        runner.run("a", listOf(source), listOf(target))
        assertEquals(1, runner.run("a", listOf(source.copy(load = 85.0)), listOf(target)).uploaded)
        assertEquals(1, cloud.rows.size)
    }
    @Test fun uncertainUploadHasNoAutomaticReplayAfterRestart() = runTest {
        cloud.persist = false
        assertEquals(1, runner.run("a", listOf(source), listOf(target)).pending)
        assertEquals(1, MiZeppWorkoutScoresRunner(cloud, journal).run("a", listOf(source), listOf(target)).pending)
        assertEquals(1, cloud.uploads)
    }
    @Test fun failedResponseAfterPersistenceCanBeVerified() = runTest {
        cloud.fail = true
        assertEquals(1, runner.run("a", listOf(source), listOf(target)).verified)
    }
    @Test fun sourceWithNoScoresHasNothingToWrite() = runTest {
        assertEquals(0, runner.run("a", listOf(source.copy(load = null, aerobicEffect = null, anaerobicEffect = null)),
            listOf(target)).uploaded)
        assertEquals(0, cloud.uploads)
    }
    @Test fun fractionalLoadIsNotInventedByRounding() = runTest {
        runner.run("a", listOf(source.copy(load = 80.5)), listOf(target))
        assertNull(cloud.rows.single().value["train_load"])
    }

    private inner class Cloud : MiWorkoutImportCloud {
        val rows = mutableListOf(MiWorkoutExperimentCloudRow(target.sid, target.key, target.category, target.start,
            target.zoneOffsetSeconds, false, target.value))
        var uploads = 0
        var persist = true
        var fail = false
        override suspend fun read(from: Long, until: Long): List<MiWorkoutExperimentCloudRow> = rows.toList()
        override suspend fun upload(target: MiWorkoutImportTarget, value: JsonObject) {
            uploads++
            if (persist) rows[0] = rows[0].copy(value = value)
            if (fail) error("synthetic lost response")
        }
    }
}
