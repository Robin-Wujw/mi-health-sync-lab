package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.TemperatureSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class WorkoutNotYetSaved(cause: Exception) : IllegalStateException(cause)

internal suspend fun <Workout> writeAppleWorkouts(
    workouts: List<Workout>,
    primary: suspend (Workout) -> Int,
    fallback: suspend (Workout) -> Int,
) {
    var written = 0
    var failed = 0
    var failedDetails = 0
    for (workout in workouts) {
        currentCoroutineContext().ensureActive()
        try {
            failedDetails += try {
                primary(workout)
            } catch (_: WorkoutNotYetSaved) {
                currentCoroutineContext().ensureActive()
                fallback(workout)
            }
            written++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed++
        }
    }
    if (failed == 0 && failedDetails == 0) return
    throw HealthWriteIncompleteException(written, buildList {
        if (failed > 0) add("Workouts not confirmed: $failed.")
        if (failedDetails > 0) add("Workout detail groups not confirmed: $failedDetails.")
    }, storeName = "Apple Health")
}

internal suspend fun writeOptionalAppleDetail(save: suspend () -> Unit): Int {
    currentCoroutineContext().ensureActive()
    return try {
        save()
        0
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        1
    }
}

internal data class AppleBodyTemperature(val timestamp: Long, val celsius: Double)
internal data class AppleTemperaturePlan(val body: List<AppleBodyTemperature>, val skippedSkin: Int)

internal fun planAppleTemperatureWrites(samples: List<TemperatureSample>): AppleTemperaturePlan = AppleTemperaturePlan(
    body = samples.mapNotNull { sample -> sample.bodyCelsius?.let { AppleBodyTemperature(sample.timestamp, it) } },
    skippedSkin = samples.count { it.skinCelsius != null },
)
