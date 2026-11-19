package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.data.time.SOURCE_DAY_SECONDS
import com.bettermifitness.sync.data.time.SourceCalendarDay
import com.bettermifitness.sync.data.time.sourceCalendarDay
import kotlin.time.Instant
import kotlinx.datetime.offsetAt

import com.bettermifitness.sync.health.HealthInboundHeartRateRecord
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthInboundOxygenSaturationRecord
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthInboundRestingHeartRateRecord
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthInboundSleepStageKind
import com.bettermifitness.sync.health.HealthRecordOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Interoperability schema references (no device identity is impersonated):
 * Mori-17/huami-health-bridge xiaomi_health_import.py @ 3823d7ad05e3d03cd97942b0ed55dafae5035ba0
 * (MIT, Copyright 2026 HuaMi Health Bridge contributors), and Mi Fitness 3.59.2
 * RestHrItem, SleepSegmentReport, SleepStateItem and CloudHrReport.
 *
 * The current official APK's two SleepState annotations agree: DEEP=2, LIGHT=3,
 * REM=4, AWAKE=5. Do not copy the reversed deep/light mapping in that importer.
 * Inner sleep `timezone` is 15-minute units; outer `zone_offset` is seconds.
 */
object MiHealthImportMapper {
    fun heartRate(
        records: List<HealthInboundHeartRateRecord>,
        sourcePackage: String,
        includeDailyAggregates: Boolean = false,
        coverage: HealthInboundReadRequest? = null,
    ): MiHealthImportPlan {
        require(sourcePackage.isNotBlank())
        val issues = mutableListOf<MiHealthImportIssue>()
        val rows = mutableListOf<MiHealthImportRow>()
        val internallyConflictingSeconds = mutableSetOf<Long>()
        for (record in latestOrigins(records, { it.origin }, issues)) {
            val origin = record.origin
            if (!validOrigin(origin, sourcePackage, issues)) continue
            if (record.startEpochMillis < 0 || record.endEpochMillis <= record.startEpochMillis) {
                issues.reject("INVALID_INTERVAL", origin, "Heart-rate record has an invalid interval.")
                continue
            }
            val zone = sourceZone(record.startZoneOffsetSeconds, record.endZoneOffsetSeconds, origin, issues)
            // Exclude the entire transport second for contradictory values at
            // one exact instant within a single latest source record.
            internallyConflictingSeconds += record.samples.filter {
                it.epochMillis in record.startEpochMillis..record.endEpochMillis && it.beatsPerMinute in 1L..300L
            }.groupBy { it.epochMillis }.filterValues { same -> same.map { it.beatsPerMinute }.distinct().size > 1 }
                .keys.map { it / 1_000L }
            if (record.samples.isEmpty()) issues.warn("EMPTY_SAMPLES", origin, "Heart-rate record contains no samples.")
            for (sample in record.samples) {
                if (sample.epochMillis < record.startEpochMillis || sample.epochMillis > record.endEpochMillis ||
                    sample.beatsPerMinute !in 1L..300L) {
                    issues.reject("INVALID_HEART_RATE_SAMPLE", origin, "A heart-rate sample is outside its interval or supported range.")
                    continue
                }
                val time = seconds(sample.epochMillis, origin, issues)
                rows += raw("heart_rate", time, buildJsonObject {
                    put("time", time)
                    put("bpm", sample.beatsPerMinute)
                }, zone, origin)
            }
        }
        for ((_, excluded) in rows.filter { it.identity.time in internallyConflictingSeconds }.groupBy { it.identity }) {
            issues += MiHealthImportIssue("HEART_RATE_SOURCE_POINT_EXCLUDED", excluded.flatMap { it.sourceRecordKeys }.toSet(),
                "A source record contradicts itself at one exact instant. This destination second was excluded; other valid points " +
                    "remain importable. No value was selected and original data was not changed.",
                MiHealthImportIssueSeverity.WARNING,
                affectedLocalDays = if (excluded.all { it.dayAggregationEligible }) excluded.map {
                    (it.identity.time + it.zoneOffsetSeconds).floorDiv(86_400L)
                }.toSet() else null, affectedTarget = excluded.first().identity)
        }
        val unique = removeTargetConflicts(rows.filterNot { it.identity.time in internallyConflictingSeconds }, issues,
            collisionCode = "HEART_RATE_SOURCE_POINT_EXCLUDED",
            collisionMessage = "Different source values share one destination second. The entire second was skipped; no value was chosen " +
                "and source data is unchanged.",
            collisionSeverity = MiHealthImportIssueSeverity.WARNING)
        val aggregates = if (includeDailyAggregates) heartRateDays(unique, coverage, issues) else emptyList()
        return MiHealthImportPlan(HealthInboundMetric.HEART_RATE, unique + aggregates, issues.distinct(), records.size)
    }

