package com.bettermifitness.sync.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BodyCompositionMatcherTest {
    private val anchor = BodyMeasurementKey(1_800_000_000L, 123_000_000, "example.scale")

    @Test
    fun newerValuesFromOtherAppsAndOtherWeighInsAreNotAttached() {
        val candidates = listOf(
            BodyCompositionValue(anchor, 22.5, 100L),
            BodyCompositionValue(anchor.copy(sourcePackage = "example.other"), 40.0, 500L),
            BodyCompositionValue(anchor.copy(epochSeconds = anchor.epochSeconds + 1), 35.0, 600L),
            BodyCompositionValue(anchor.copy(nanoseconds = anchor.nanoseconds + 1), 31.0, 700L),
        )
        assertEquals(22.5, BodyCompositionMatcher.latestValue(anchor, candidates))
    }

    @Test
    fun correctedValueAtTheSameMeasurementWinsByModificationTime() {
        val candidates = listOf(
            BodyCompositionValue(anchor, 22.5, 100L, 0),
            BodyCompositionValue(anchor, 22.3, 101L, 10),
            BodyCompositionValue(anchor, 22.4, 101L, 0),
        )
        assertEquals(22.3, BodyCompositionMatcher.latestValue(anchor, candidates))
    }

    @Test
    fun noExactAssociationLeavesTheFieldAbsent() {
        val candidates = listOf(
            BodyCompositionValue(anchor.copy(epochSeconds = anchor.epochSeconds - 60), 20.0, 100L),
            BodyCompositionValue(anchor.copy(sourcePackage = "example.other"), 21.0, 100L),
        )
        assertNull(BodyCompositionMatcher.latestValue(anchor, candidates))
        assertNull(BodyCompositionMatcher.latestValue(anchor, emptyList()))
    }

    @Test
    fun invalidValuesAndUnknownSourcesAreNotAssociated() {
        assertNull(BodyCompositionMatcher.latestValue(anchor, listOf(
            BodyCompositionValue(anchor, Double.NaN, 100L),
            BodyCompositionValue(anchor, Double.POSITIVE_INFINITY, 101L),
        )))
        val unknown = anchor.copy(sourcePackage = "")
        assertNull(BodyCompositionMatcher.latestValue(unknown, listOf(
            BodyCompositionValue(unknown, 22.0, 100L),
        )))
    }
}
