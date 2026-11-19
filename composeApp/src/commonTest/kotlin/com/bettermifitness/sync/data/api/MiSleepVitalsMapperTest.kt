package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.health.HealthInboundHeartRateRecord
import com.bettermifitness.sync.health.HealthInboundHeartRateSample
import com.bettermifitness.sync.health.HealthInboundOxygenSaturationRecord
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthInboundSleepStage
import com.bettermifitness.sync.health.HealthInboundSleepStageKind
import com.bettermifitness.sync.health.HealthRecordOrigin
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiSleepVitalsMapperTest {
    private val source = "com.huami.watch.hmwatchmanager"
    private val start = 1_800_000_000L
    private fun origin(id: String, revision: Long = 1) = HealthRecordOrigin(id, source, null, revision, revision)
    private fun sleep(id: String = "night", from: Long = start, until: Long = start + 600) = HealthInboundSleepRecord(
        origin(id), from * 1_000, until * 1_000, 28_800, 28_800,
        listOf(HealthInboundSleepStage(from * 1_000, until * 1_000, HealthInboundSleepStageKind.LIGHT)))
    private fun heart(vararg points: Pair<Long, Long>) = HealthInboundHeartRateRecord(origin("hr"), start * 1_000,
        (start + 1_000) * 1_000, 28_800, 28_800, points.map { HealthInboundHeartRateSample(it.first * 1_000, it.second) })
    private fun plan(sleeps: List<HealthInboundSleepRecord>, hearts: List<HealthInboundHeartRateRecord> = emptyList(),
        oxygen: List<HealthInboundOxygenSaturationRecord> = emptyList(), breath: List<SleepVitalPoint> = emptyList()) =
        MiSleepVitalsMapper.enrich(MiHealthImportMapper.sleep(sleeps, source), sleeps, source, hearts, oxygen, breath)

    @Test fun sleepSummaryIncludesMeasuredExtremaAndDoesNotInventEfficiency() {
        val result = plan(listOf(sleep()), listOf(heart(start to 60, start + 60 to 80)),
            listOf(HealthInboundOxygenSaturationRecord(origin("oxygen"), (start + 30) * 1_000, 28_800, 97.0)),
            listOf(SleepVitalPoint(start + 30, 16, "breath")))
        val row = result.rawRows.single()
        assertEquals(JsonPrimitive(70), row.value["avg_hr"])
        assertEquals(JsonPrimitive(60), row.value["min_hr"])
        assertEquals(JsonPrimitive(80), row.value["max_hr"])
        assertEquals(JsonPrimitive(97), row.value["avg_spo2"])
        assertEquals(JsonPrimitive(16), row.value["avg_breath"])
        assertFalse("sleep_efficiency" in row.value)
        assertFalse("sleep_duration" in row.value)
        assertEquals(1, result.sleepFiles.size)
        assertEquals(listOf(start, start + 60), result.sleepFiles.single().heartRate.map { it.time })
    }

    @Test fun awakeSamplesAndTheWakingBoundaryDoNotEnterTheSummary() {
        val night = sleep().copy(stages = listOf(
            HealthInboundSleepStage(start * 1_000, (start + 60) * 1_000, HealthInboundSleepStageKind.AWAKE),
            HealthInboundSleepStage((start + 60) * 1_000, (start + 600) * 1_000, HealthInboundSleepStageKind.LIGHT)))
        val row = plan(listOf(night), listOf(heart(start to 120, start + 60 to 60, start + 600 to 110))).rawRows.single()
        assertEquals(JsonPrimitive(60), row.value["avg_hr"])
    }

    @Test fun napsRemainSeparateAndOverlappingSleepCannotStealSamples() {
        val sleeps = listOf(sleep(), sleep("overlap", start + 120, start + 240), sleep("nap", start + 720, start + 900))
        val result = plan(sleeps, listOf(heart(start + 150 to 100, start + 780 to 70)))
        assertEquals(1, result.sleepFiles.size)
        assertEquals(start + 900, result.sleepFiles.single().end)
        assertEquals(JsonPrimitive(70), result.rawRows.single { it.identity.time == start + 900 }.value["avg_hr"])
    }

    @Test fun newestInvalidRevisionNeverRevivesOlderHeartSamples() {
        val old = heart(start + 60 to 65)
        val revised = old.copy(origin = origin("hr", 2), samples = listOf(HealthInboundHeartRateSample((start + 60) * 1_000, 0)))
        val result = plan(listOf(sleep()), listOf(old, revised))
        assertTrue(result.sleepFiles.isEmpty())
        assertFalse("avg_hr" in result.rawRows.single().value)
    }

    @Test fun unknownStagesAreNotTurnedIntoSleepOrAWholeNightAverage() {
        val result = plan(listOf(sleep().copy(stages = emptyList())), listOf(heart(start + 60 to 65)))
        assertTrue(result.sleepFiles.isEmpty())
        assertFalse("avg_hr" in result.rawRows.single().value)
    }

    @Test fun nativeSignedByteLimitDoesNotWrapOrClampMeasuredHeartRate() {
        val result = plan(listOf(sleep()), listOf(heart(start + 60 to 140)))
        assertTrue(result.sleepFiles.isEmpty())
        assertTrue(result.issues.any { it.code == "SLEEP_VITALS_UNREPRESENTABLE" })
        assertFalse("avg_hr" in result.rawRows.single().value)
    }

    @Test fun foreignSourceCannotSupplyTheSleepingHeartRate() {
        val foreign = heart(start + 60 to 80).let { it.copy(origin = it.origin.copy(sourcePackage = "other.source")) }
        assertTrue(plan(listOf(sleep()), listOf(foreign)).sleepFiles.isEmpty())
    }

    @Test fun subsecondSamplesAreNotMovedToWholeSecondSlots() {
        val record = heart(start + 60 to 70).copy(samples = listOf(HealthInboundHeartRateSample(start * 1_000 + 60_500, 70)))
        val result = plan(listOf(sleep()), listOf(record))
        assertTrue(result.sleepFiles.isEmpty())
        assertFalse("avg_hr" in result.rawRows.single().value)
    }

    @Test fun twoRespiratoryDevicesAreNotCombinedIntoOneSleepAverage() {
        val result = plan(listOf(sleep()), breath = listOf(SleepVitalPoint(start + 60, 14, "first", "device-a"),
            SleepVitalPoint(start + 120, 20, "second", "device-b")))
        assertFalse("avg_breath" in result.rawRows.single().value)
    }
}
