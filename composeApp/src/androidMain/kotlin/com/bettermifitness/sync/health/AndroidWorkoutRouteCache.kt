package com.bettermifitness.sync.health

import android.content.Context
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.bettermifitness.sync.data.api.WorkoutRoutePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class AndroidWorkoutRouteCache(
    context: Context,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences("authorized_workout_routes_v1", Context.MODE_PRIVATE)

    @Serializable
    private data class Cached(val modified: Long, val points: List<WorkoutRoutePoint>, val generation: Long, val savedAt: Long)

    fun prune(generation: Long) {
        val stale = prefs.all.filter { (key, value) ->
            key != BROAD_PERMISSION && (value as? String)?.let(::decode)?.isUsable(generation) != true
        }
        if (stale.isNotEmpty()) prefs.edit().also { edit -> stale.keys.forEach(edit::remove) }.commit()
    }

    fun observePermissions(canReadWorkouts: Boolean, canReadAllRoutes: Boolean) {
        val heldBroadPermission = prefs.getBoolean(BROAD_PERMISSION, false)
        if (!canReadWorkouts || heldBroadPermission && !canReadAllRoutes) clear()
        if (canReadWorkouts && canReadAllRoutes && !heldBroadPermission) {
            prefs.edit().putBoolean(BROAD_PERMISSION, true).commit()
        }
    }

    fun clear() {
        if (prefs.all.isNotEmpty()) prefs.edit().clear().commit()
    }

    private fun decode(value: String): Cached? = try {
        Json.decodeFromString<Cached>(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun Cached.isUsable(generation: Long): Boolean =
        this.generation == generation && clockMillis() - savedAt in 0 until MAX_AGE_MILLIS

    fun discard(record: ExerciseSessionRecord) {
        if (prefs.contains(record.metadata.id)) prefs.edit().remove(record.metadata.id).commit()
    }

    fun read(record: ExerciseSessionRecord, generation: Long): List<WorkoutRoutePoint>? {
        val data = prefs.getString(record.metadata.id, null) ?: return null
        val cached = decode(data)
        if (cached == null || !cached.isUsable(generation) || cached.modified != record.metadata.lastModifiedTime.toEpochMilli()) {
            discard(record)
            return null
        }
        return cached.points
    }
    fun save(record: ExerciseSessionRecord, route: ExerciseRoute, generation: Long) {
        check(route.route.all { it.time.nano == 0 }) { "Subsecond route points require an explicit precision mapping." }
        val points = route.route.map { WorkoutRoutePoint(it.time.epochSecond, it.latitude, it.longitude,
            it.altitude?.inMeters, it.horizontalAccuracy?.inMeters) }
        check(prefs.edit().putString(record.metadata.id,
            Json.encodeToString(Cached(record.metadata.lastModifiedTime.toEpochMilli(), points, generation, clockMillis()))).commit())
    }

    private companion object {
        const val BROAD_PERMISSION = "cached_broad_route_permission"
        const val MAX_AGE_MILLIS = 86_400_000L
    }
}
