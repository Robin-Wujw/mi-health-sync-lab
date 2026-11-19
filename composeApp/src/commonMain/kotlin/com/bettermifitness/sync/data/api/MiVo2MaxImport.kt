package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.InboundVo2Max
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

internal fun prepareVo2MaxImport(source: List<InboundVo2Max>): MiHealthImportPlan {
    val issues = mutableListOf<MiHealthImportIssue>()
    val rows = source.mapNotNull { record ->
        val zone = record.zoneOffsetSeconds
        val invalidTimeZone = zone == null || zone !in -43_200..50_400 || zone % 900 != 0
        if (record.epochSeconds <= 0 || invalidTimeZone ||
            !record.mlPerKgMinute.isFinite() || record.mlPerKgMinute !in 1.0..100.0) {
            issues += MiHealthImportIssue("VO2MAX_INVALID_SOURCE", setOf(record.origin.stableRecordKey),
                "VO2max requires a valid value, source time and source timezone.")
            return@mapNotNull null
        }
        MiHealthImportRow(MiHealthImportIdentity(MiHealthImportChannel.RAW, "default", "vo2_max", record.epochSeconds),
            buildJsonObject { put("time", record.epochSeconds); put("vo2_max", record.mlPerKgMinute.roundToInt()) },
            checkNotNull(zone), setOf(record.origin.stableRecordKey))
    }
    val grouped = rows.groupBy { it.identity }
    val conflicting = grouped.filterValues { it.map { r -> r.canonicalContent }.distinct().size > 1 }.keys
    conflicting.forEach { identity -> issues += MiHealthImportIssue("VO2MAX_SOURCE_COLLISION",
        grouped.getValue(identity).flatMap { it.sourceRecordKeys }.toSet(), "Conflicting VO2max records share one destination time.") }
    return MiHealthImportPlan(HealthInboundMetric.VO2_MAX,
        rows.filter { it.identity !in conflicting }.distinctBy { it.identity }, issues, source.size)
}
