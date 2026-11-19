package com.bettermifitness.sync.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiWeightUploadTest {
    private val complete = WeightMeasurement(
        timestamp = 1_700_000_000L,
        weightKg = 70.05,
        bodyFatPercent = 20.25,
        muscleMassKg = 50.0,
        boneMassKg = 3.05,
        basalMetabolismKcal = 1_650.5,
        tzIn15Min = 32,
        bodyWaterMassKg = 38.55,
        bodyWaterPercent = 55.05,
        leanBodyMassKg = 55.85,
        skeletalMuscleMassKg = 30.0,
        visceralFatLevel = 8.0,
        rawSourceJson = """{"unexpected":"must not be uploaded"}""",
        sourcePackage = "example.scale",
    )

    @Test
    fun legacyUploadRemainsWeightOnlyUnlessExplicitlyEnabled() {
        val value = buildMiWeightUploadValue(complete)
        assertEquals(setOf("weight", "time", "timezone"), value.keys)
        assertEquals(70.05, value.getValue("weight").jsonPrimitive.double)
        assertEquals(1_700_000_000L, value.getValue("time").jsonPrimitive.long)
        assertEquals(32, value.getValue("timezone").jsonPrimitive.int)
    }

    @Test
    fun compositionUsesMiSchemaAndUnitsWithoutInventingMuscleOrVisceralFat() {
        val value = buildMiWeightUploadValue(complete, includeBodyComposition = true)
        assertEquals(
            setOf(
                "weight", "time", "timezone", "body_fat_rate", "bone_mass",
                "basal_metabolism", "body_moisture_mass", "moisture_rate", "fat_free_body",
            ),
            value.keys,
        )
        assertEquals(20.25, value.getValue("body_fat_rate").jsonPrimitive.double)
        assertEquals(3.05, value.getValue("bone_mass").jsonPrimitive.double)
        assertEquals(1_651, value.getValue("basal_metabolism").jsonPrimitive.int)
        assertEquals(38.55, value.getValue("body_moisture_mass").jsonPrimitive.double)
        assertEquals(55.05, value.getValue("moisture_rate").jsonPrimitive.double)
        assertEquals(55.85, value.getValue("fat_free_body").jsonPrimitive.double)
        assertFalse("muscle_mass" in value)
        assertFalse("visceral_fat" in value)
        assertFalse("unexpected" in value)
        assertFalse("sourcePackage" in value)
    }

    @Test
    fun absentAndInvalidOptionalMeasurementsAreOmittedRatherThanZeroed() {
        val measurement = complete.copy(
            bodyFatPercent = Double.NaN,
            boneMassKg = null,
            basalMetabolismKcal = -1.0,
            bodyWaterMassKg = Double.POSITIVE_INFINITY,
            bodyWaterPercent = 101.0,
            leanBodyMassKg = 71.0,
            tzIn15Min = Int.MAX_VALUE,
        )
        assertEquals(
            setOf("weight", "time"),
            buildMiWeightUploadValue(measurement, includeBodyComposition = true).keys,
        )
    }

    @Test
    fun rejectsNonFiniteWeightsAndMillisecondTimestampsBeforeNetworkIo() {
        for (weight in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 501.0)) {
            assertFailsWith<IllegalArgumentException> {
                buildMiWeightUploadValue(complete.copy(weightKg = weight))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            buildMiWeightUploadValue(complete.copy(timestamp = 1_700_000_000_000L))
        }
    }

    @Test
    fun businessSuccessRequiresAnExplicitZeroCode() {
        assertTrue(isMiWeightUploadResponseSuccessful(Json.parseToJsonElement("""{"code":0}""")))
        assertTrue(isMiWeightUploadResponseSuccessful(Json.parseToJsonElement("""{"code":"0"}""")))
        for (response in listOf("{}", "null", "[]", """{"result":{"code":0}}""",
            """{"code":null}""", """{"code":1}""", """{"code":true}""")) {
            assertFalse(isMiWeightUploadResponseSuccessful(Json.parseToJsonElement(response)), response)
        }
    }

    @Test
    fun explicitUploadRejectionIsNotHiddenByZeroBusinessCode() {
        for (response in listOf(
            """{"code":0,"result":{"result":false}}""",
            """{"code":0,"result":false}""",
        )) {
            assertFalse(isMiWeightUploadResponseSuccessful(Json.parseToJsonElement(response)))
        }
        assertTrue(isMiWeightUploadResponseSuccessful(
            Json.parseToJsonElement("""{"code":0,"result":{"result":true}}"""),
        ))
        assertTrue(isMiWeightUploadResponseSuccessful(Json.parseToJsonElement("""{"code":0,"result":{}}""")))
    }

    @Test
    fun readBackAllowsOneDecimalRoundingAndChecksTheRoundedBmrActuallySent() {
        val readBack = complete.copy(
            weightKg = 70.1,
            bodyFatPercent = 20.3,
            boneMassKg = 3.1,
            basalMetabolismKcal = 1_651.0,
            bodyWaterMassKg = 38.6,
            bodyWaterPercent = 55.1,
            leanBodyMassKg = 55.9,
            muscleMassKg = null,
            visceralFatLevel = null,
        )
        assertEquals(emptySet(), missingMiWeightUploadFields(complete, readBack, true))
    }

    @Test
    fun readBackReportsMissingChangedAndNonFiniteFields() {
        val readBack = complete.copy(
            weightKg = 70.11,
            bodyFatPercent = 21.0,
            boneMassKg = null,
            // The sent integer is 1651; an unrounded 1650.5 is not an exact retention.
            basalMetabolismKcal = 1_650.5,
            bodyWaterMassKg = Double.NaN,
            bodyWaterPercent = null,
            leanBodyMassKg = 55.85,
        )
        assertEquals(
            setOf("weight", "body_fat_rate", "bone_mass", "basal_metabolism",
                "body_moisture_mass", "moisture_rate"),
            missingMiWeightUploadFields(complete, readBack, true),
        )
    }

    @Test
    fun absentOrDifferentMeasurementCannotConfirmAnyRequestedField() {
        val expectedMissing = setOf("weight", "body_fat_rate", "bone_mass", "basal_metabolism",
            "body_moisture_mass", "moisture_rate", "fat_free_body")
        assertEquals(expectedMissing, missingMiWeightUploadFields(complete, null, true))
        assertEquals(expectedMissing, missingMiWeightUploadFields(
            complete, complete.copy(timestamp = complete.timestamp + 1), true,
        ))
    }

    @Test
    fun readBackDoesNotDemandFieldsThatWereNeverUploaded() {
        val weightOnlyReadBack = WeightMeasurement(complete.timestamp, complete.weightKg)
        assertEquals(emptySet(), missingMiWeightUploadFields(complete, weightOnlyReadBack))
        val sparse = complete.copy(bodyFatPercent = null, boneMassKg = null,
            basalMetabolismKcal = null, bodyWaterMassKg = null, bodyWaterPercent = null,
            leanBodyMassKg = null)
        assertEquals(emptySet(), missingMiWeightUploadFields(sparse, weightOnlyReadBack, true))
    }
}
