package com.bettermifitness.sync.data.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeightMeasurementTest {
    @Test
    fun byTimeRowsRetainCloudSourceWithoutBreakingLegacyConstructors() {
        val legacy = HeartRateEntry("weight", 1_700_000_000L, "{}")
        assertNull(legacy.sid)
        val row = Json.decodeFromString(HeartRateEntry.serializer(),
            """{"key":"weight","time":1700000000,"value":"{}","sid":"xiaomiwear_app_manually"}""",
        )
        assertEquals("xiaomiwear_app_manually", row.sid)
    }

    @Test
    fun oldPositionalArgumentsKeepTheirMeaning() {
        val measurement = WeightMeasurement(1_700_000_000L, 70.0, 20.0, 50.0, 3.0, 1_600.0, 32)
        assertEquals(32, measurement.tzIn15Min)
        assertEquals(50.0, measurement.muscleMassKg)
        assertNull(measurement.bodyWaterMassKg)
        assertNull(measurement.sourcePackage)
    }

    @Test
    fun legacySerializedRecordsDecodeWithAbsentCompositionAsNull() {
        val measurement = Json.decodeFromString(WeightMeasurement.serializer(),
            """{"timestamp":1700000000,"weightKg":70.0,"tzIn15Min":32}""",
        )
        assertNull(measurement.bodyWaterPercent)
        assertNull(measurement.leanBodyMassKg)
        assertNull(measurement.visceralFatLevel)
        assertNull(measurement.rawSourceJson)
    }

    @Test
    fun extendedRecordRoundTripsWithoutLosingSourceOrOptionalFields() {
        val measurement = WeightMeasurement(
            timestamp = 1_700_000_000L, weightKg = 70.0,
            bodyWaterMassKg = 38.0, bodyWaterPercent = 54.3,
            leanBodyMassKg = 56.0, skeletalMuscleMassKg = 30.0,
            visceralFatLevel = 8.0, proteinPercent = 17.0, proteinMassKg = 11.9,
            bmi = 23.0, bodyAgeYears = 35, bodyScore = 88.0,
            rawSourceJson = """{"weight":70,"extra":{"original":true}}""",
            sourcePackage = "example.scale",
        )
        val serialized = Json.encodeToString(WeightMeasurement.serializer(), measurement)
        assertEquals(measurement, Json.decodeFromString(WeightMeasurement.serializer(), serialized))
    }
}