    fun restingHeartRate(
        records: List<HealthInboundRestingHeartRateRecord>,
        sourcePackage: String,
        includeDailyAggregates: Boolean = false,
        coverage: HealthInboundReadRequest? = null,
    ): MiHealthImportPlan {
        require(sourcePackage.isNotBlank())
        val issues = mutableListOf<MiHealthImportIssue>()
        val candidates = latestOrigins(records, { it.origin }, issues).mapNotNull { record ->
            if (!validOrigin(record.origin, sourcePackage, issues)) return@mapNotNull null
            if (record.epochMillis < 0 || record.beatsPerMinute !in 1L..300L) {
                issues.reject("INVALID_RESTING_HEART_RATE", record.origin, "Resting heart rate has an invalid time or value.")
                return@mapNotNull null
            }
            // RestHrItem is one value per source calendar date, not an instant
            // sample. Official DailyRecordDaoUtils.reprocessModelRecordIfNeed
            // normalizes its outer time and inner date_time to a UTC-coded date
            // and resets the transport offset to zero before persisting/uploading.
            val sourceOffset = record.zoneOffsetSeconds?.takeIf { it in -64_800..64_800 }
            if (sourceOffset == null) {
                issues.reject("RESTING_HEART_RATE_DATE_UNKNOWN", record.origin,
                    "Mi requires a source calendar date for resting heart rate. The Health Connect record has no valid source timezone " +
                        "offset, so no UTC date was guessed.")
                return@mapNotNull null
            }
            val time = cloudDayAnchor(record.epochMillis / 1_000L, sourceOffset)
            if (time < 0) {
                issues.reject("INVALID_RESTING_HEART_RATE_DATE", record.origin,
                    "The source calendar date is before Mi's supported timestamp epoch.")
                return@mapNotNull null
            }
            val row = raw("resting_heart_rate", time, buildJsonObject {
                put("date_time", time)
                put("bpm", record.beatsPerMinute)
            }, SourceZone(0, true), record.origin)
            RestingHeartRateCandidate(record.epochMillis, record.beatsPerMinute, row)
        }
        val unique = candidates.groupBy { it.row.identity }.values.mapNotNull { day ->
            val sources = day.flatMap { it.row.sourceRecordKeys }.toSet()
            if (day.groupBy { it.sourceEpochMillis }.values.any { sameInstant ->
                    sameInstant.map { it.beatsPerMinute }.distinct().size > 1
                }) {
                issues += MiHealthImportIssue("RESTING_HEART_RATE_DAY_COLLISION", sources,
                    "Different resting-heart-rate values share the same source instant within this calendar date. The date was skipped " +
                        "rather than selecting a contradictory measurement.")
                return@mapNotNull null
            }
            // HC stores instantaneous RHR observations. Mi's native daily HR
            // report filters positive RestHrItem values and takes their minimum:
            // HeartRateBiz.splitDailyReport -> dcd -> getRestHr -> minOfOrDefault.
            // Select an actual source RHR value; never derive RHR from ordinary
            // heart-rate samples, average observations, or move a timestamp.
            val selected = day.minBy { it.beatsPerMinute }
            if (day.any { it.beatsPerMinute != selected.beatsPerMinute }) {
                issues += MiHealthImportIssue("RESTING_HEART_RATE_DAILY_MINIMUM_APPLIED", sources,
                    "Mi stores one resting-heart-rate value per source calendar date. The lowest valid observed resting heart rate in " +
                        "the selected input was used, matching Mi Fitness 3.59.2's daily display rule. Other observations remain in " +
                            "Health Connect; this is not an average or a claim that the full day was observed.",
                    MiHealthImportIssueSeverity.WARNING)
            }
            selected.row.copy(sourceRecordKeys = sources)
        }.sortedBy { it.identity.time }
        if (includeDailyAggregates && unique.isNotEmpty()) {
            issues += MiHealthImportIssue(
                "RESTING_HEART_RATE_RAW_ONLY", unique.flatMap { it.sourceRecordKeys }.toSet(),
                "Resting heart rate uses Mi's own raw daily RHR record; no additional unverified aggregate schema is generated.",
                MiHealthImportIssueSeverity.WARNING,
            )
        }
        return MiHealthImportPlan(HealthInboundMetric.RESTING_HEART_RATE, unique, issues.distinct(), records.size)
    }

