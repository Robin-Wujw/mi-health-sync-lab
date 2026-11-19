package com.bettermifitness.sync.zepp

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlin.time.Instant

data class ZeppImportPlan(val rows: List<MiHealthImportRow>, val excluded: Int)

object MiZeppScoreMapper {
    /** Native item contracts, verified against Mi Fitness 3.59.2. No vitality conversion. */
    fun map(observations: List<ZeppObservation>, stressDisplayTimeZone: TimeZone? = null): ZeppImportPlan {
        val candidates = mutableListOf<MiHealthImportRow>()
        var excluded = 0
        fun row(key: String, points: List<ZeppObservation>, value: JsonObject) {
            val p = points.first()
            // Regional stress events carry absolute times but omit the original timezone.
            // A chosen destination display zone is transport metadata, not new source provenance.
            val stress = key in setOf("stress", "single_stress")
            val zone = p.zoneOffsetSeconds ?: if (stress) {
                stressDisplayTimeZone?.offsetAt(Instant.fromEpochSeconds(p.observedSeconds))?.totalSeconds
            } else null
            if (zone == null || zone !in -43_200..50_400 || zone % 900 != 0) { excluded++; return }
            candidates += MiHealthImportRow(MiHealthImportIdentity(MiHealthImportChannel.RAW, "default", key,
                p.observedSeconds), value, zone, points.map {
                HealthRecordIds.version("zepp", it.metric, it.observedSeconds, it.deviceId, it.sourceScope).toString()
            }.toSet(), dayAggregationEligible = p.zoneOffsetSeconds != null)
        }
        val groups = observations.groupBy {
            val family = when {
                it.metric.startsWith("pai_") -> "pai"
                it.metric.startsWith("weekly_load") || it.metric == "daily_load" -> "training_load"
                else -> it.metric
            }
            listOf(it.observedSeconds, it.zoneOffsetSeconds, it.deviceId, it.sourceScope, family)
        }.mapValues { (_, points) ->
            val latest = points.maxOf { it.revisionSeconds ?: 0 }
            points.filter { (it.revisionSeconds ?: 0) == latest }
        }
        for (group in groups.values) {
            fun one(metric: String): ZeppObservation? = group.filter { it.metric == metric }.distinctBy { it.value }.singleOrNull()
            val pai = listOf("pai_daily", "pai_total", "pai_low_zone", "pai_medium_zone", "pai_high_zone").map(::one)
            if (pai.any { it != null }) {
                if (pai.any { it == null }) excluded++
                else {
                    val points = pai.filterNotNull()
                    row("pai", points, buildJsonObject {
                        put("date_time", points.first().observedSeconds)
                        val fields = listOf("daily_pai", "total_pai", "low_zone_pai", "medium_zone_pai", "high_zone_pai")
                        for ((index, field) in fields.withIndex()) {
                            put(field, points[index].value)
                        }
                    })
                }
            }
            val weekly = one("weekly_load")
            if (weekly != null) {
                val points = group.filter { it.metric.startsWith("weekly_load") || it.metric == "daily_load" }
                row("training_load", points, buildJsonObject {
                    put("date_time", weekly.observedSeconds); put("wtl_sum", weekly.value.toInt())
                    for ((metric, field) in listOf("daily_load" to "current_day_train_load",
                        "weekly_load_min" to "wtl_sum_optimal_min", "weekly_load_max" to "wtl_sum_optimal_max",
                        "weekly_load_overreaching" to "wtl_sum_overreaching")) {
                        one(metric)?.let { put(field, it.value.toInt()) }
                    }
                })
            }
            for (p in group.filter { it.metric == "stress" || it.metric == "single_stress" }) {
                if (p.value % 1 != 0.0 || p.value !in 1.0..100.0) { excluded++; continue }
                row(p.metric, listOf(p), buildJsonObject { put("time", p.observedSeconds); put("stress", p.value.toInt()) })
            }
        }
        val selected = candidates.groupBy { it.identity }.mapNotNull { (_, rows) ->
            // Different device streams do not outrank each other merely because one uploaded later.
            val unique = rows.distinctBy { it.canonicalContent + "|" + it.zoneOffsetSeconds }
            if (unique.size != 1) { excluded++; null } else unique.single()
        }
        return ZeppImportPlan(selected, excluded)
    }
}
