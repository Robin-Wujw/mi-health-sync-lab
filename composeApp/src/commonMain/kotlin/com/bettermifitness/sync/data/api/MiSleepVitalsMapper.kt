package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.health.HealthInboundHeartRateRecord
import com.bettermifitness.sync.health.HealthInboundOxygenSaturationRecord
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthInboundSleepStageKind
import com.mifitness.miclient.gps.NativeSleepFileWriter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlin.math.roundToInt

data class SleepVitalPoint(val time: Long, val value: Int, val sourceKey: String, val deviceKey: String? = null)

data class MiSleepVitalsFile(val identity: MiHealthImportIdentity, val start: Long, val end: Long,
    val zoneOffsetSeconds: Int, val heartRate: List<NativeSleepFileWriter.Point>,
    val oxygen: List<NativeSleepFileWriter.Point>) {
    fun encode(referenceTime: Long) = NativeSleepFileWriter.encode(referenceTime, zoneOffsetSeconds / 900,
        start, end, heartRate, oxygen)
}

object MiSleepVitalsMapper {
    fun enrich(base: MiHealthImportPlan, sleeps: List<HealthInboundSleepRecord>, sourcePackage: String,
        heartRate: List<HealthInboundHeartRateRecord>, oxygen: List<HealthInboundOxygenSaturationRecord>,
        breath: List<SleepVitalPoint> = emptyList()): MiHealthImportPlan {
        val issues = base.issues.toMutableList()
        val selected = MiHealthImportMapper.latestOrigins(sleeps.filter { it.origin.sourcePackage == sourcePackage },
            { it.origin }, mutableListOf())
        val sourceIssues = mutableListOf<MiHealthImportIssue>()
        val alignedHearts = MiHealthImportMapper.latestOrigins(heartRate, { it.origin }, sourceIssues).map {
            it.copy(samples = it.samples.filter { sample -> sample.epochMillis % 1_000L == 0L })
        }
        val alignedOxygen = MiHealthImportMapper.latestOrigins(oxygen, { it.origin }, sourceIssues)
            .filter { it.epochMillis % 1_000L == 0L }
        val heartPlan = MiHealthImportMapper.heartRate(alignedHearts, sourcePackage)
        val oxygenPlan = MiHealthImportMapper.oxygenSaturation(alignedOxygen, sourcePackage)
        issues += (heartPlan.issues + oxygenPlan.issues).filter { it.severity == MiHealthImportIssueSeverity.WARNING }
        if (heartRate.any { record -> record.samples.any { it.epochMillis % 1_000L != 0L } } ||
            oxygen.any { it.epochMillis % 1_000L != 0L }) {
            issues += MiHealthImportIssue("SLEEP_VITALS_UNREPRESENTABLE", emptySet(),
                "Subsecond sleep vital observations were excluded without moving their timestamps.", MiHealthImportIssueSeverity.WARNING)
        }
        val hearts = heartPlan.rawRows.map { row -> SleepVitalPoint(row.identity.time,
            row.value.getValue("bpm").jsonPrimitive.int, row.sourceRecordKeys.sorted().joinToString("|")) }
        val oxygens = oxygenPlan.rawRows.map { row -> SleepVitalPoint(row.identity.time,
            row.value.getValue("spo2").jsonPrimitive.int, row.sourceRecordKeys.sorted().joinToString("|")) }
        val files = mutableListOf<MiSleepVitalsFile>()
        val rows = base.rows.map { row ->
            if (row.identity.channel != MiHealthImportChannel.RAW) return@map row
            val sleep = selected.filter { it.origin.stableRecordKey in row.sourceRecordKeys }
                .distinctBy { listOf(it.startEpochMillis, it.endEpochMillis, it.stages) }.singleOrNull() ?: return@map row
            if ((row.value["items"] as? JsonArray).isNullOrEmpty() || row.zoneOffsetSeconds % 900 != 0) return@map row
            fun samples(points: List<SleepVitalPoint>): List<SleepVitalPoint> = points.filter { point ->
                val millis = point.time * 1_000
                millis >= sleep.startEpochMillis && millis < sleep.endEpochMillis &&
                    selected.filter { millis >= it.startEpochMillis && millis < it.endEpochMillis }
                        .map { it.startEpochMillis to it.endEpochMillis }.distinct().size == 1 &&
                    sleep.stages.any { millis >= it.startEpochMillis && millis < it.endEpochMillis && it.kind in asleepKinds }
            }.groupBy { it.time }.values.mapNotNull { sameTime ->
                sameTime.distinctBy { it.value }.singleOrNull()
            }.sortedBy { it.time }
            var heartSamples = samples(hearts)
            val oxygenSamples = samples(oxygens)
            val breathSamples = samples(breath).filter { it.value in 4..60 }
                .takeIf { points -> points.map { it.deviceKey }.distinct().size <= 1 }.orEmpty()
            if (heartSamples.any { it.value !in 1..127 }) {
                issues += issue(row, "SLEEP_VITALS_UNREPRESENTABLE")
                heartSamples = emptyList()
            }
            val value = row.value.toMutableMap()
            fun summary(points: List<SleepVitalPoint>, average: String, minimum: String? = null, maximum: String? = null) {
                if (points.isEmpty()) return
                value[average] = JsonPrimitive(points.map { it.value }.average().roundToInt())
                if (minimum != null) value[minimum] = JsonPrimitive(points.minOf { it.value })
                if (maximum != null) value[maximum] = JsonPrimitive(points.maxOf { it.value })
            }
            if (heartSamples.isNotEmpty() || oxygenSamples.isNotEmpty()) {
                val file = MiSleepVitalsFile(row.identity, row.value.getValue("bedtime").jsonPrimitive.long,
                    row.identity.time, row.zoneOffsetSeconds, heartSamples.map { NativeSleepFileWriter.Point(it.time, it.value) },
                    oxygenSamples.map { NativeSleepFileWriter.Point(it.time, it.value) })
                try {
                    file.encode(row.identity.time)
                    files += file
                    summary(heartSamples, "avg_hr", "min_hr", "max_hr")
                    summary(oxygenSamples, "avg_spo2", "min_spo2", "max_spo2")
                } catch (_: IllegalArgumentException) { issues += issue(row, "SLEEP_VITALS_UNREPRESENTABLE") }
            }
            summary(breathSamples, "avg_breath")
            if (value == row.value) row else row.copy(value = JsonObject(value),
                enrichment = MiHealthImportEnrichment.SLEEP_VITALS)
        }
        if ((sourceIssues + heartPlan.issues + oxygenPlan.issues).any { it.severity == MiHealthImportIssueSeverity.REJECTED }) {
            issues += MiHealthImportIssue("SLEEP_VITALS_SOURCE_EXCLUDED", emptySet(),
                "Conflicting or invalid optional vital samples were excluded.", MiHealthImportIssueSeverity.WARNING)
        }
        return base.copy(rows = rows, issues = issues.distinct(), sleepFiles = files)
    }

    private fun issue(row: MiHealthImportRow, code: String) = MiHealthImportIssue(code, row.sourceRecordKeys,
        "The optional sleep vital stream cannot be represented without changing observations.", MiHealthImportIssueSeverity.WARNING)

    private val asleepKinds = setOf(HealthInboundSleepStageKind.DEEP, HealthInboundSleepStageKind.LIGHT,
        HealthInboundSleepStageKind.REM, HealthInboundSleepStageKind.SLEEPING)
}
