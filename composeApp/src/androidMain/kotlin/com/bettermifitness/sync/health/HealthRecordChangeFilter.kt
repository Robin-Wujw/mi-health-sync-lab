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
 * alone, in the foreground and the background. A failed read cannot prove it is safe to upsert.
 */
internal class HealthRecordChangeFilter(
    private val readOwn: suspend (KClass<out Record>, TimeRangeFilter) -> List<Record>,
) {
    suspend fun changedOnly(records: List<Record>): HealthRecordChanges {
        val skipped = mutableListOf<String>()
        val writable = records.groupBy { it::class }.flatMap { (type, sameType) ->
            val stored = storedStates(type, sameType)
            if (stored == null) {
                skipped += "${type.simpleName} (${sameType.size}): could not read this app's existing records."
                return@flatMap emptyList()
            }
            val changed = sameType.filter { record ->
                val target = stored[record.metadata.clientRecordId]
                if (record is ExerciseSessionRecord && target is ExerciseSessionRecord &&
                    !record.preservesWorkoutDetails(target)) return@filter false
                shouldWriteHealthRecord(record.currentState(), target?.currentState())
            }
            // Counts only: this line lets a device check confirm the comparison ran without exposing health data.
            Log.i(TAG, "${type.simpleName}: ${sameType.size} candidate(s), ${stored.size} stored, ${changed.size} to write")
            changed
        }
        return HealthRecordChanges(writable, skipped)
    }

    private suspend fun storedStates(type: KClass<out Record>, records: List<Record>): Map<String, Record>? =
        try {
            readOwn(type, spanOf(records)).mapNotNull { stored ->
                stored.metadata.clientRecordId?.let { id -> id to stored }
            }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${type.simpleName}: could not compare with stored records (${e::class.simpleName}), not writing")
            null
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
        else -> 0
    }

    private fun ExerciseSessionRecord.preservesWorkoutDetails(stored: ExerciseSessionRecord): Boolean {
        val existingRouteSize = stored.routePointCount() ?: return false
        return (routePointCount() ?: 0) >= existingRouteSize &&
            WorkoutNotes.detailFields(notes).containsAll(WorkoutNotes.detailFields(stored.notes))
    }

    private fun ExerciseSessionRecord.routePointCount(): Int? = when (val result = exerciseRouteResult) {
        is ExerciseRouteResult.Data -> result.exerciseRoute.route.size
        is ExerciseRouteResult.ConsentRequired -> null
        else -> 0
    }

    private companion object {
        const val TAG = "HealthRecordChange"
    }
}

internal data class HealthRecordChanges(val records: List<Record>, val skippedReasons: List<String>)
