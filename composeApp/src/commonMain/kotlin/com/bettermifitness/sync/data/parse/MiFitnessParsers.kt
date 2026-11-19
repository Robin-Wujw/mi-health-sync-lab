package com.bettermifitness.sync.data.parse

import com.bettermifitness.sync.data.api.ActiveCaloriesSample
import com.bettermifitness.sync.data.api.BloodPressureSample
import com.bettermifitness.sync.data.api.DistanceSample
import com.bettermifitness.sync.data.api.HeartRateEntry
import com.bettermifitness.sync.data.api.HeartRateSample
import com.bettermifitness.sync.data.api.HrvAlgorithm
import com.bettermifitness.sync.data.api.HrvSample
import com.bettermifitness.sync.data.api.SleepEntry
import com.bettermifitness.sync.data.api.SleepSession
import com.bettermifitness.sync.data.api.SleepStage
import com.bettermifitness.sync.data.api.SpO2Sample
import com.bettermifitness.sync.data.api.SportRecordEntry
import com.bettermifitness.sync.data.api.StepsRecord
import com.bettermifitness.sync.data.api.TemperatureSample
import com.bettermifitness.sync.data.api.Vo2MaxSample
import com.bettermifitness.sync.data.api.WeightMeasurement
import com.bettermifitness.sync.data.api.WorkoutSession
import com.bettermifitness.sync.data.api.WorkoutTimedSample
import com.bettermifitness.sync.health.HealthDataNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Normalized raw fitness row from Mi list/latest endpoints.
 * Decouples parsers from DTO type aliases (HeartRateEntry / SleepEntry).
 */
data class RawFitnessEntry(
    val key: String? = null,
    val time: Long = 0,
    val value: String = "",
    val sid: String? = null,
)

fun HeartRateEntry.toRaw(): RawFitnessEntry = RawFitnessEntry(key, time, value, sid)
fun SleepEntry.toRaw(): RawFitnessEntry = RawFitnessEntry(key, time, value, sid)

/**
 * Pure Mi API JSON → domain sample converters.
 * No I/O, no platform APIs — safe for unit tests on both Android and iOS.
 */
object MiFitnessParsers {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Mi sleep persist keys from the official app (`FitnessPersistKey`):
     * modern segments (`sleep`) plus legacy watch reports
     * (`watch_night_sleep`, `watch_daytime_sleep`).
     */
    private val SLEEP_KEYS = setOf("sleep", "watch_night_sleep", "watch_daytime_sleep")

    fun parseHeartRateSamples(entries: List<RawFitnessEntry>): List<HeartRateSample> =
        entries.mapNotNull { entry ->
            try {
                val obj = json.parseToJsonElement(entry.value).jsonObject
                HeartRateSample(
                    // ReportHrItem also has a daily `date_time`; `time` is the
                    // individual sample timestamp, not that day anchor.
                    timestamp = obj.positiveLong("time") ?: entry.time,
                    // APK HrItem serializes as `bpm`; accept `hr` alias for robustness.
                    bpm = obj.exactInt("bpm")
                        ?: obj.exactInt("hr")
                        ?: return@mapNotNull null,
                    tzIn15Min = obj.miTimezoneOrNull(),
                    sourceKey = entry.key,
                    sourceSid = entry.sid,
                )
            } catch (_: Exception) {
                null
            }
        }

    /**
     * RestHrItem serializes its daily timestamp as `date_time`, not the
     * individual heart-rate point's `time`. Endpoint coverage is handled by the caller.
     */
    fun parseRestingHeartRateSamples(entries: List<RawFitnessEntry>): List<HeartRateSample> =
        entries.mapNotNull { entry ->
            try {
                val obj = json.parseToJsonElement(entry.value).jsonObject
                HeartRateSample(
                    timestamp = obj.positiveLong("date_time") ?: entry.time,
                    bpm = obj.exactInt("bpm") ?: return@mapNotNull null,
                    tzIn15Min = obj.miTimezoneOrNull(),
                    sourceKey = entry.key,
                    sourceSid = entry.sid,
                )
            } catch (_: Exception) {
                null
            }
        }

