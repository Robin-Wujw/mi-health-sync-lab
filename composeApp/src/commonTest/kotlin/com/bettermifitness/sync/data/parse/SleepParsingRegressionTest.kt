package com.bettermifitness.sync.data.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SleepParsingRegressionTest {
    @Test
    fun awakeSummaryDoesNotCreateATimestampedStage() {
        val sleep = MiFitnessParsers.parseSleepSession(RawFitnessEntry(
            key = "sleep",
            value = """{"bedtime":1700000000,"wake_up_time":1700003600,
              "sleep_awake_duration":20,"items":[
                {"start_time":1700000000,"end_time":1700003600,"state":3}
              ]}""",
        ))
        assertNotNull(sleep)
        assertEquals(1, sleep.stages.size)
        assertEquals(3, sleep.stages.single().stage)
    }

    @Test
    fun midnightAndMillisecondsKeepAbsoluteTimeAndInheritEnvelopeTimezone() {
        // 23:30 Jan 1 -> 06:30 Jan 2 in UTC+8. These are absolute instants.
        val start = 1_704_123_000L
        val end = 1_704_148_200L
        val sleep = MiFitnessParsers.parseSleepSession(RawFitnessEntry(
            key = "sleep",
            value = """{"timezone":32,"segment_details":[{
              "bedtime":${start * 1000},"wake_up_time":${end * 1000},
              "items":[{"start_time":${start * 1000},"end_time":${end * 1000},"state":3}]
            }]}""",
        ))
        assertNotNull(sleep)
        assertEquals(start, sleep.startTime)
        assertEquals(end, sleep.endTime)
        assertEquals(start, sleep.stages.single().startTime)
        assertEquals(32, sleep.tzIn15Min)
    }

    @Test
    fun malformedItemDoesNotDiscardTheNightAndMalformedSegmentDoesNotDiscardANap() {
        val sessions = MiFitnessParsers.parseSleepSessions(listOf(RawFitnessEntry(
            key = "sleep",
            value = """{"timezone":32,"segment_details":[
              {"bedtime":1700000000,"wake_up_time":1700003600,"items":[
                "broken",{"start_time":"broken","end_time":1700000900,"state":2},
                {"start_time":1700000900,"end_time":1700003600,"state":3}
              ]},
              "broken segment",
              {"bedtime":1700040000,"wake_up_time":1700041800,"timezone":28,"items":[]}
            ]}""",
        )))
        assertEquals(2, sessions.size)
        assertEquals(1, sessions[0].stages.size)
        assertEquals(32, sessions[0].tzIn15Min)
        assertTrue(sessions[1].stages.isEmpty())
        assertEquals(28, sessions[1].tzIn15Min)
    }

    @Test
    fun aggregateDurationAndDailyTimestampCannotInventASleepInterval() {
        val sessions = MiFitnessParsers.parseSleepSessions(listOf(RawFitnessEntry(
            key = "sleep", time = 1_700_000_000L,
            value = """{"total_duration":480,"sleep_awake_duration":20,"segment_details":[]}""",
        )))
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun missingAnchorsCanBeRecoveredFromActualStagesOnly() {
        val sleep = MiFitnessParsers.parseSleepSession(RawFitnessEntry(
            key = "sleep", time = 1_699_900_000L,
            value = """{"items":[
              {"start_time":1700000000,"end_time":1700000900,"state":2},
              {"start_time":1700001800,"end_time":1700003600,"state":3}
            ]}""",
        ))
        assertNotNull(sleep)
        assertEquals(1_700_000_000L, sleep.startTime)
        assertEquals(1_700_003_600L, sleep.endTime)
        assertEquals(2, sleep.stages.size)
    }
}
