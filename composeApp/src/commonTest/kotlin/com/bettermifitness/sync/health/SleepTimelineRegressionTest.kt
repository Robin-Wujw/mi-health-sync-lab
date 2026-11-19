package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.SleepSession
import com.bettermifitness.sync.data.api.SleepStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SleepTimelineRegressionTest {
    private val start = 1_700_000_000L
    private val now = start + 100_000L

    private fun session(from: Long, to: Long, vararg stages: SleepStage) = SleepSession(
        startTime = start + from, endTime = start + to, stages = stages.toList(), tzIn15Min = 32,
    )

    private fun stage(from: Long, to: Long, code: Int) = SleepStage(start + from, start + to, code)

    @Test
    fun conflictingOverlapBecomesUnknownWithoutOverlapOrArtificialOneSecondGaps() {
        val result = HealthDataNormalizer.normalizeSleep(listOf(
            session(0, 150, stage(0, 100, 2), stage(50, 150, 3)),
        ), now).single()
        assertEquals(listOf(stage(0, 50, 2), stage(50, 100, 1), stage(100, 150, 3)), result.stages)
        assertTrue(result.stages.zipWithNext().all { (a, b) -> a.endTime <= b.startTime })
    }

    @Test
    fun duplicateReportsDoNotReviveAConflictingPhaseAndInputOrderDoesNotMatter() {
        val deep = session(0, 150, stage(0, 100, 2))
        val light = session(0, 150, stage(50, 150, 3))
        val duplicate = session(0, 150, stage(0, 100, 2))
        val expected = listOf(stage(0, 50, 2), stage(50, 100, 1), stage(100, 150, 3))
        for (input in listOf(listOf(deep, light, duplicate), listOf(duplicate, deep, light),
            listOf(light, duplicate, deep))) {
            val result = HealthDataNormalizer.normalizeSleep(input, now)
            assertEquals(1, result.size)
            assertEquals(expected, result.single().stages)
        }
    }

    @Test
    fun stageBoundsAreClippedAndUnreportedGapsStayEmpty() {
        val result = HealthDataNormalizer.normalizeSleep(listOf(
            session(0, 600, stage(-60, 100, 2), stage(300, 700, 3)),
        ), now).single()
        assertEquals(listOf(stage(0, 100, 2), stage(300, 600, 3)), result.stages)
    }

    @Test
    fun overlappingNightReportsMergeWhileSeparatedNapRemainsItsOwnSession() {
        val night = session(0, 3600, stage(0, 3600, 3))
        val legacy = session(300, 1800, stage(300, 1800, 3))
        val nap = session(20_000, 21_000, stage(20_000, 21_000, 2))
        val result = HealthDataNormalizer.normalizeSleep(listOf(nap, legacy, night), now)
        assertEquals(2, result.size)
        assertEquals(start, result[0].startTime)
        assertEquals(start + 3600, result[0].endTime)
        assertEquals(listOf(stage(0, 3600, 3)), result[0].stages)
        assertEquals(start + 20_000, result[1].startTime)
    }

    @Test
    fun aReportedSessionWithoutStagesSurvivesAndFutureTailIsClippedToNow() {
        val empty = HealthDataNormalizer.normalizeSleep(listOf(session(0, 600)), now).single()
        assertTrue(empty.stages.isEmpty())
        val open = HealthDataNormalizer.normalizeSleep(listOf(
            session(0, 600, stage(0, 600, 3)),
        ), start + 400).single()
        assertEquals(start + 400, open.endTime)
        assertEquals(listOf(stage(0, 400, 3)), open.stages)
    }

    @Test
    fun unknownAndPhoneTrackingAreNotRelabeledAsAwakeOrSpecificSleep() {
        val result = HealthDataNormalizer.normalizeSleep(listOf(
            session(0, 600, stage(0, 100, 1), stage(100, 200, 6), stage(300, 600, 99)),
        ), now).single()
        assertEquals(listOf(stage(0, 200, 1), stage(300, 600, 1)), result.stages)
    }

    @Test
    fun positiveHrvSummariesArePreservedWithoutAssumingAnAlgorithmOrNormalRange() {
        val result = HealthDataNormalizer.normalizeSleep(listOf(
            session(0, 600).copy(avgHrvMs = 2, minHrvMs = 1, maxHrvMs = 420),
        ), now).single()
        assertEquals(2, result.avgHrvMs)
        assertEquals(1, result.minHrvMs)
        assertEquals(420, result.maxHrvMs)
    }
}