    fun parseSpO2Samples(entries: List<RawFitnessEntry>): List<SpO2Sample> =
        entries.mapNotNull { entry ->
            try {
                val obj = json.parseToJsonElement(entry.value).jsonObject
                SpO2Sample(
                    // APK Spo2Item serializes as `time`; ReportSpo2Item carries both
                    // `date_time` (day anchor, ITimeData.getTimestamp) and `time`
                    // (point timestamp, toSpo2Item target). Prefer `time`.
                    timestamp = obj["time"]?.jsonPrimitive?.longOrNull
                        ?: obj["date_time"]?.jsonPrimitive?.longOrNull
                        ?: entry.time,
                    percentage = obj["spo2"]?.jsonPrimitive?.int ?: return@mapNotNull null,
                    tzIn15Min = obj.miTimezoneOrNull(),
                )
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Mi records per-minute step deltas; bucket into hourly samples to match Mi Fitness UI.
     */
    fun parseHourlySteps(entries: List<RawFitnessEntry>): List<StepsRecord> {
        val byHour = HashMap<Long, Int>()
        val tzByHour = HashMap<Long, Int>()
        for (entry in newestMiCounterRows(entries)) {
            try {
                val o = json.parseToJsonElement(entry.value).jsonObject
                val t = HealthDataNormalizer.toEpochSeconds(o.positiveLong("time") ?: entry.time)
                val steps = o.exactInt("steps")?.takeIf { it >= 0 } ?: continue
                val hourStart = (t / 3600L) * 3600L
                byHour[hourStart] = (byHour[hourStart] ?: 0) + steps
                if (hourStart !in tzByHour) {
                    o.miTimezoneOrNull()?.let { tzByHour[hourStart] = it }
                }
            } catch (_: Exception) {
                /* skip bad entry */
            }
        }
        return byHour.entries
            .filter { it.value > 0 }
            .map { (hour, steps) ->
                StepsRecord(
                    date = hour.toString(),
                    steps = steps,
                    tzIn15Min = tzByHour[hour],
                )
            }
    }

    /**
     * Distance often rides on the same per-minute `steps` stream (`distance` field, meters).
     * Bucket into hourly intervals for Health writes.
     */
    fun parseHourlyDistanceFromSteps(entries: List<RawFitnessEntry>): List<DistanceSample> {
        val byHour = HashMap<Long, Double>()
        val tzByHour = HashMap<Long, Int>()
        for (entry in newestMiCounterRows(entries)) {
            try {
                val o = json.parseToJsonElement(entry.value).jsonObject
                val t = HealthDataNormalizer.toEpochSeconds(o.positiveLong("time") ?: entry.time)
                val meters = o.doubleField("distance") ?: continue
                if (!meters.isFinite() || meters <= 0) continue
                val hourStart = (t / 3600L) * 3600L
                byHour[hourStart] = (byHour[hourStart] ?: 0.0) + meters
                if (hourStart !in tzByHour) {
                    o.miTimezoneOrNull()?.let { tzByHour[hourStart] = it }
                }
            } catch (_: Exception) {
            }
        }
        return byHour.entries
            .filter { it.value > 0 }
            .map { (hour, meters) ->
                DistanceSample(
                    startTime = hour,
                    endTime = hour + 3599,
                    meters = meters,
                    tzIn15Min = tzByHour[hour],
                )
            }
    }

    /** Active energy from the dedicated `calories` key (kcal). */
    fun parseHourlyActiveCalories(entries: List<RawFitnessEntry>): List<ActiveCaloriesSample> {
        val byHour = HashMap<Long, Double>()
        val tzByHour = HashMap<Long, Int>()
        for (entry in newestMiCounterRows(entries)) {
            try {
                val o = json.parseToJsonElement(entry.value).jsonObject
                val t = HealthDataNormalizer.toEpochSeconds(o.positiveLong("time") ?: entry.time)
                val kcal = o.doubleField("calories") ?: continue
                if (!kcal.isFinite() || kcal <= 0) continue
                val hourStart = (t / 3600L) * 3600L
                byHour[hourStart] = (byHour[hourStart] ?: 0.0) + kcal
                if (hourStart !in tzByHour) {
                    o.miTimezoneOrNull()?.let { tzByHour[hourStart] = it }
                }
            } catch (_: Exception) {
            }
        }
        return byHour.entries
            .filter { it.value > 0 }
            .map { (hour, kcal) ->
                ActiveCaloriesSample(
                    startTime = hour,
                    endTime = hour + 3599,
                    kilocalories = kcal,
                    tzIn15Min = tzByHour[hour],
                )
            }
    }

    fun parseWeightMeasurements(entries: List<RawFitnessEntry>): List<WeightMeasurement> =
        entries.mapNotNull { entry ->
            try {
                val o = json.parseToJsonElement(entry.value).jsonObject
                fun number(name: String): Double? = o.doubleField(name)?.takeIf { it.isFinite() }
                val kg = number("weight") ?: return@mapNotNull null
                if (kg <= 0 || kg > 500) return@mapNotNull null
                WeightMeasurement(
                    timestamp = entry.time.takeIf { it > 0 }
                        ?: (o["time"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    weightKg = kg,
                    bodyFatPercent = number("body_fat_rate"),
                    muscleMassKg = number("muscle_mass"),
                    boneMassKg = number("bone_mass"),
                    basalMetabolismKcal = number("basal_metabolism"),
                    tzIn15Min = (o["timezone"] as? JsonPrimitive)?.intOrNull,
                    bodyWaterMassKg = number("body_moisture_mass"),
                    bodyWaterPercent = number("moisture_rate"),
                    leanBodyMassKg = number("fat_free_body"),
                    skeletalMuscleMassKg = number("skeletal_muscle_mass"),
                    visceralFatLevel = number("visceral_fat"),
                    proteinPercent = number("protein_rate"),
                    proteinMassKg = number("protein_mass"),
                    bmi = number("bmi"),
                    bodyAgeYears = number("body_age")?.takeIf {
                        it in 1.0..150.0 && it % 1.0 == 0.0
                    }?.toInt(),
                    bodyScore = number("body_score"),
                    rawSourceJson = entry.value,
                )
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Mi [BloodPressureItem]: systolic_pressure / diastolic_pressure (mmHg).
     * Also accepts high_pressure/low_pressure aliases if present.
     */
    fun parseBloodPressureSamples(entries: List<RawFitnessEntry>): List<BloodPressureSample> =
        entries.mapNotNull { entry ->
            try {
                val o = json.parseToJsonElement(entry.value).jsonObject
                val systolic = o["systolic_pressure"]?.jsonPrimitive?.intOrNull
                    ?: o["high_pressure"]?.jsonPrimitive?.intOrNull
                    ?: return@mapNotNull null
                val diastolic = o["diastolic_pressure"]?.jsonPrimitive?.intOrNull
                    ?: o["low_pressure"]?.jsonPrimitive?.intOrNull
                    ?: return@mapNotNull null
                BloodPressureSample(
                    timestamp = o["time"]?.jsonPrimitive?.longOrNull
                        ?: entry.time.takeIf { it > 0 }
                        ?: return@mapNotNull null,
                    systolicMmhg = systolic,
                    diastolicMmhg = diastolic,
                    pulseBpm = o["pulse"]?.jsonPrimitive?.intOrNull,
                    tzIn15Min = o.miTimezoneOrNull(),
                )
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Mi [TemperatureItem]: body_temperature / skin_temperature (°C).
     * At least one temperature field required.
     */
    fun parseTemperatureSamples(entries: List<RawFitnessEntry>): List<TemperatureSample> =
        entries.mapNotNull { entry ->
            try {
                val o = json.parseToJsonElement(entry.value).jsonObject
                val body = o.doubleField("body_temperature")
                val skin = o.doubleField("skin_temperature")
                if (body == null && skin == null) return@mapNotNull null
                TemperatureSample(
                    timestamp = o["time"]?.jsonPrimitive?.longOrNull
                        ?: entry.time.takeIf { it > 0 }
                        ?: return@mapNotNull null,
                    bodyCelsius = body,
                    skinCelsius = skin,
                    tzIn15Min = o.miTimezoneOrNull(),
                )
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Mi [Vo2MaxItem]: field `vo2_max` (integer mL·kg⁻¹·min⁻¹ in APK).
     */
    fun parseVo2MaxSamples(entries: List<RawFitnessEntry>): List<Vo2MaxSample> =
        entries.mapNotNull { entry ->
            try {
                val o = json.parseToJsonElement(entry.value).jsonObject
                val vo2 = o.doubleField("vo2_max")
                    ?: o["vo2Max"]?.jsonPrimitive?.doubleOrNull
                    ?: return@mapNotNull null
                if (vo2 <= 0 || vo2 > 100) return@mapNotNull null
                Vo2MaxSample(
                    timestamp = o["time"]?.jsonPrimitive?.longOrNull
                        ?: entry.time.takeIf { it > 0 }
                        ?: return@mapNotNull null,
                    mlPerKgMin = vo2,
                    tzIn15Min = o.miTimezoneOrNull(),
                )
            } catch (_: Exception) {
                null
            }
        }

    fun parseWorkouts(entries: List<SportRecordEntry>): List<WorkoutSession> =
        entries.mapNotNull { entry -> parseWorkout(entry) }

    fun parseWorkout(entry: SportRecordEntry): WorkoutSession? {
        return try {
            val payload = json.parseToJsonElement(entry.value).jsonObject
            val startTs = payload["start_time"]?.jsonPrimitive?.longOrNull
                ?: entry.time.takeIf { it > 0 }
                ?: return null
            val durationSec = payload["duration"]?.jsonPrimitive?.longOrNull ?: 0L
            val endTs = payload["end_time"]?.jsonPrimitive?.longOrNull
                ?: (startTs + durationSec).takeIf { durationSec > 0 }
                ?: return null
            if (endTs <= startTs) return null
            // Prefer string category / key from APK sport reports; sport_type may be int or string.
            val sportTypeEl = payload["sport_type"]
            val sportTypeStr = try {
                sportTypeEl?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.toIntOrNull() == null }
            } catch (_: Exception) {
                null
            }
            val activity = entry.category?.takeIf { it.isNotBlank() }
                ?: entry.key?.takeIf { it.isNotBlank() && it != "sport" }
                ?: sportTypeStr
                ?: payload["type"]?.jsonPrimitive?.content
                ?: payload["sport_name"]?.jsonPrimitive?.content
                ?: "workout"
            val version = payload["version"]?.jsonPrimitive?.intOrNull ?: 0
            val protoType = payload["proto_type"]?.jsonPrimitive?.intOrNull
                ?: payload["sport_type"]?.jsonPrimitive?.intOrNull
            val did = payload["did"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val tz = payload.miTimezoneOrNull()
            val gpsTime = payload["time"]?.jsonPrimitive?.longOrNull ?: startTs
            // FDS files exist when report version > 0 (Mi FitnessFDSDataGetter).
            val canFetchFds = version > 0 && !did.isNullOrBlank() && protoType != null && tz != null
            val maxH = payload.doubleField("max_height")
            val minH = payload.doubleField("min_height")
            val avgH = payload.doubleField("avg_height")
            WorkoutSession(
                startTime = startTs,
                endTime = endTs,
                activityType = activity,
                distanceMeters = payload.doubleField("distance")
                    ?: payload.doubleField("corrected_distance"),
                caloriesKcal = payload.doubleField("calories")
                    ?: payload.doubleField("total_cal"),
                avgHeartRateBpm = payload["avg_hrm"]?.jsonPrimitive?.intOrNull,
                maxHeartRateBpm = payload["max_hrm"]?.jsonPrimitive?.intOrNull,
                minHeartRateBpm = payload["min_hrm"]?.jsonPrimitive?.intOrNull,
                totalSteps = payload["steps"]?.jsonPrimitive?.intOrNull
                    ?: payload["total_steps"]?.jsonPrimitive?.intOrNull,
                avgPaceSecPerKm = payload.doubleField("avg_pace"),
                maxPaceSecPerKm = payload.doubleField("max_pace"),
                minPaceSecPerKm = payload.doubleField("min_pace"),
                avgCadenceSpm = payload.doubleField("avg_cadence"),
                maxCadenceSpm = payload.doubleField("max_cadence"),
                maxSpeedMps = payload.doubleField("max_speed"),
                avgStrideCm = payload.doubleField("avg_stride"),
                avgPowerWatts = payload.doubleField("avg_power")
                    ?: payload.doubleField("running_power")
                    ?: payload.doubleField("power"),
                maxPowerWatts = payload.doubleField("max_power"),
                avgGroundContactMs = payload.doubleField("avg_touchdown_time")
                    ?: payload.doubleField("touchdown_time")
                    ?: payload.doubleField("ground_contact_time"),
                avgVerticalOscillationCm = payload.doubleField("avg_vertical_amplitude")
                    ?: payload.doubleField("vertical_amplitude")
                    ?: payload.doubleField("vertical_oscillation"),
                maxElevationM = maxH?.takeIf { it != 0.0 },
                minElevationM = minH?.takeIf { it != 0.0 },
                avgElevationM = avgH?.takeIf { it != 0.0 },
                elevationGainM = elevationGain(minH, maxH, avgH),
                hrZoneWarmupSec = payload["hrm_warm_up_duration"]?.jsonPrimitive?.intOrNull,
                hrZoneFatBurnSec = payload["hrm_fat_burning_duration"]?.jsonPrimitive?.intOrNull,
                hrZoneAerobicSec = payload["hrm_aerobic_duration"]?.jsonPrimitive?.intOrNull,
                hrZoneAnaerobicSec = payload["hrm_anaerobic_duration"]?.jsonPrimitive?.intOrNull,
                hrZoneExtremeSec = payload["hrm_extreme_duration"]?.jsonPrimitive?.intOrNull,
                trainEffect = payload.doubleField("train_effect"),
                trainLoad = payload.doubleField("train_load"),
                recoverMinutes = payload["recover_time"]?.jsonPrimitive?.intOrNull,
                vo2Max = payload.doubleField("vo2_max")?.takeIf { it > 0 },
                tzIn15Min = tz,
                gpsDeviceSid = did.takeIf { canFetchFds },
                gpsTimestampSec = gpsTime.takeIf { canFetchFds },
                gpsTzIn15Min = tz.takeIf { canFetchFds },
                gpsProtoType = protoType.takeIf { canFetchFds },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.doubleField(name: String): Double? {
        val el = this[name] ?: return null
        return try {
            el.jsonPrimitive.doubleOrNull
                ?: el.jsonPrimitive.content.toDoubleOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.intField(name: String): Int? {
        val el = this[name] ?: return null
        return try {
            el.jsonPrimitive.intOrNull
                ?: el.jsonPrimitive.doubleOrNull?.toInt()
                ?: el.jsonPrimitive.content.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.longField(name: String): Long? {
        val el = this[name] ?: return null
        return try {
            el.jsonPrimitive.longOrNull
                ?: el.jsonPrimitive.doubleOrNull?.toLong()
                ?: el.jsonPrimitive.content.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** Mi `timezone` field: offset in units of 15 minutes (28 → UTC+7). */
    private fun JsonObject.miTimezoneOrNull(): Int? =
        exactInt("timezone")?.takeIf { it in -72..72 }

    /** Accept integer-valued JSON numbers/strings without rounding fractional data. */
    private fun JsonObject.exactInt(name: String): Int? {
        val value = (this[name] as? JsonPrimitive)?.doubleOrNull ?: return null
        return value.takeIf {
            it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it == it.toInt().toDouble()
        }?.toInt()
    }

    private fun JsonObject.positiveLong(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

    /** Rough elevation gain from min/max when Mi does not send ascent explicitly. */
    private fun elevationGain(minH: Double?, maxH: Double?, avgH: Double?): Double? {
        if (minH != null && maxH != null && maxH > minH && maxH != 0.0) {
            return maxH - minH
        }
        return null
    }

    /** Maps raw HR entries into timed samples for a workout window. */
    fun heartRateInWindow(
        entries: List<HeartRateSample>,
        startSec: Long,
        endSec: Long,
    ): List<WorkoutTimedSample> =
        entries
            .map { it.copy(timestamp = HealthDataNormalizer.toEpochSeconds(it.timestamp)) }
            .filter { it.timestamp in startSec..endSec && it.bpm in 20..250 }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
            .map { WorkoutTimedSample(it.timestamp, it.bpm.toDouble()) }

    /**
     * Mi Cloud sleep rows are daily-report envelopes: aggregates live at the top level
     * (`total_duration`, `sleep_score`, ...) while per-segment fields (`bedtime`,
     * `wake_up_time`, `items`, ...) sit inside `segment_details[]` (issue #52).
     * A day can carry several segments (night sleep plus naps), so each row expands
     * to one session per segment.
     */
    fun parseSleepSessions(entries: List<RawFitnessEntry>): List<SleepSession> =
        entries
            .filter { it.key in SLEEP_KEYS }
            .flatMap { entry -> parseSegmentSessions(entry) }

    fun parseSleepSession(entry: RawFitnessEntry): SleepSession? =
        parseSegmentSessions(entry).firstOrNull()

    private fun parseSegmentSessions(entry: RawFitnessEntry): List<SleepSession> =
        MiSleepParser.parse(entry)

    /**
     * Preserve generic overnight HRV for diagnostics with UNKNOWN algorithm.
     * Extract it independently of sleep stages: daily envelopes can carry HRV
     * even when their segment objects contain no HRV or no valid sleep stages.
     * A generic `avg_hrv` is not evidence for RMSSD or SDNN.
     */
    fun parseHrvSamples(entries: List<RawFitnessEntry>): List<HrvSample> =
        entries.filter { it.key in SLEEP_KEYS }.flatMap { entry ->
            val root = try {
                json.parseToJsonElement(entry.value) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return@flatMap emptyList()
            val segments = (root["segment_details"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }.orEmpty()
            // Root and segment summaries may repeat a nightly aggregate. When
            // a root summary is available retain it once; do not create one per nap.
            val rootSample = hrvSampleFromPayload(root, root, entry)
            if (rootSample != null) listOf(rootSample)
            else segments.mapNotNull { hrvSampleFromPayload(it, root, entry) }
        }

    private fun hrvSampleFromPayload(
        obj: JsonObject,
        root: JsonObject,
        entry: RawFitnessEntry,
    ): HrvSample? {
        val ms = obj.doubleField("avg_hrv") ?: obj.doubleField("avgHrv") ?: return null
        // Do not impose a clinical "normal range" on exported source data.
        // Destination-specific limits are checked by the platform writer.
        if (!ms.isFinite() || ms <= 0.0) return null
        val t = obj.positiveLong("hrv_analysis_timestamp")
            ?: obj.positiveLong("hrvAnalysisTimestamp")
            ?: obj.positiveLong("wake_up_time")
            ?: root.positiveLong("hrv_analysis_timestamp")
            ?: root.positiveLong("hrvAnalysisTimestamp")
            ?: entry.time.takeIf { it > 0 }
            ?: return null
        return HrvSample(
            timestamp = t,
            hrvMs = ms,
            tzIn15Min = obj.miTimezoneOrNull() ?: root.miTimezoneOrNull(),
            algorithm = HrvAlgorithm.UNKNOWN,
            sourceKey = entry.key,
            sourceSid = entry.sid,
            rawSourceJson = entry.value,
            isAggregate = true,
        )
    }

    fun hrvSamplesFromSessions(sessions: List<SleepSession>): List<HrvSample> =
        sessions.mapNotNull { session ->
            val ms = session.avgHrvMs ?: return@mapNotNull null
            if (ms <= 0) return@mapNotNull null
            val t = session.hrvAnalysisTimeSec
                ?.takeIf { it > 0 }
                ?: session.endTime.takeIf { it > 0 }
                ?: return@mapNotNull null
            HrvSample(
                timestamp = t,
                hrvMs = ms.toDouble(),
                tzIn15Min = session.tzIn15Min,
                algorithm = HrvAlgorithm.UNKNOWN,
                isAggregate = true,
            )
        }

}