    fun oxygenSaturation(
        records: List<HealthInboundOxygenSaturationRecord>,
        sourcePackage: String,
        includeDailyAggregates: Boolean = false,
        coverage: HealthInboundReadRequest? = null,
    ): MiHealthImportPlan {
        require(sourcePackage.isNotBlank())
        val issues = mutableListOf<MiHealthImportIssue>()
        val rows = latestOrigins(records, { it.origin }, issues).mapNotNull { record ->
            if (!validOrigin(record.origin, sourcePackage, issues)) return@mapNotNull null
            if (record.epochMillis < 0 || !record.percentage.isFinite() || record.percentage <= 0.0 || record.percentage > 100.0) {
                issues.reject("INVALID_OXYGEN_SATURATION", record.origin,
                    "Oxygen saturation must have a valid time and a finite percentage greater than zero and at most 100.")
                return@mapNotNull null
            }
            val percentage = record.percentage.roundToInt()
            if (percentage == 0) {
                issues.reject("UNREPRESENTABLE_OXYGEN_SATURATION", record.origin,
                    "Mi stores oxygen saturation as a positive whole percentage; this source value would round to zero.")
                return@mapNotNull null
            }
            if (record.percentage != percentage.toDouble()) {
                issues.warn("OXYGEN_SATURATION_PRECISION", record.origin,
                    "Mi's Spo2Item stores a whole percentage, so this value is rounded to the nearest integer. The original Health " +
                        "Connect record retains its precision.")
            }
            val zone = sourceZone(record.zoneOffsetSeconds, record.zoneOffsetSeconds, record.origin, issues)
            val time = seconds(record.epochMillis, record.origin, issues)
            // A HC instant does not prove this was a manual measurement. The
            // official single_spo2 key is reserved for explicitly manual data.
            raw("spo2", time, buildJsonObject {
                put("time", time)
                put("spo2", percentage)
            }, zone, record.origin)
        }
        val unique = removeTargetConflicts(rows, issues)
        val aggregates = if (includeDailyAggregates) oxygenSaturationDays(unique, coverage, issues) else emptyList()
        return MiHealthImportPlan(HealthInboundMetric.OXYGEN_SATURATION, unique + aggregates, issues.distinct(), records.size)
    }

