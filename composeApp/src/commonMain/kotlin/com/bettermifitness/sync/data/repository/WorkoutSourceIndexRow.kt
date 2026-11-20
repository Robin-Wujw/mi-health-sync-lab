package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.FitnessResponse
import com.bettermifitness.sync.data.api.FitnessResult
import com.bettermifitness.sync.data.api.MiWorkoutImportTarget
import com.bettermifitness.sync.data.api.SportRecordEntry
import com.bettermifitness.sync.data.api.WorkoutSession
import com.bettermifitness.sync.data.api.isVerifiedWorkoutImport
import com.bettermifitness.sync.data.parse.MiFitnessParsers
import com.bettermifitness.sync.data.api.canonicalMiHealthImportJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

@Serializable
internal data class WorkoutSourceIndexRow(val time: Long, val summary: WorkoutSession)

internal fun workoutSourceStream(targets: List<MiWorkoutImportTarget>): String {
    val ownership = targets.map { target ->
        Json.encodeToString(listOf(target.sid, target.key, target.category, target.start.toString(),
            target.zoneOffsetSeconds.toString(), canonicalMiHealthImportJson(target.value)))
    }.distinct().sorted()
    return "sport-index-v1|${Json.encodeToString(ownership).encodeUtf8().sha256().hex()}"
}

internal fun projectWorkoutSourcePage(page: FitnessResult<SportRecordEntry>, owned: List<MiWorkoutImportTarget>):
    FitnessResponse<WorkoutSourceIndexRow> = FitnessResponse(code = 0, result = FitnessResult(
        dataList = page.dataList.filterNot { it.deleted || isVerifiedWorkoutImport(it, owned) }.mapNotNull { row ->
            MiFitnessParsers.parseWorkout(row)?.let { WorkoutSourceIndexRow(row.time, it) }
        },
        hasMore = page.hasMore,
        nextKey = page.nextKey,
    ))
