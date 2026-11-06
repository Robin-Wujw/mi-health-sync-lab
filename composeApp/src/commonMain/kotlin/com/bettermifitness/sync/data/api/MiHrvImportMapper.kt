package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.health.HealthInboundHrvRecord
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthInboundSleepStageKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * Explicit experimental mapping of HC RMSSD samples to a sleep-interval summary.
 *
 * Mi Fitness 3.59.2 SleepSegmentReport serializes avg_hrv/min_hrv/max_hrv as
 * nullable integers. Its hrvDetailRecords is transient; CloudSleepSegment does
 * not expose HRV. The native HRV algorithm has not been established. Therefore
 * this is NOT an RMSSD-to-native-algorithm conversion, a reconstructed overnight
 * beat-interval statistic, a baseline calculation, or a detailed HRV curve.
 *
 * The original HC samples retain their algorithm, precision, identity and time.
 * Only a user's explicit opt-in permits these three rounded summary fields.
 */
object MiHrvImportMapper {
    fun sleepSummary(
        hrvRecords: List<HealthInboundHrvRecord>,
        sleepRecords: List<HealthInboundSleepRecord>,
        sourcePackage: String,
        experimentalRmssdMappingEnabled: Boolean = false,
    ): MiHealthImportPlan {
        check(experimentalRmssdMappingEnabled) {
            "Enable the experimental sleep-interval RMSSD summary mapping before importing HRV."
        }
        require(sourcePackage.isNotBlank())
        if (hrvRecords.isEmpty()) return MiHealthImportPlan(HealthInboundMetric.HRV, emptyList(), emptyList(), 0)

        val issues = mutableListOf<MiHealthImportIssue>()
        val samples = latestHrvSamples(hrvRecords, sourcePackage, issues)
        val sleeps = MiHealthImportMapper.latestOrigins(
            sleepRecords.filter { it.origin.sourcePackage == sourcePackage && it.origin.recordId.isNotBlank() },
            { it.origin }, issues,
        )
            .filter { it.startEpochMillis >= 0L && it.endEpochMillis > it.startEpochMillis }
        val bySleep = linkedMapOf<String, MutableList<SummarySample>>()
        // Equivalent HC sessions can have different record IDs. They encode the
        // same destination sleep and must not make every RMSSD point ambiguous.
        val sleepGroups = sleeps.groupBy { sleep ->
            listOf(sleep.startEpochMillis, sleep.endEpochMillis,
                sleep.startZoneOffsetSeconds, sleep.endZoneOffsetSeconds,
                sleep.stages.sortedWith(compareBy({ it.startEpochMillis }, { it.endEpochMillis }, { it.kind.name })))
        }.values.toList()

        for (sample in samples) {
            // An instantaneous HC summary may be timestamped exactly at waking.
            // It is admitted only when there is a unique matching sleep interval;
            // the phase/measurement-window uncertainty is reported below.
            // Prefer the half-open interval at adjoining sessions' boundary.
            // A wake-time sample is admitted only if no interval contains it.
            val inside = sleepGroups.filter { group -> group.first().let {
                sample.epochMillis >= it.startEpochMillis && sample.epochMillis < it.endEpochMillis
            } }
            val matches = inside.ifEmpty {
                sleepGroups.filter { sample.epochMillis == it.first().endEpochMillis }
            }
            if (matches.size != 1) {
                issues += issue(
                    if (matches.isEmpty()) "HRV_NO_MATCHING_SLEEP" else "HRV_AMBIGUOUS_SLEEP",
                    sample.sourceKeys,
                    if (matches.isEmpty()) "No selected-source sleep interval contains this RMSSD sample. It is outside this sleep-only mapping and was excluded without changing its timestamp or inventing a sleep session. Other valid sleep summaries remain importable."
                    else "More than one sleep interval contains this RMSSD sample. It was not assigned to an arbitrary session.",
                    // HC can legitimately contain daytime RMSSD points, and a
                    // query boundary may not include a corresponding sleep.
                    // These exclusions are not malformed source measurements.
                    MiHealthImportIssueSeverity.WARNING,
                )
                continue
            }
            val matchingSleeps = matches.single()
            val sleep = matchingSleeps.first()
            val phases = sleep.stages.filter {
                sample.epochMillis >= it.startEpochMillis && sample.epochMillis < it.endEpochMillis
            }
            if (phases.any { it.kind in awakeKinds }) {
                issues += issue("HRV_AWAKE_SAMPLE_EXCLUDED", sample.sourceKeys,
                    "This RMSSD sample is timestamped in an awake or out-of-bed phase and was excluded from the sleep-interval summary.",
                    MiHealthImportIssueSeverity.WARNING)
                continue
            }
            if (phases.isEmpty() || phases.any { it.kind == HealthInboundSleepStageKind.UNKNOWN }) {
                issues += issue("HRV_SLEEP_RANGE_ASSUMPTION", sample.sourceKeys,
                    "This sample falls in the sleep interval, but no confirmed sleep phase covers its timestamp. The summary uses the interval association only; it does not assert that the measurement window was fully asleep.",
                    MiHealthImportIssueSeverity.WARNING)
            }
            for (equivalent in matchingSleeps) {
                bySleep.getOrPut(equivalent.origin.stableRecordKey) { mutableListOf() } += sample
            }
        }

        val usedSleeps = sleeps.filter { it.origin.stableRecordKey in bySleep }
        // Reuse the established sleep encoder/validation without changing its
        // timeline or synthesizing daily reports. Only represented sessions can
        // carry an experimental summary.
        val base = MiHealthImportMapper.sleep(usedSleeps, sourcePackage, includeDailyAggregates = false)
        issues += base.issues
        val enriched = base.rawRows.mapNotNull { row ->
            val matched = row.sourceRecordKeys.flatMap { bySleep[it].orEmpty() }
                .distinctBy { it.epochMillis to it.rmssdMillis }
            if (matched.isEmpty()) return@mapNotNull null
            val values = matched.map { it.rmssdMillis }
            val average = values.average()
            val minimum = values.min()
            val maximum = values.max()
            val avgInt = average.roundToInt()
            val minInt = minimum.roundToInt()
            val maxInt = maximum.roundToInt()
            val sourceKeys = row.sourceRecordKeys + matched.flatMap { it.sourceKeys }
            if (minInt <= 0) {
                issues += issue("UNREPRESENTABLE_HRV_SUMMARY", sourceKeys,
                    "Mi stores positive whole-millisecond HRV summaries. A source value would round to zero, so the summary was not written.")
                return@mapNotNull null
            }
            issues += issue("RMSSD_EXPERIMENTAL_MAPPING", sourceKeys,
                "Experimental sleep-interval RMSSD sample summary: avg_hrv is the arithmetic mean of the selected HC RMSSD samples; min_hrv and max_hrv are sample extrema. Mi's native algorithm is unconfirmed, and HC point records do not establish complete overnight sampling. No equivalent native HRV, baseline, score or curve is claimed.",
                MiHealthImportIssueSeverity.WARNING)
            if (average != avgInt.toDouble() || minimum != minInt.toDouble() || maximum != maxInt.toDouble()) {
                issues += issue("HRV_INTEGER_ROUNDING", sourceKeys,
                    "Mi's SleepSegmentReport stores HRV summaries as whole milliseconds. These summary values are rounded to the nearest integer; original RMSSD precision remains in Health Connect.",
                    MiHealthImportIssueSeverity.WARNING)
            }
            row.copy(
                value = JsonObject(row.value + mapOf(
                    "avg_hrv" to JsonPrimitive(avgInt),
                    "min_hrv" to JsonPrimitive(minInt),
                    "max_hrv" to JsonPrimitive(maxInt),
                )),
                sourceRecordKeys = sourceKeys,
                dayAggregationEligible = false,
                enrichment = MiHealthImportEnrichment.SLEEP_HRV,
            )
        }
        return MiHealthImportPlan(HealthInboundMetric.HRV, enriched, issues.distinct(), hrvRecords.size)
    }

