package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.WeightMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightCompositionNormalizerTest {
    private val now = 1_700_000_100L

    @Test
    fun retainsValidCompositionAndProvenanceWhileNormalizingMilliseconds() {
        val input = WeightMeasurement(
            timestamp = 1_700_000_000_000L, weightKg = 70.0,
            bodyFatPercent = 20.0, muscleMassKg = 50.0, boneMassKg = 3.0,
            basalMetabolismKcal = 1_650.5, tzIn15Min = 32,
            bodyWaterMassKg = 38.0, bodyWaterPercent = 54.3,
            leanBodyMassKg = 56.0, skeletalMuscleMassKg = 30.0,
            visceralFatLevel = 8.0, proteinPercent = 17.0, proteinMassKg = 12.0,
            bmi = 23.0, bodyAgeYears = 35, bodyScore = 88.5,
            rawSourceJson = "original", sourcePackage = "example.scale",
        )
        assertEquals(input.copy(timestamp = 1_700_000_000L),
            HealthDataNormalizer.normalizeWeight(listOf(input), now).single())
    }

    @Test
    fun invalidOptionalValuesBecomeNullAndMissingValuesStayNull() {
        val input = WeightMeasurement(
            timestamp = 1_700_000_000L, weightKg = 70.0,
            bodyFatPercent = Double.NaN, muscleMassKg = 71.0, boneMassKg = null,
            basalMetabolismKcal = Double.POSITIVE_INFINITY,
            bodyWaterMassKg = Double.NEGATIVE_INFINITY, bodyWaterPercent = 101.0,
            leanBodyMassKg = 80.0, skeletalMuscleMassKg = -2.0,
            visceralFatLevel = Double.NaN, proteinPercent = -1.0, proteinMassKg = 75.0,
            bmi = Double.NaN, bodyAgeYears = -1, bodyScore = Double.NaN,
            rawSourceJson = "original", sourcePackage = "example.scale",
        )
        val clean = HealthDataNormalizer.normalizeWeight(listOf(input), now).single()
        assertEquals(70.0, clean.weightKg)
        assertNull(clean.bodyFatPercent)
        assertNull(clean.muscleMassKg)
        assertNull(clean.boneMassKg)
        assertNull(clean.basalMetabolismKcal)
        assertNull(clean.bodyWaterMassKg)
        assertNull(clean.bodyWaterPercent)
        assertNull(clean.leanBodyMassKg)
        assertNull(clean.skeletalMuscleMassKg)
        assertNull(clean.visceralFatLevel)
        assertNull(clean.proteinPercent)
        assertNull(clean.proteinMassKg)
        assertNull(clean.bmi)
        assertNull(clean.bodyAgeYears)
        assertNull(clean.bodyScore)
        assertEquals("original", clean.rawSourceJson)
        assertEquals("example.scale", clean.sourcePackage)
    }

    @Test
    fun rejectsNonFiniteAnchorWeights() {
        val input = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).map {
            WeightMeasurement(1_700_000_000L, it)
        }
        assertTrue(HealthDataNormalizer.normalizeWeight(input, now).isEmpty())
    }
}
