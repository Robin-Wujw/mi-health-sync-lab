package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.WeightMeasurement
import kotlin.math.abs

/** A plan for one measurement, never a merge of unrelated daily measurements. */
data class BodySyncPlan(
    val toHealth: WeightMeasurement? = null,
    val toMi: WeightMeasurement? = null,
)

class BodySyncConflictException(val fields: Set<String>) :
    IllegalStateException("Conflicting values for the same measurement: " + fields.joinToString())

/** Supported HC fields only. Muscle mass is deliberately distinct from lean mass. */
fun bodyComponentValues(w: WeightMeasurement): Map<String, Double?> = linkedMapOf(
    "body_fat_rate" to w.bodyFatPercent,
    "bone_mass" to w.boneMassKg,
    "body_moisture_mass" to (w.bodyWaterMassKg ?: w.bodyWaterPercent?.let { w.weightKg * it / 100.0 }),
    "fat_free_body" to w.leanBodyMassKg,
    "basal_metabolism" to w.basalMetabolismKcal,
)

fun bodyExportFingerprint(w: WeightMeasurement): String = HealthRecordIds.version(
    w.timestamp, w.weightKg, w.tzIn15Min,
    w.bodyFatPercent, w.boneMassKg, w.bodyWaterMassKg, w.bodyWaterPercent,
    w.leanBodyMassKg, w.basalMetabolismKcal,
).toString()

private fun fillMissing(base: WeightMeasurement, extra: WeightMeasurement) = base.copy(
    bodyFatPercent = base.bodyFatPercent ?: extra.bodyFatPercent,
    boneMassKg = base.boneMassKg ?: extra.boneMassKg,
    bodyWaterMassKg = base.bodyWaterMassKg ?: extra.bodyWaterMassKg ?: extra.bodyWaterPercent?.let { extra.weightKg * it / 100.0 },
    leanBodyMassKg = base.leanBodyMassKg ?: extra.leanBodyMassKg,
    basalMetabolismKcal = base.basalMetabolismKcal ?: extra.basalMetabolismKcal,
)

/**
 * Latest measurement wins. Enrichment is allowed only at the identical measured
 * second and matching weight. An optional field arriving later is not discarded
 * just because weight is unchanged. Conflicting same-time values need resolution.
 */
fun planBodySync(
    mi: WeightMeasurement?,
    hc: WeightMeasurement?,
    allowBodyImport: Boolean,
): BodySyncPlan {
    if (mi == null) return BodySyncPlan(toMi = hc)
    if (hc == null) return BodySyncPlan(toHealth = mi)
    val weightMatches = abs(mi.weightKg - hc.weightKg) < 0.05
    if (mi.timestamp == hc.timestamp) {
        if (!weightMatches) throw BodySyncConflictException(setOf("weight"))
        val miFields = bodyComponentValues(mi)
        val hcFields = bodyComponentValues(hc)
        if (allowBodyImport) {
            val conflicts = miFields.keys.filterTo(linkedSetOf()) { key ->
                val a = miFields[key]
                val b = hcFields[key]
                a != null && b != null &&
                    abs(a - b) > if (key == "basal_metabolism") 1.0 else 0.050001
            }
            if (conflicts.isNotEmpty()) throw BodySyncConflictException(conflicts)
        }
        val healthNeedsFields = miFields.any { (key, value) ->
            value != null && hcFields[key] == null
        }
        val miNeedsFields = allowBodyImport && hcFields.any { (key, value) ->
            value != null && miFields[key] == null
        }
        return BodySyncPlan(
            toHealth = if (healthNeedsFields) fillMissing(hc, mi) else null,
            toMi = if (miNeedsFields) fillMissing(mi, hc) else null,
        )
    }
    // Preserve the upstream anti-echo tolerance, but never borrow composition
    // values across these different timestamps.
    if (weightMatches && abs(mi.timestamp - hc.timestamp) < 60) return BodySyncPlan()
    return if (hc.timestamp > mi.timestamp) BodySyncPlan(toMi = hc)
    else BodySyncPlan(toHealth = mi)
}
