package com.bettermifitness.sync.health

import android.content.Context
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidWorkoutRouteCacheTest {
    @Test
    fun authorizedRouteSurvivesReopenAndSourceRevisionInvalidatesIt() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("authorized_workout_routes_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val start = Instant.parse("2026-11-01T00:00:00Z")
        fun record(modified: Long): ExerciseSessionRecord {
            val metadata = Metadata::class.java.declaredConstructors.single { it.parameterCount == 7 }
                .newInstance(0, "synthetic-route", DataOrigin("com.huami.watch.hmwatchmanager"),
                    Instant.ofEpochSecond(modified), null, 0L, null) as Metadata
            return ExerciseSessionRecord(start, ZoneOffset.UTC, start.plusSeconds(100), ZoneOffset.UTC,
                metadata, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)
        }
        try {
            val cache = AndroidWorkoutRouteCache(context)
            val original = record(1)
            assertNull(cache.read(original))
            cache.save(original, ExerciseRoute(listOf(ExerciseRoute.Location(start.plusSeconds(5), 1.2, 103.8))))
            val reopened = AndroidWorkoutRouteCache(context)
            assertEquals(1.2, reopened.read(original)!!.single().latitude)
            assertNull(reopened.read(record(2)))
            assertFailsWith<IllegalStateException> {
                reopened.save(original, ExerciseRoute(listOf(ExerciseRoute.Location(start.plusNanos(1), 1.2, 103.8))))
            }
            assertEquals(1, reopened.read(original)!!.size)
        } finally { prefs.edit().clear().commit() }
    }
}
