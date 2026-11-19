package com.bettermifitness.sync.data.parse

import com.bettermifitness.sync.data.api.HeartRateEntry
import com.bettermifitness.sync.data.api.HeartRateSample
import com.bettermifitness.sync.data.api.HrvAlgorithm
import com.bettermifitness.sync.data.api.SleepEntry
import com.bettermifitness.sync.health.HealthDataNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardiacParserTest {
    private val time = 1_700_000_000L

    @Test
    fun pointHeartRateUsesTimeNotDayAnchorAndPreservesSource() {
        val samples = MiFitnessParsers.parseHeartRateSamples(listOf(
            HeartRateEntry("heart_rate", time, """{"date_time":1699920000,"time":1700000010,"bpm":72,"timezone":32}""", "watch").toRaw(),
        ))
        assertEquals(1_700_000_010L, samples.single().timestamp)
        assertEquals("watch", samples.single().sourceSid)
        assertEquals("heart_rate", samples.single().sourceKey)
    }

    @Test
    fun malformedOptionalFieldsDoNotEraseAValidHeartRate() {
        val samples = MiFitnessParsers.parseHeartRateSamples(listOf(
            RawFitnessEntry("heart_rate", time, """{"time":0,"bpm":"bad","hr":"72.0","timezone":{}}"""),
            RawFitnessEntry("heart_rate", time + 1, """{"bpm":72.5}"""),
        ))
        assertEquals(listOf(HeartRateSample(time, 72, sourceKey = "heart_rate")), samples)
    }

    @Test
    fun dailyRestingHeartRateUsesDateTimeAndKeepsLatestRowSource() {
        val sample = MiFitnessParsers.parseRestingHeartRateSamples(listOf(
            SleepEntry("resting_heart_rate", time, """{"date_time":1700000010,"time":1700000020,"bpm":55}""", "watch").toRaw(),
        )).single()
        assertEquals(time + 10, sample.timestamp)
        assertEquals("watch", sample.sourceSid)
        assertEquals(sample, HealthDataNormalizer.normalizeHeartRate(listOf(sample), time + 100).single())
    }

    @Test
    fun workoutWindowNormalizesMillisecondsAndDoesNotLoseLowAcceptedHr() {
        val samples = MiFitnessParsers.heartRateInWindow(
            listOf(HeartRateSample(time * 1000, 25), HeartRateSample(time, 25)),
            time - 1,
            time + 1,
        )
        assertEquals(1, samples.size)
        assertEquals(time, samples.single().timeSec)
        assertEquals(25.0, samples.single().value)
    }

    @Test
    fun envelopeHrvSurvivesMissingSleepStagesAndRemainsUnknown() {
        val raw = """{"avg_hrv":42.75,"hrv_analysis_timestamp":1700000010,"timezone":32,"segment_details":[{"items":[]}]}"""
        val sample = MiFitnessParsers.parseHrvSamples(listOf(
            RawFitnessEntry("sleep", time, raw, "watch"),
        )).single()
        assertEquals(time + 10, sample.timestamp)
        assertEquals(42.75, sample.hrvMs)
        assertEquals(HrvAlgorithm.UNKNOWN, sample.algorithm)
        assertEquals(raw, sample.rawSourceJson)
        assertEquals("watch", sample.sourceSid)
        assertTrue(sample.isAggregate)
    }

    @Test
    fun rootAggregateIsNotRepeatedForEachSegment() {
        val raw = """{"avg_hrv":45,"segment_details":[{"avg_hrv":45,"wake_up_time":1700000010},{"avg_hrv":50,"wake_up_time":1700000020}]}"""
        val samples = MiFitnessParsers.parseHrvSamples(listOf(RawFitnessEntry("sleep", time, raw)))
        assertEquals(1, samples.size)
        assertEquals(45.0, samples.single().hrvMs)
    }

    @Test
    fun genericAlgorithmLabelDoesNotOverrideUnverifiedSourceContract() {
        val sample = MiFitnessParsers.parseHrvSamples(listOf(
            RawFitnessEntry("sleep", time, """{"avg_hrv":1.5,"algorithm":"RMSSD"}"""),
        )).single()
        assertEquals(HrvAlgorithm.UNKNOWN, sample.algorithm)
        assertEquals(1.5, sample.hrvMs)
    }
}
