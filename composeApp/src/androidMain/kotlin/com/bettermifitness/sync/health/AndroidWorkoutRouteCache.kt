package com.bettermifitness.sync.health

import android.content.Context
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.bettermifitness.sync.data.api.WorkoutRoutePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** User-authorized routes stay in app-private storage and are invalidated by source revisions. */
internal class AndroidWorkoutRouteCache(context: Context) {
    private val prefs = context.getSharedPreferences("authorized_workout_routes_v1", Context.MODE_PRIVATE)
    @Serializable private data class Cached(val modified: Long, val points: List<WorkoutRoutePoint>)
    fun read(record: ExerciseSessionRecord): List<WorkoutRoutePoint>? {
        val data = prefs.getString(record.metadata.id, null) ?: return null
        val cached = Json.decodeFromString<Cached>(data)
        return cached.points.takeIf { cached.modified == record.metadata.lastModifiedTime.toEpochMilli() }
    }
    fun save(record: ExerciseSessionRecord, route: ExerciseRoute) {
        check(route.route.all { it.time.nano == 0 }) { "Subsecond route points require an explicit precision mapping." }
        val points = route.route.map { WorkoutRoutePoint(it.time.epochSecond, it.latitude, it.longitude,
            it.altitude?.inMeters, it.horizontalAccuracy?.inMeters) }
        check(prefs.edit().putString(record.metadata.id,
            Json.encodeToString(Cached(record.metadata.lastModifiedTime.toEpochMilli(), points))).commit())
    }
}