    private fun latestHrvSamples(
        records: List<HealthInboundHrvRecord>,
        sourcePackage: String,
        issues: MutableList<MiHealthImportIssue>,
    ): List<SummarySample> {
        val validOrigins = records.filter { record ->
            val valid = record.origin.sourcePackage == sourcePackage && record.origin.recordId.isNotBlank()
            if (!valid) issues += issue("INVALID_SOURCE", setOf(record.origin.stableRecordKey),
                "The RMSSD record does not have the selected source and a stable Health Connect record ID.")
            valid
        }
        val latest = validOrigins.groupBy { it.origin.stableRecordKey }.values.mapNotNull { revisions ->
            val newest = revisions.maxWith(compareBy<HealthInboundHrvRecord> { it.origin.lastModifiedEpochMillis }
                .thenBy { it.origin.clientRecordVersion })
            val tied = revisions.filter { it.origin.lastModifiedEpochMillis == newest.origin.lastModifiedEpochMillis &&
                it.origin.clientRecordVersion == newest.origin.clientRecordVersion }
            if (tied.map { Triple(it.epochMillis, it.rmssdMillis, it.zoneOffsetSeconds) }.distinct().size > 1) {
                issues += issue("CONFLICTING_HRV_REVISIONS", setOf(newest.origin.stableRecordKey),
                    "The same HC record revision has conflicting RMSSD values or times. No arbitrary value was selected.")
                null
            } else if (newest.epochMillis < 0L || !newest.rmssdMillis.isFinite() ||
                newest.rmssdMillis !in 1.0..200.0) {
                issues += issue("INVALID_HRV_SAMPLE", setOf(newest.origin.stableRecordKey),
                    "The latest RMSSD record has an invalid time or falls outside Health Connect's documented 1–200 ms range. It was not clamped or replaced with an older revision.")
                null
            } else newest
        }
        return latest.groupBy { it.epochMillis }.values.mapNotNull { sameInstant ->
            val sourceKeys = sameInstant.map { it.origin.stableRecordKey }.toSet()
            if (sameInstant.map { it.rmssdMillis }.distinct().size > 1) {
                issues += issue("HRV_TIME_COLLISION", sourceKeys,
                    "Different RMSSD values share the same source and timestamp. They were not averaged into a new measurement.")
                null
            } else {
                if (sameInstant.size > 1) issues += issue("HRV_DUPLICATE_SAMPLE", sourceKeys,
                    "Identical source/time/value records were counted once so duplicated HC entries cannot bias the sample mean.",
                    MiHealthImportIssueSeverity.WARNING)
                SummarySample(sameInstant.first().epochMillis, sameInstant.first().rmssdMillis, sourceKeys)
            }
        }.sortedBy { it.epochMillis }
    }

    private data class SummarySample(val epochMillis: Long, val rmssdMillis: Double, val sourceKeys: Set<String>)

    private val awakeKinds = setOf(
        HealthInboundSleepStageKind.AWAKE,
        HealthInboundSleepStageKind.AWAKE_IN_BED,
        HealthInboundSleepStageKind.OUT_OF_BED,
    )

    private fun issue(
        code: String,
        sources: Set<String>,
        message: String,
        severity: MiHealthImportIssueSeverity = MiHealthImportIssueSeverity.REJECTED,
    ) = MiHealthImportIssue(code, sources, message, severity)
}