    fun sleep(
        records: List<HealthInboundSleepRecord>,
        sourcePackage: String,
        includeDailyAggregates: Boolean = false,
        coverage: HealthInboundReadRequest? = null,
    ): MiHealthImportPlan {
        require(sourcePackage.isNotBlank())
        val issues = mutableListOf<MiHealthImportIssue>()
        val rows = mutableListOf<MiHealthImportRow>()
        for (record in latestOrigins(records, { it.origin }, issues)) {
            val origin = record.origin
            if (!validOrigin(origin, sourcePackage, issues)) continue
            if (record.startEpochMillis < 0 || record.endEpochMillis <= record.startEpochMillis) {
                issues.reject("INVALID_SLEEP_INTERVAL", origin, "Sleep session has an invalid interval.")
                continue
            }
            val sorted = record.stages.sortedBy { it.startEpochMillis }
            if (sorted.any { it.startEpochMillis < record.startEpochMillis || it.endEpochMillis > record.endEpochMillis ||
                    it.endEpochMillis <= it.startEpochMillis } ||
                sorted.zipWithNext().any { (a, b) -> a.endEpochMillis > b.startEpochMillis }) {
                issues.reject("INVALID_SLEEP_STAGES", origin,
                    "Sleep stages overlap or extend outside their session; the session was not altered.")
                continue
            }
            val zone = sourceZone(record.startZoneOffsetSeconds, record.endZoneOffsetSeconds, origin, issues)
            val start = seconds(record.startEpochMillis, origin, issues)
            val end = seconds(record.endEpochMillis, origin, issues)
            if (end <= start) {
                issues.reject("SUBSECOND_SLEEP_INTERVAL", origin, "The sleep interval cannot be represented in whole seconds.")
                continue
            }
            val durationSeconds = mutableMapOf<Int, Long>()
            var genericSleepSeconds = 0L
            var unmapped = false
            var uncovered = sorted.isEmpty()
            var cursor = record.startEpochMillis
            var wakeCount = 0
            var previousAwakeEnd: Long? = null
            val items = mutableListOf<JsonObject>()
            for (stage in sorted) {
                if (stage.startEpochMillis > cursor) uncovered = true
                cursor = stage.endEpochMillis
                val a = seconds(stage.startEpochMillis, origin, issues)
                val b = seconds(stage.endEpochMillis, origin, issues)
                if (b <= a) {
                    unmapped = true
                    issues.warn("SUBSECOND_SLEEP_STAGE", origin, "A subsecond sleep stage cannot be stored in Mi's whole-second format.")
                    continue
                }
                val code = when (stage.kind) {
                    HealthInboundSleepStageKind.DEEP -> 2
                    HealthInboundSleepStageKind.LIGHT -> 3
                    HealthInboundSleepStageKind.REM -> 4
                    HealthInboundSleepStageKind.AWAKE, HealthInboundSleepStageKind.AWAKE_IN_BED -> 5
                    else -> null
                }
                if (code == null) {
                    // A generic SLEEPING stage confirms sleep duration, but not its depth.
                    if (stage.kind == HealthInboundSleepStageKind.SLEEPING) genericSleepSeconds += b - a
                    unmapped = true
                    previousAwakeEnd = null
                    continue
                }
                durationSeconds[code] = (durationSeconds[code] ?: 0) + (b - a)
                if (code == 5) {
                    if (previousAwakeEnd != a) wakeCount++
                    previousAwakeEnd = b
                } else previousAwakeEnd = null
                items += buildJsonObject { put("start_time", a); put("end_time", b); put("state", code) }
            }
            if (cursor < record.endEpochMillis) uncovered = true
            if (unmapped || uncovered) {
                issues.warn("PARTIAL_SLEEP_STAGES", origin,
                    "Session bounds and known stages are retained. Unclassified/out-of-bed gaps are not changed into light sleep; " +
                        "duration counts only confirmed sleep.")
            }
            val knownSleepSeconds = (2..4).sumOf { durationSeconds[it] ?: 0L } + genericSleepSeconds
            val value = buildJsonObject {
                put("bedtime", start)
                put("wake_up_time", end)
                put("bed_timestamp", start)
                put("out_bed_timestamp", end)
                put("duration", knownSleepSeconds / 60L)
                if (zone.offset % 900 == 0) put("timezone", zone.offset / 900)
                put("sleep_deep_duration", (durationSeconds[2] ?: 0) / 60L)
                put("sleep_light_duration", (durationSeconds[3] ?: 0) / 60L)
                put("sleep_rem_duration", (durationSeconds[4] ?: 0) / 60L)
                put("sleep_awake_duration", (durationSeconds[5] ?: 0) / 60L)
                put("awake_count", wakeCount)
                put("items", JsonArray(items))
                if (unmapped || uncovered) put("is_uncomplete", true)
            }
            // A partial-stage session remains a useful raw import. It is not a
            // complete daily report, even if its surrounding day was fully read.
            rows += raw("sleep", end, value, zone, origin).copy(
                dayAggregationEligible = zone.knownAndStable && !unmapped && !uncovered,
            )
        }
        val unique = removeTargetConflicts(rows, issues)
        val aggregates = if (includeDailyAggregates) sleepDays(unique, coverage, issues) else emptyList()
        return MiHealthImportPlan(HealthInboundMetric.SLEEP, unique + aggregates, issues.distinct(), records.size)
    }

    private data class SourceZone(val offset: Int, val knownAndStable: Boolean)

    private data class RestingHeartRateCandidate(
        val sourceEpochMillis: Long,
        val beatsPerMinute: Long,
        val row: MiHealthImportRow,
    )

