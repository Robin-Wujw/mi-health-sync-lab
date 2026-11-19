package com.bettermifitness.sync.data.parse

import com.bettermifitness.sync.health.HealthDataNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * By-time queries request newest-first. A repeated (key, sid, sample time) is
 * a revision of the same counter row, not another delta to add to the hour.
 * Without a source identity only exact duplicates can be removed safely.
 * This does not choose an authoritative device when different sources overlap.
 */
internal fun newestMiCounterRows(entries: List<RawFitnessEntry>): List<RawFitnessEntry> {
    val identified = mutableSetOf<Triple<String?, String, Long>>()
    val unidentified = mutableSetOf<RawFitnessEntry>()
    return entries.filter { entry ->
        val sid = entry.sid?.takeIf { it.isNotBlank() }
        if (sid == null) {
            unidentified.add(entry)
        } else {
            val payloadTime = try {
                val obj = Json.parseToJsonElement(entry.value) as? JsonObject
                (obj?.get("time") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            } catch (_: Exception) {
                null
            }
            val time = HealthDataNormalizer.toEpochSeconds(payloadTime ?: entry.time)
            identified.add(Triple(entry.key, sid, time))
        }
    }
}
