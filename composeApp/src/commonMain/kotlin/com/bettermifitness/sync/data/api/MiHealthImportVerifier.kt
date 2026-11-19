package com.bettermifitness.sync.data.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Stable content for the local ledger; object order and 60 versus 60.0 are immaterial. */
fun canonicalMiHealthImportJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.keys.sorted().joinToString(prefix = "{", postfix = "}") { key ->
        "${JsonPrimitive(key)}:${canonicalMiHealthImportJson(value.getValue(key))}"
    }
    is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonicalMiHealthImportJson(it) }
    is JsonPrimitive -> {
        if (value.isString || value == JsonNull || value.content == "true" || value.content == "false") {
            value.toString()
        } else {
            value.content.toLongOrNull()?.toString() ?: value.doubleOrNull?.let { number ->
                if (number.isFinite() && number >= Long.MIN_VALUE.toDouble() &&
                    number < Long.MAX_VALUE.toDouble() && number == number.toLong().toDouble()) {
                    number.toLong().toString()
                } else number.toString()
            } ?: value.toString()
        }
    }
}

/** Index one completed read once, retaining every candidate for the semantic verifier. */
internal class MiHealthImportObservationIndex(observedRows: List<MiHealthImportCloudRow>) {
    private val byTarget = observedRows.groupBy { it.key to it.time }

    fun atTarget(row: MiHealthImportRow): List<MiHealthImportCloudRow> =
        byTarget[row.identity.key to row.identity.time].orEmpty()

    fun verify(row: MiHealthImportRow): MiHealthImportVerification =
        MiHealthImportVerifier.verify(row, atTarget(row))
}

/**
 * Readback proves cloud persistence of the fields we sent, not an official App
 * chart, daily-stat computation, or retention by a future backend version.
 */
object MiHealthImportVerifier {
    fun verify(
        expectedRow: MiHealthImportRow,
        observedRows: List<MiHealthImportCloudRow>,
    ): MiHealthImportVerification {
        val identity = expectedRow.identity
        val atTarget = observedRows.filter {
            !it.deleted && it.key == identity.key && it.time == identity.time &&
                (identity.channel == MiHealthImportChannel.RAW || it.tag == identity.tag)
        }
        val sameSource = atTarget.filter { it.sid == identity.sid }
        if (sameSource.isEmpty()) {
            return if (atTarget.any { it.sid.isNullOrBlank() }) {
                MiHealthImportVerification(MiHealthImportVerificationStatus.AMBIGUOUS, setOf("sid"))
            } else MiHealthImportVerification(MiHealthImportVerificationStatus.MISSING)
        }
        val distinct = sameSource.distinctBy { canonicalMiHealthImportJson(it.value) to it.zoneOffsetSeconds }
        if (distinct.size != 1) {
            return MiHealthImportVerification(MiHealthImportVerificationStatus.AMBIGUOUS, setOf("duplicate_target"))
        }
        val observed = distinct.single()
        val missing = mutableSetOf<String>()
        compareExpectedFields(expectedRow.value, observed.value, "", missing)
        if (observed.zoneOffsetSeconds != null && observed.zoneOffsetSeconds != expectedRow.zoneOffsetSeconds) {
            missing += "zone_offset"
        }
        return if (missing.isEmpty()) MiHealthImportVerification(MiHealthImportVerificationStatus.VERIFIED)
        else MiHealthImportVerification(MiHealthImportVerificationStatus.MISMATCH, missing)
    }

    private fun compareExpectedFields(
        expected: JsonElement,
        observed: JsonElement?,
        path: String,
        mismatches: MutableSet<String>,
    ) {
        when {
            expected is JsonObject && observed is JsonObject -> expected.forEach { (key, value) ->
                compareExpectedFields(value, observed[key], if (path.isEmpty()) key else "$path.$key", mismatches)
            }
            expected is JsonArray && observed is JsonArray -> {
                if (expected.size != observed.size) mismatches += "$path.length"
                expected.forEachIndexed { index, value ->
                    compareExpectedFields(value, observed.getOrNull(index), "$path[$index]", mismatches)
                }
            }
            observed == null || canonicalMiHealthImportJson(expected) != canonicalMiHealthImportJson(observed) ->
                mismatches += path.ifEmpty { "value" }
        }
    }
}
