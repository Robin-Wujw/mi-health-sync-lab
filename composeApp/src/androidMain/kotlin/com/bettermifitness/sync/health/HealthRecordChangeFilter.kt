package com.bettermifitness.sync.health

import android.util.Log
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException

/**
 * Metadata for a record about to be written. Pass every value the record stores as [content]:
 * a value left out here cannot trigger an update when only that value changes.
 */
internal fun stampedMetadata(clientRecordId: String, revision: Long, vararg content: Any?): Metadata =
    // These are imported cloud records. Their original recording method is
    // not known; importing them does not make them manual user entries.
    Metadata.unknownRecordingMethod(
        clientRecordId = clientRecordId,
        clientRecordVersion = stampedHealthRecordVersion(revision, HealthRecordIds.version(*content)),
    )

/**
 * Follows the Health Connect write guidance: "On every sync, only write new data and updated data
 * that was modified since the last sync."
 *
 * [readOwn] must return only this app's records. An app can read those with its write permission
 * alone, in the foreground and the background. If a read still fails, that record type is written
 * in full: comparing is an optimization and a safeguard, and must never block a sync.
 */
internal class HealthRecordChangeFilter(
    private val readOwn: suspend (KClass<out Record>, TimeRangeFilter) -> List<Record>,
) {
    suspend fun changedOnly(records: List<Record>): List<Record> =
        records.groupBy { it::class }.flatMap { (type, sameType) ->
            val stored = storedStates(type, sameType)
            val changed = sameType.filter { record ->
                shouldWriteHealthRecord(record.currentState(), stored[record.metadata.clientRecordId])
            }
            // Counts only: this line lets a device check confirm the comparison ran without exposing health data.
            Log.i(TAG, "${type.simpleName}: ${sameType.size} candidate(s), ${stored.size} stored, ${changed.size} to write")
            changed
        }

    private suspend fun storedStates(type: KClass<out Record>, records: List<Record>): Map<String, HealthRecordState> =
        try {
            readOwn(type, spanOf(records)).mapNotNull { stored ->
                stored.metadata.clientRecordId?.let { id -> id to stored.currentState() }
            }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${type.simpleName}: could not compare with stored records (${e::class.simpleName}), writing all")
            emptyMap()
        }

    /** Widened by a second on both sides so every record is inside it, however a provider matches intervals. */
    private fun spanOf(records: List<Record>): TimeRangeFilter {
        val spans = records.map { it.timeSpan() }
        return TimeRangeFilter.between(
            spans.minOf { it.start }.minusSeconds(1),
            spans.maxOf { it.endInclusive }.plusSeconds(1),
        )
    }

    /** Lists every type the writer produces, as the library keeps their shared time interfaces internal. */
    private fun Record.timeSpan(): ClosedRange<Instant> = when (this) {
        is BasalMetabolicRateRecord -> time..time
        is BloodPressureRecord -> time..time
        is BodyFatRecord -> time..time
        is BodyTemperatureRecord -> time..time
        is BodyWaterMassRecord -> time..time
        is BoneMassRecord -> time..time
        is HeartRateVariabilityRmssdRecord -> time..time
        is LeanBodyMassRecord -> time..time
        is OxygenSaturationRecord -> time..time
        is RestingHeartRateRecord -> time..time
        is Vo2MaxRecord -> time..time
        is WeightRecord -> time..time
        is ActiveCaloriesBurnedRecord -> startTime..endTime
        is CyclingPedalingCadenceRecord -> startTime..endTime
        is DistanceRecord -> startTime..endTime
        is ElevationGainedRecord -> startTime..endTime
        is ExerciseSessionRecord -> startTime..endTime
        is HeartRateRecord -> startTime..endTime
        is PowerRecord -> startTime..endTime
        is SkinTemperatureRecord -> startTime..endTime
        is SleepSessionRecord -> startTime..endTime
        is SpeedRecord -> startTime..endTime
        is StepsCadenceRecord -> startTime..endTime
        is StepsRecord -> startTime..endTime
        else -> error("No time span known for ${this::class.simpleName}")
    }

    private fun Record.currentState() = HealthRecordState(metadata.clientRecordVersion, detailCount())

    private fun Record.detailCount(): Int = when (this) {
        is CyclingPedalingCadenceRecord -> samples.size
        is HeartRateRecord -> samples.size
        is PowerRecord -> samples.size
        is SpeedRecord -> samples.size
        is StepsCadenceRecord -> samples.size
        // Stages may legitimately merge in a revision, so only their absence ranks lower.
        is SleepSessionRecord -> if (stages.isEmpty()) 0 else 1
        is ExerciseSessionRecord -> routePointCount() + WorkoutNotes.partCount(notes)
        else -> 0
    }

    private fun ExerciseSessionRecord.routePointCount(): Int = when (val result = exerciseRouteResult) {
        is ExerciseRouteResult.Data -> result.exerciseRoute.route.size
        // A route exists but its size is hidden: rank it above no route at all.
        is ExerciseRouteResult.ConsentRequired -> 1
        else -> 0
    }

    private companion object {
        const val TAG = "HealthRecordChange"
    }
}