    private fun sourceZone(start: Int?, end: Int?, origin: HealthRecordOrigin, issues: MutableList<MiHealthImportIssue>): SourceZone {
        val validStart = start?.takeIf { it in -64_800..64_800 }
        val validEnd = end?.takeIf { it in -64_800..64_800 }
        if (validStart == null || validEnd == null) {
            issues.warn("SOURCE_TIMEZONE_UNAVAILABLE", origin,
                "Raw UTC instants are preserved; UTC is used only as the transport display offset where no source offset exists. Daily " +
                    "aggregation is disabled.")
            return SourceZone(validStart ?: validEnd ?: 0, false)
        }
        if (validStart != validEnd) {
            issues.warn("SOURCE_TIMEZONE_CHANGED", origin,
                "Raw UTC instants and the source start offset are preserved; a timezone/DST transition prevents daily aggregation.")
            return SourceZone(validStart, false)
        }
        if (validStart % 900 != 0) {
            issues.warn("SOURCE_TIMEZONE_NOT_QUARTER_HOUR", origin,
                "The exact outer offset is preserved; Mi's optional 15-minute timezone field cannot express this offset.")
        }
        return SourceZone(validStart, true)
    }

    private fun raw(key: String, time: Long, value: JsonObject, zone: SourceZone, origin: HealthRecordOrigin) =
        MiHealthImportRow(
            MiHealthImportIdentity(MiHealthImportChannel.RAW, MI_HEALTH_CONNECT_IMPORT_SID, key, time),
            value, zone.offset, setOf(origin.stableRecordKey), zone.knownAndStable,
        )

    private fun seconds(ms: Long, origin: HealthRecordOrigin, issues: MutableList<MiHealthImportIssue>): Long {
        if (ms % 1_000L != 0L) issues.warn("SUBSECOND_PRECISION", origin,
            "Mi's timestamp schema stores whole seconds. Subsecond precision is retained only by the original Health Connect record.")
        return ms / 1_000L
    }

    private fun validOrigin(origin: HealthRecordOrigin, sourcePackage: String, issues: MutableList<MiHealthImportIssue>): Boolean {
        if (origin.sourcePackage == sourcePackage && origin.recordId.isNotBlank()) return true
        issues.reject("INVALID_SOURCE", origin, "The record does not have the explicitly selected source and a stable record ID.")
        return false
    }

    internal fun <T> latestOrigins(records: List<T>, origin: (T) -> HealthRecordOrigin, issues: MutableList<MiHealthImportIssue>): List<T> =
        records.groupBy { origin(it).stableRecordKey }.values.mapNotNull { group ->
            val selected = group.maxWith(compareBy<T> { origin(it).lastModifiedEpochMillis }.thenBy { origin(it).clientRecordVersion })
            val selectedOrigin = origin(selected)
            val tied = group.filter {
                origin(it).lastModifiedEpochMillis == selectedOrigin.lastModifiedEpochMillis &&
                    origin(it).clientRecordVersion == selectedOrigin.clientRecordVersion
            }.distinct()
            if (tied.size > 1) {
                issues.reject("CONFLICTING_SOURCE_REVISIONS", selectedOrigin,
                    "The same latest Health Connect record revision has conflicting content. No arbitrary version was selected and no " +
                        "older revision was substituted.")
                return@mapNotNull null
            }
            if (group.size > 1) issues.warn(
                "SOURCE_REVISION_DEDUPLICATED", origin(selected), "Only the latest revision of this source record is planned.")
            selected
        }

