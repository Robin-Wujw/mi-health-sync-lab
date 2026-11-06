package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.api.MiHealthImportIssueSeverity
import com.bettermifitness.sync.data.api.MiHealthImportMapper
import com.bettermifitness.sync.data.api.MiHealthImportPlan
import com.bettermifitness.sync.data.api.MiHealthImportStage
import com.bettermifitness.sync.data.api.MiHrvImportMapper
import com.bettermifitness.sync.data.api.MiPendingImportProbeResult
import com.bettermifitness.sync.data.api.summarizeMiHealthImportIssues
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthMetricReader

/** The same source selection and mapping used by normal sync and device tests. */
internal suspend fun prepareMiHealthImportPlan(
    metric: HealthInboundMetric,
    reader: HealthMetricReader,
    request: HealthInboundReadRequest,
    sourcePackage: String,
): MiHealthImportPlan = when (metric) {
    HealthInboundMetric.HEART_RATE -> MiHealthImportMapper.heartRate(
        reader.readHeartRate(request), sourcePackage, includeDailyAggregates = true, coverage = request)
    HealthInboundMetric.RESTING_HEART_RATE -> {
        val selected = reader.readRestingHeartRate(request)
        // RHR identifies a calendar date. Include its earlier observations even
        // when the rolling range starts halfway through that date.
        fun date(record: com.bettermifitness.sync.health.HealthInboundRestingHeartRateRecord): Long? {
            val offset = record.zoneOffsetSeconds?.takeIf { it in -64_800..64_800 } ?: return null
            if (record.epochMillis < 0) return null
            return (record.epochMillis / 1_000 + offset).floorDiv(86_400) * 86_400
        }
        val dates = selected.mapNotNull(::date).toSet()
        val starts = selected.mapNotNull { record -> date(record)?.minus(record.zoneOffsetSeconds!!.toLong()) }
        val from = minOf(request.fromEpochSeconds, starts.minOrNull() ?: request.fromEpochSeconds).coerceAtLeast(0)
        val records = if (from < request.fromEpochSeconds) {
            reader.readRestingHeartRate(request.copy(fromEpochSeconds = from))
                .filter { date(it) == null || date(it) in dates }
        } else selected
        MiHealthImportMapper.restingHeartRate(records, sourcePackage, includeDailyAggregates = true, coverage = request)
    }
    HealthInboundMetric.SLEEP -> MiHealthImportMapper.sleep(
        reader.readSleep(request), sourcePackage, includeDailyAggregates = true, coverage = request)
    HealthInboundMetric.OXYGEN_SATURATION -> MiHealthImportMapper.oxygenSaturation(
        reader.readOxygenSaturation(request), sourcePackage, includeDailyAggregates = true, coverage = request)
    HealthInboundMetric.HRV -> {
        val sleeps = reader.readSleep(request)
        // The destination identity represents a whole sleep session. Reading
        // just the selected day's tail would create a different HRV summary for
        // the same target when the user changes between 1, 7 and 30 days.
        val from = minOf(request.fromEpochSeconds,
            sleeps.minOfOrNull { it.startEpochMillis / 1_000L } ?: request.fromEpochSeconds)
        val until = maxOf(request.untilEpochSeconds,
            sleeps.maxOfOrNull { it.endEpochMillis / 1_000L + 1L } ?: request.untilEpochSeconds)
        val samples = reader.readHrv(request.copy(fromEpochSeconds = from, untilEpochSeconds = until))
        MiHrvImportMapper.sleepSummary(samples, sleeps, sourcePackage, experimentalRmssdMappingEnabled = true)
    }
}

/** Counts only. This object does not contain the source plan or a cloud payload. */
data class MiHealthLiveInspection(
    val diagnostic: MiHealthImportDiagnostic,
    val pendingProbe: MiPendingImportProbeResult,
)

fun MiHealthLiveInspection.copyableText(appVersion: String): String =
    "Mi Health Sync Lab live inspection v1\ninspect_only=true\nhealth_data_writes_attempted=0\n" +
        diagnostic.copy(pendingProbe = pendingProbe).copyableText(appVersion)

internal fun MiHealthImportPlan.inspectionDiagnostic(): MiHealthImportDiagnostic {
    val rejected = issues.filter { it.severity == MiHealthImportIssueSeverity.REJECTED }
    val rejectedSources = rejected.flatMap { it.sourceRecordKeys }.distinct().size +
        rejected.count { it.sourceRecordKeys.isEmpty() }
    val targets = rows.distinctBy { it.identity }
    val raw = targets.count { it.identity.channel == MiHealthImportChannel.RAW }
    val daily = targets.size - raw
    return MiHealthImportDiagnostic(
        metricKey = metric.metricKey, stage = MiHealthImportStage.PREPARE,
        inputRecords = inputRecordCount, sourceRawRows = rawRows.size,
        plannedRawRows = raw, plannedDailyRows = daily,
        notAttemptedRawRows = raw, notAttemptedDailyRows = daily,
        sourceRejectedRecords = rejectedSources, affectedRows = rejectedSources,
        failureReason = if (rejected.isNotEmpty()) MiHealthImportFailureReason.SOURCE_REJECTED.name else null,
        issueCounts = summarizeMiHealthImportIssues(issues),
    )
}
