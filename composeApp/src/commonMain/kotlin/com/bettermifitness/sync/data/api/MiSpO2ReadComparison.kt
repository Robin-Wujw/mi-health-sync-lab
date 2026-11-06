package com.bettermifitness.sync.data.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Counts only; no source identities, timestamps, health values or cloud rows escape. */
data class MiSpO2MinuteCohort(
    val targets: Int = 0,
    val minuteAlignedTargets: Int = 0,
    val sharedSourceMinuteTargets: Int = 0,
)

data class MiSpO2ReadComparison(
    val sourceTargets: Int = 0,
    val exactMatched: MiSpO2MinuteCohort = MiSpO2MinuteCohort(),
    val missing: MiSpO2MinuteCohort = MiSpO2MinuteCohort(),
    val otherTargets: Int = 0,
    val missingInnerTimeCandidateTargets: Int = 0,
    val missingSameMinuteCandidateTargets: Int = 0,
    val missingSameValueCandidateTargets: Int = 0,
    val missingDifferentValueCandidateTargets: Int = 0,
    val missingUnusableValueCandidateTargets: Int = 0,
    val missingMultipleCandidateTargets: Int = 0,
    val missingOtherSourceCandidateTargets: Int = 0,
    val missingDeletedCandidateTargets: Int = 0,
    val exactOuterInnerTimeDifferentTargets: Int = 0,
)

/**
 * Examines existing observations without changing verification or journal state.
 * Only the caller's fully queried MISSING identities can enter the missing cohort.
 * Nearby values are hints, never persistence proof; an absent hint does not prove
 * absence outside the original query bounds (including the end of a minute).
 */
fun compareMiSpO2Reads(
    sourceRows: List<MiHealthImportRow>,
    observations: List<MiHealthImportCloudRow>,
    missingIdentities: Set<MiHealthImportIdentity>,
): MiSpO2ReadComparison {
    val targets = sourceRows.filter { it.identity.channel == MiHealthImportChannel.RAW && it.identity.key == "spo2" }
        .groupBy { it.identity }
    val sourceMinutes = targets.keys.filter { it.time >= 0 }
        .groupingBy { it.sid to it.time.floorDiv(60L) }.eachCount()
    val cloud = observations.filter { it.key == "spo2" }.distinct()
    val byOuterTime = cloud.groupBy { it.time }
    val byMinute = cloud.filter { it.time >= 0 }.groupBy { it.time.floorDiv(60L) }
    val byInnerTime = cloud.mapNotNull { row -> row.value.innerTime()?.let { it to row } }
        .groupBy({ it.first }, { it.second })

    var matched = MiSpO2MinuteCohort()
    var missing = MiSpO2MinuteCohort()
    var other = 0
    var innerCandidates = 0
    var minuteCandidates = 0
    var sameValues = 0
    var differentValues = 0
    var unusableValues = 0
    var multipleCandidates = 0
    var otherSourceCandidates = 0
    var deletedCandidates = 0
    var outerInnerDifferent = 0

    fun MiSpO2MinuteCohort.add(identity: MiHealthImportIdentity) = copy(
        targets = this.targets + 1,
        minuteAlignedTargets = minuteAlignedTargets + if (identity.time % 60L == 0L) 1 else 0,
        sharedSourceMinuteTargets = sharedSourceMinuteTargets +
            if (sourceMinutes.getValue(identity.sid to identity.time.floorDiv(60L)) > 1) 1 else 0,
    )

    for ((identity, versions) in targets) {
        if (identity.time < 0 || identity.sid.isBlank() ||
            versions.distinctBy { it.canonicalContent to it.zoneOffsetSeconds }.size != 1) {
            other++
            continue
        }
        val expected = versions.first()
        val exact = byOuterTime[identity.time].orEmpty()
        if (exact.any { row -> !row.deleted && row.sid == identity.sid &&
                row.value.innerTime()?.let { it != identity.time } == true }) {
            outerInnerDifferent++
        }
        val exactIsUncontested = exact.none { it.deleted || it.sid != identity.sid }
        if (exactIsUncontested && MiHealthImportVerifier.verify(expected, exact).status == MiHealthImportVerificationStatus.VERIFIED) {
            matched = matched.add(identity)
            continue
        }
        // A conflicting exact observation is not an absent target, even if the
        // caller accidentally supplies an outdated missing-identity set.
        if (identity !in missingIdentities || exact.isNotEmpty()) {
            other++
            continue
        }
        missing = missing.add(identity)
        val innerNear = byInnerTime[identity.time].orEmpty().filter { it.time != identity.time }
        val minuteNear = byMinute[identity.time.floorDiv(60L)].orEmpty().filter { it.time != identity.time }
        val near = (innerNear + minuteNear).distinct()
        if (near.any { it.sid.isNullOrBlank() || it.sid != identity.sid }) otherSourceCandidates++
        if (near.any { it.deleted }) deletedCandidates++

        val activeInner = innerNear.filter { !it.deleted && it.sid == identity.sid }
        val candidates = if (activeInner.isNotEmpty()) {
            innerCandidates++
            activeInner
        } else {
            minuteNear.filter { !it.deleted && it.sid == identity.sid }.also {
                if (it.isNotEmpty()) minuteCandidates++
            }
        }
        if (candidates.size > 1) multipleCandidates++
        val expectedValue = expected.value.spo2Number()
        var hasSame = false
        var hasDifferent = false
        var hasUnusable = false
        for (candidate in candidates) {
            val value = candidate.value.spo2Number()
            when {
                expectedValue == null || value == null -> hasUnusable = true
                value == expectedValue -> hasSame = true
                else -> hasDifferent = true
            }
        }
        if (hasSame) sameValues++
        if (hasDifferent) differentValues++
        if (hasUnusable) unusableValues++
    }
    return MiSpO2ReadComparison(
        sourceTargets = targets.size,
        exactMatched = matched,
        missing = missing,
        otherTargets = other,
        missingInnerTimeCandidateTargets = innerCandidates,
        missingSameMinuteCandidateTargets = minuteCandidates,
        missingSameValueCandidateTargets = sameValues,
        missingDifferentValueCandidateTargets = differentValues,
        missingUnusableValueCandidateTargets = unusableValues,
        missingMultipleCandidateTargets = multipleCandidates,
        missingOtherSourceCandidateTargets = otherSourceCandidates,
        missingDeletedCandidateTargets = deletedCandidates,
        exactOuterInnerTimeDifferentTargets = outerInnerDifferent,
    )
}

private fun JsonObject.innerTime(): Long? = (this["time"] as? JsonPrimitive)
    ?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }

private fun JsonObject.spo2Number(): Double? = (this["spo2"] as? JsonPrimitive)
    ?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 && it <= 100.0 }
