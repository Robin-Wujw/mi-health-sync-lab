package com.bettermifitness.sync.data.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightCompositionParserTest {
    @Test
    fun readsMiFieldsWithDistinctMassAndPercentageSemanticsAndPreservesRawJson() {
        val value = """{
            "time":1700000001,"weight":"70.5","body_fat_rate":"20.5",
            "muscle_mass":50.0,"muscle_rate":71.0,"bone_mass":3.0,
            "basal_metabolism":"1650","body_moisture_mass":"38.2","moisture_rate":54.2,
            "fat_free_body":56.0,"skeletal_muscle_mass":"30.1","visceral_fat":"8.0",
            "protein_rate":17.0,"protein_mass":"12.0","bmi":23.0,
            "body_age":"35.0","body_score":"88.5","timezone":32,"unknown_field":"kept"
        }"""
        val measurement = MiFitnessParsers.parseWeightMeasurements(
            listOf(RawFitnessEntry("weight", 1_700_000_000L, value)),
        ).single()
        assertEquals(1_700_000_000L, measurement.timestamp)
        assertEquals(70.5, measurement.weightKg)
        assertEquals(20.5, measurement.bodyFatPercent)
        assertEquals(50.0, measurement.muscleMassKg)
        assertEquals(3.0, measurement.boneMassKg)
        assertEquals(1_650.0, measurement.basalMetabolismKcal)
        assertEquals(38.2, measurement.bodyWaterMassKg)
        assertEquals(54.2, measurement.bodyWaterPercent)
        assertEquals(56.0, measurement.leanBodyMassKg)
        assertEquals(30.1, measurement.skeletalMuscleMassKg)
        assertEquals(8.0, measurement.visceralFatLevel)
        assertEquals(17.0, measurement.proteinPercent)
        assertEquals(12.0, measurement.proteinMassKg)
        assertEquals(23.0, measurement.bmi)
        assertEquals(35, measurement.bodyAgeYears)
        assertEquals(88.5, measurement.bodyScore)
        assertEquals(32, measurement.tzIn15Min)
        assertEquals(value, measurement.rawSourceJson)
    }

    @Test
    fun musclePercentageAndLeanMassAreNeverUsedAsMuscleMassFallbacks() {
        val measurement = MiFitnessParsers.parseWeightMeasurements(listOf(RawFitnessEntry(
            value = """{"time":1700000000,"weight":70,"muscle_rate":75,"fat_free_body":56}""",
        ))).single()
        assertEquals(1_700_000_000L, measurement.timestamp)
        assertNull(measurement.muscleMassKg)
        assertEquals(56.0, measurement.leanBodyMassKg)
    }

    @Test
    fun malformedOptionalFieldsDoNotDestroyValidWeightOrBecomeZero() {
        val measurement = MiFitnessParsers.parseWeightMeasurements(listOf(RawFitnessEntry(
            time = 1_700_000_000L,
            value = """{"weight":70,"body_fat_rate":"NaN","bone_mass":{},
                "body_moisture_mass":"Infinity","body_age":35.5,"body_score":null,"timezone":{}}""",
        ))).single()
        assertEquals(70.0, measurement.weightKg)
        assertNull(measurement.bodyFatPercent)
        assertNull(measurement.boneMassKg)
        assertNull(measurement.bodyWaterMassKg)
        assertNull(measurement.bodyAgeYears)
        assertNull(measurement.bodyScore)
        assertNull(measurement.tzIn15Min)
    }

    @Test
    fun nonFiniteWeightCannotEnterTheSyncPipeline() {
        val parsed = MiFitnessParsers.parseWeightMeasurements(listOf(
            RawFitnessEntry(value = """{"weight":"NaN"}"""),
            RawFitnessEntry(value = """{"weight":"Infinity"}"""),
        ))
        assertTrue(parsed.isEmpty())
    }
}
