package com.bettermifitness.sync.lab

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.api.MiWorkoutExperimentCloud
import com.bettermifitness.sync.data.api.MiWorkoutExperimentJournal
import com.bettermifitness.sync.data.api.MiWorkoutExperimentReservation
import com.bettermifitness.sync.data.api.MiWorkoutExperimentTarget
import com.bettermifitness.sync.data.api.MiWorkoutUploadExperiment
import com.bettermifitness.sync.data.api.parseMiWorkoutExperimentRow
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Test APK only: one account-scoped permanent slot; no reset, selection or batch argument. */
internal suspend fun workoutUploadExperiment(
    context: Context, session: MiSessionManager, client: HealthConnectClient,
    from: Instant, until: Instant, allowWrite: Boolean,
): String {
    val source = readTreadmillTargets(client, from, until)
    val account = checkNotNull(session.currentUserId)
    val accountDigest = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
    val preferences = context.getSharedPreferences("workout_upload_single_experiment_v1", Context.MODE_PRIVATE)
    val journal = object : MiWorkoutExperimentJournal {
        override suspend fun load(): MiWorkoutExperimentReservation? = preferences.getString(accountDigest, null)?.let {
            Json.decodeFromString<MiWorkoutExperimentReservation>(it)
        }

        override suspend fun reserve(target: MiWorkoutExperimentTarget): Boolean = synchronized(preferences) {
            if (preferences.contains(accountDigest)) false else preferences.edit()
                .putString(accountDigest, Json.encodeToString(MiWorkoutExperimentReservation(target))).commit()
        }

        override suspend fun verified(target: MiWorkoutExperimentTarget) {
            check(load()?.target == target)
            check(preferences.edit().putString(accountDigest,
                Json.encodeToString(MiWorkoutExperimentReservation(target, true))).commit())
        }
    }
    var networkWrites = 0
    val cloud = object : MiWorkoutExperimentCloud {
        override suspend fun read(from: Long, until: Long) =
            readWorkoutCloud(session, from, until).map(::parseMiWorkoutExperimentRow)

        override suspend fun upload(target: MiWorkoutExperimentTarget): kotlinx.serialization.json.JsonElement {
            check(allowWrite && networkWrites == 0)
            val sourceNow = readTreadmillTargets(client, from, until)
            check(sourceNow.singleOrNull { it.sourceId == target.sourceId } == target)
            val dataClient = session.api.dataClient()
            val payload = target.payload(dataClient.phoneId)
            networkWrites++
            return dataClient.encryptedPost(path = "/app/v1/data/up_sport_records", payload = payload.toString())
        }
    }
    val result = MiWorkoutUploadExperiment(cloud, journal).run(source, allowWrite)
    val selected = journal.load()?.target ?: source.maxByOrNull { it.start }
    return listOf("workout_upload.eligible_source_treadmills=${source.size}",
        "workout_upload.health_network_write_attempts=$networkWrites", "workout_upload.source=treadmill_zepp",
        "workout_upload.has_distance=${selected?.distanceMeters != null}",
        "workout_upload.has_active_calories=${selected?.activeCaloriesKcal != null}",
        "workout_upload.preview_only=${!allowWrite}", result.report()).joinToString("\n")
}

private suspend fun readTreadmillTargets(
    client: HealthConnectClient, from: Instant, until: Instant,
): List<MiWorkoutExperimentTarget> {
    val granted = client.permissionController.getGrantedPermissions()
    suspend fun <T : Record> read(type: KClass<T>): List<T> {
        check(HealthPermission.getReadPermission(type) in granted)
        val rows = mutableListOf<T>()
        val tokens = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        var token: String? = null
        var pages = 0
        do {
            val page = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, until),
                dataOriginFilter = setOf(DataOrigin("com.huami.watch.hmwatchmanager")), pageToken = token))
            pages++
            for (row in page.records) {
                check(row.metadata.dataOrigin.packageName == "com.huami.watch.hmwatchmanager")
                check(row.metadata.id.isNotBlank() && ids.add(row.metadata.id))
                if (row.metadata.clientRecordId?.startsWith("mifit-") != true) rows += row
            }
            token = page.pageToken
            check(token == null || tokens.add(token) && pages < 100)
        } while (token != null)
        return rows
    }
    val sessions = read(ExerciseSessionRecord::class)
    val distances = read(DistanceRecord::class)
    val calories = read(ActiveCaloriesBurnedRecord::class)
    return sessions.filter { row ->
        row.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL &&
            row.startTime >= from && row.endTime <= until && row.startTime.nano == 0 && row.endTime.nano == 0 &&
            row.startZoneOffset != null && row.startZoneOffset == row.endZoneOffset &&
            row.segments.none { it.segmentType in setOf(ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST) } &&
            sessions.count { it.startTime < row.endTime && it.endTime > row.startTime } == 1
    }.map { row ->
        val distanceRows = distances.filter { it.startTime == row.startTime && it.endTime == row.endTime }
        val calorieRows = calories.filter { it.startTime == row.startTime && it.endTime == row.endTime }
        check(distanceRows.size <= 1 && calorieRows.size <= 1)
        val distance = distanceRows.singleOrNull()?.distance?.inMeters
        val active = calorieRows.singleOrNull()?.energy?.inKilocalories
        check(distance == null || distance.isFinite() && distance in 0.0..200_000.0)
        check(active == null || active.isFinite() && active in 0.0..20_000.0)
        val revision = listOf(row.metadata.lastModifiedTime.toString()) +
            distanceRows.map { "${it.metadata.id}:${it.metadata.lastModifiedTime}" } +
            calorieRows.map { "${it.metadata.id}:${it.metadata.lastModifiedTime}" }
        MiWorkoutExperimentTarget(row.metadata.id, revision.joinToString("|"), row.startTime.epochSecond,
            row.endTime.epochSecond, checkNotNull(row.startZoneOffset).totalSeconds, distance?.roundToInt(), active?.roundToInt())
    }
}