    private fun removeTargetConflicts(
        rows: List<MiHealthImportRow>,
        issues: MutableList<MiHealthImportIssue>,
        collisionCode: String = "TARGET_TIME_COLLISION",
        collisionMessage: String = "Different source values share the same Mi source/key/second. No value was chosen or averaged.",
        collisionSeverity: MiHealthImportIssueSeverity = MiHealthImportIssueSeverity.REJECTED,
    ): List<MiHealthImportRow> =
        rows.groupBy { it.identity }.values.mapNotNull { sameTarget ->
            val first = sameTarget.first()
            val sources = sameTarget.flatMap { it.sourceRecordKeys }.toSet()
            if (sameTarget.any { it.canonicalContent != first.canonicalContent || it.zoneOffsetSeconds != first.zoneOffsetSeconds }) {
                issues += MiHealthImportIssue(collisionCode, sources, collisionMessage, collisionSeverity,
                    affectedLocalDays = if (sameTarget.all { it.dayAggregationEligible }) sameTarget.map {
                        (it.identity.time + it.zoneOffsetSeconds).floorDiv(86_400L)
                    }.toSet() else null, affectedTarget = first.identity.takeIf { it.key == "heart_rate" })
                null
            } else first.copy(sourceRecordKeys = sources, dayAggregationEligible = sameTarget.all { it.dayAggregationEligible })
        }.sortedBy { it.identity.time }

    private fun aggregateGroups(rows: List<MiHealthImportRow>, coverage: HealthInboundReadRequest?,
        issues: MutableList<MiHealthImportIssue>, localizeRejections: Boolean = false): List<List<MiHealthImportRow>> {
        val sourceZone = coverage?.sourceCalendarZone
        val rejected = issues.filter { it.severity == MiHealthImportIssueSeverity.REJECTED ||
            it.code == "HEART_RATE_SOURCE_POINT_EXCLUDED" }
        if (rejected.isNotEmpty() && (sourceZone != null || !localizeRejections || rejected.any { it.affectedLocalDays.isNullOrEmpty() })) {
            if (rows.isNotEmpty()) issues += MiHealthImportIssue("REJECTED_SOURCE_PREVENTS_DAILY_REPORT",
                rows.flatMap { it.sourceRecordKeys }.toSet(),
                "Some source values were rejected. Raw valid values remain importable, but a complete daily summary cannot be asserted.",
                MiHealthImportIssueSeverity.WARNING)
            return emptyList()
        }
        val byLocalDay = rows.groupBy { row ->
            val offset = sourceZone?.offsetAt(Instant.fromEpochSeconds(row.identity.time))?.totalSeconds ?: row.zoneOffsetSeconds
            (row.identity.time + offset).floorDiv(SOURCE_DAY_SECONDS)
        }
        val blockedDays = rejected.flatMap { it.affectedLocalDays.orEmpty() }.toSet()
        return byLocalDay.filterKeys { it !in blockedDays }.filter { (epochDay, day) ->
            val sources = day.flatMap { it.sourceRecordKeys }.toSet()
            val first = day.first()
            val range = if (sourceZone != null) sourceZone.sourceCalendarDay(epochDay) else {
                val start = epochDay * SOURCE_DAY_SECONDS - first.zoneOffsetSeconds
                SourceCalendarDay(start, start + SOURCE_DAY_SECONDS)
            }
            val offsetsAgree = if (sourceZone == null) day.all { it.zoneOffsetSeconds == first.zoneOffsetSeconds } else day.all {
                it.identity.time in range.from until range.until &&
                    sourceZone.offsetAt(Instant.fromEpochSeconds(it.identity.time)).totalSeconds == it.zoneOffsetSeconds
            }
            val valid = coverage != null && day.all { it.dayAggregationEligible } && offsetsAgree &&
                range.coveredBy(coverage.fromEpochSeconds, coverage.untilEpochSeconds)
            if (!valid) issues += MiHealthImportIssue("INCOMPLETE_LOCAL_DAY", sources,
                "Raw data is retained; no daily report is generated for a partial query day, incomplete sleep stages, or an uncertain " +
                    "source timezone.",
                MiHealthImportIssueSeverity.WARNING)
            valid
        }.values.toList()
    }

    private fun heartRateDays(rows: List<MiHealthImportRow>, coverage: HealthInboundReadRequest?,
        issues: MutableList<MiHealthImportIssue>): List<MiHealthImportRow> =
        aggregateGroups(rows, coverage, issues, localizeRejections = true).map { day ->
            val bpm = day.map { it.value.getValue("bpm").jsonPrimitive.int }
            val latest = day.maxBy { it.identity.time }
            aggregate(day, "heart_rate", buildJsonObject {
                put("min_hr", bpm.min())
                put("max_hr", bpm.max())
                put("avg_hr", bpm.average().roundToInt())
                put("latest_hr", latest.value)
                // No fabricated abnormal_hr_count or heart-rate zones.
            })
        }

