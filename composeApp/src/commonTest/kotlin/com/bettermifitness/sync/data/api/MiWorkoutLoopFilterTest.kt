package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.health.HealthRecordOrigin
import com.bettermifitness.sync.health.InboundWorkout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiWorkoutLoopFilterTest {
    private val target = MiWorkoutImportMapper.map(InboundWorkout(
        HealthRecordOrigin("source", "com.huami.watch.hmwatchmanager", null, 1, 1000),
        WorkoutSession(1_800_000_000, 1_800_001_800, "indoor_running", distanceMeters = 3000.0, tzIn15Min = 32), 1800))!!
    private val row = SportRecordEntry(target.key, target.start, target.value.toString(), target.category, target.sid, 28_800)

    @Test
    fun onlyOwnedUnchangedRowsIncludingPromotedFilesAreExcludedFromExport() {
        assertTrue(isVerifiedWorkoutImport(row, listOf(target)))
        val promoted = JsonObject(target.value + mapOf("version" to JsonPrimitive(1), "did" to JsonPrimitive("default"),
            "proto_type" to JsonPrimitive(8)))
        assertTrue(isVerifiedWorkoutImport(row.copy(value = promoted.toString()), listOf(target)))
        val manual = JsonObject(promoted + ("did" to JsonPrimitive("xiaomiwear_app_manually")))
        assertTrue(isVerifiedWorkoutImport(row.copy(value = manual.toString()), listOf(target)))
        assertFalse(isVerifiedWorkoutImport(row, emptyList()))
        assertFalse(isVerifiedWorkoutImport(row.copy(sid = "native-watch"), listOf(target)))
        assertFalse(isVerifiedWorkoutImport(row.copy(zoneOffsetSeconds = 0), listOf(target)))
        assertFalse(isVerifiedWorkoutImport(row.copy(value = JsonObject(target.value + ("distance" to JsonPrimitive(2999))).toString()),
            listOf(target)))
        assertFalse(isVerifiedWorkoutImport(row.copy(value = "broken-json"), listOf(target)))
    }
}
