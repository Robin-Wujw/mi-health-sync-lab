package com.bettermifitness.sync.data.parse

import kotlin.test.Test
import kotlin.test.assertEquals

class MiCounterRowsTest {
    private val time = 1_700_000_000L
    private fun row(steps: Int, sid: String? = "watch") = RawFitnessEntry(
        "steps", time, """{"time":1700000000,"steps":$steps,"distance":$steps}""", sid,
    )

    @Test
    fun newerRevisionReplacesOldDeltaBeforeHourlySummation() {
        val entries = listOf(row(12), row(8), row(8))
        assertEquals(12, MiFitnessParsers.parseHourlySteps(entries).single().steps)
        assertEquals(12.0, MiFitnessParsers.parseHourlyDistanceFromSteps(entries).single().meters)
    }

    @Test
    fun missingSidOnlyPermitsExactDuplicateRemoval() {
        assertEquals(listOf(row(12, null), row(8, null)), newestMiCounterRows(listOf(
            row(12, null), row(12, null), row(8, null),
        )))
    }

    @Test
    fun differentDevicesAreNotDeclaredRevisionsOfEachOther() {
        val entries = listOf(row(12, "watch-a"), row(8, "watch-b"))
        assertEquals(entries, newestMiCounterRows(entries))
    }

    @Test
    fun calorieRevisionAndMillisecondTimeDoNotInflateTotals() {
        val newer = RawFitnessEntry("calories", time, """{"time":1700000000000,"calories":2.5}""", "watch")
        val older = RawFitnessEntry("calories", time, """{"time":1700000000,"calories":1.5}""", "watch")
        val result = MiFitnessParsers.parseHourlyActiveCalories(listOf(newer, older)).single()
        assertEquals((time / 3600) * 3600, result.startTime)
        assertEquals(2.5, result.kilocalories)
    }
}