    private fun oxygenSaturationDays(rows: List<MiHealthImportRow>, coverage: HealthInboundReadRequest?,
        issues: MutableList<MiHealthImportIssue>): List<MiHealthImportRow> =
        aggregateGroups(rows, coverage, issues).map { day ->
            val percentages = day.map { it.value.getValue("spo2").jsonPrimitive.int }
            val latest = day.maxBy { it.identity.time }
            // CloudSpo2Report's observed values. No lack_spo2_count is invented:
            // a saturation reading alone does not establish a low-SpO2 event.
            aggregate(day, "spo2", buildJsonObject {
                put("min_spo2", percentages.min())
                put("max_spo2", percentages.max())
                put("avg_spo2", percentages.average().roundToInt())
                put("latest_spo2", latest.value)
            })
        }

    private fun sleepDays(rows: List<MiHealthImportRow>, coverage: HealthInboundReadRequest?,
        issues: MutableList<MiHealthImportIssue>): List<MiHealthImportRow> =
        aggregateGroups(rows, coverage, issues).mapNotNull { day ->
            val ordered = day.sortedBy { it.value.getValue("bedtime").jsonPrimitive.long }
            if (ordered.zipWithNext().any { (a, b) ->
                    a.value.getValue("wake_up_time").jsonPrimitive.long > b.value.getValue("bedtime").jsonPrimitive.long }) {
                issues += MiHealthImportIssue("OVERLAPPING_SLEEP_SESSIONS", day.flatMap { it.sourceRecordKeys }.toSet(),
                    "Raw sessions remain distinct; overlapping sessions cannot form an additive daily report.",
                        MiHealthImportIssueSeverity.WARNING)
                return@mapNotNull null
            }
            aggregate(day, "sleep", buildJsonObject {
                put("total_duration", day.sumOf { it.value.getValue("duration").jsonPrimitive.long })
                for (field in listOf("sleep_deep_duration", "sleep_light_duration", "sleep_rem_duration", "sleep_awake_duration",
                    "awake_count")) {
                    put(field, day.sumOf { it.value.getValue(field).jsonPrimitive.long })
                }
                put("segment_details", JsonArray(ordered.map { row ->
                    JsonObject(row.value.filterKeys { it in setOf("bedtime", "wake_up_time", "timezone", "duration",
                        "sleep_deep_duration", "sleep_light_duration", "sleep_rem_duration", "sleep_awake_duration", "awake_count") })
                }))
                // A sleep score, HRV estimate and algorithm version cannot be
                // reconstructed from HC's stage intervals and are never invented.
            })
        }

    private fun aggregate(day: List<MiHealthImportRow>, key: String, value: JsonObject): MiHealthImportRow {
        val first = day.first()
        return MiHealthImportRow(
            MiHealthImportIdentity(MiHealthImportChannel.DAILY_AGGREGATE, MI_HEALTH_CONNECT_IMPORT_SID,
                key, cloudDayAnchor(first.identity.time, first.zoneOffsetSeconds), "daily_report"),
            value, 0, day.flatMap { it.sourceRecordKeys }.toSet(),
            completeSourceDay = true,
        )
    }

    /**
     * Mi Fitness 3.59.2 FitnessDateUtils.changeTo0TimeIn0TzOneDay(t, offset)
     * uses a GMT+00:00 Calendar, sets (t + offset) * 1000, clears H/M/S, then
     * divides milliseconds by 1000. This is a calendar-date encoding, NOT the
     * absolute instant at local midnight. CloudDailyHelper sets its outer zone
     * offset to zero. Raw sample times and full-day coverage remain real instants.
     */
    private fun cloudDayAnchor(time: Long, offset: Int): Long = (time + offset).floorDiv(86_400L) * 86_400L

    private fun MutableList<MiHealthImportIssue>.warn(code: String, origin: HealthRecordOrigin, message: String) {
        add(MiHealthImportIssue(code, setOf(origin.stableRecordKey), message, MiHealthImportIssueSeverity.WARNING))
    }

    private fun MutableList<MiHealthImportIssue>.reject(code: String, origin: HealthRecordOrigin, message: String) {
        add(MiHealthImportIssue(code, setOf(origin.stableRecordKey), message))
    }
}
