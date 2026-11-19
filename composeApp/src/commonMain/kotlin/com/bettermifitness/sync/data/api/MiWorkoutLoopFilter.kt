package com.bettermifitness.sync.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Exact verified ownership only; native watch and edited rows remain eligible for normal export. */
internal fun isVerifiedWorkoutImport(row: SportRecordEntry, targets: List<MiWorkoutImportTarget>): Boolean {
    if (row.deleted || row.sid !in setOf("default", "xiaomiwear_app_manually")) return false
    return targets.any { target ->
        row.key == target.key && row.time == target.start && runCatching {
            target.matches(MiWorkoutExperimentCloudRow(checkNotNull(row.sid), checkNotNull(row.key),
                checkNotNull(row.category), row.time, row.zoneOffsetSeconds, false,
                Json.parseToJsonElement(row.value) as JsonObject))
        }.getOrDefault(false)
    }
}
