package com.bettermifitness.sync.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiSpO2ReadComparisonTest {
    private val minute = 1_800_000_000L

    @Test
    fun comparesSeventyMatchesAndOneHundredThirtyOneMissingWithoutChangingEither() {
        val matched = (0 until 70).map { row(minute + it * 120L) }
        val missing = (0 until 131).map { row(minute + 20_000L + it * 120L + 1) }
        val sources = matched + missing
        val observed = matched.map { it.cloud() }
        val result = compareMiSpO2Reads(sources, observed, missing.identities())
        assertEquals(201, result.sourceTargets)
        assertEquals(MiSpO2MinuteCohort(70, 70, 0), result.exactMatched)
        assertEquals(MiSpO2MinuteCohort(131, 0, 0), result.missing)
        assertEquals(0, result.otherTargets)
        assertEquals(0, result.missingInnerTimeCandidateTargets)
        assertEquals(0, result.missingSameMinuteCandidateTargets)
        assertEquals(sources, matched + missing)
        assertEquals(observed, matched.map { it.cloud() })
    }

    @Test
    fun sameMinuteSameValueRemainsMissingAndSharedSourceMinuteCountsTargets() {
        val match = row(minute)
        val absent = row(minute + 10)
        val result = compareMiSpO2Reads(listOf(match, absent), listOf(match.cloud()), setOf(absent.identity))
        assertEquals(MiSpO2MinuteCohort(1, 1, 1), result.exactMatched)
        assertEquals(MiSpO2MinuteCohort(1, 0, 1), result.missing)
        assertEquals(1, result.missingSameMinuteCandidateTargets)
        assertEquals(1, result.missingSameValueCandidateTargets)
        assertEquals(0, result.missingInnerTimeCandidateTargets)
        assertEquals(0, result.missingMultipleCandidateTargets)
    }

    @Test
    fun sharedMinuteRequiresTheSameSourceIdentityAndCountsDistinctTargetsOnly() {
        val first = row(minute)
        val differentSource = row(minute + 10).copy(identity = row(minute + 10).identity.copy(sid = "another-source"))
        val result = compareMiSpO2Reads(listOf(first, first, differentSource), emptyList(),
            setOf(first.identity, differentSource.identity))
        assertEquals(2, result.sourceTargets)
        assertEquals(2, result.missing.targets)
        assertEquals(0, result.missing.sharedSourceMinuteTargets)
    }

    @Test
    fun innerTimeCandidatesTakePrecedenceOverSameMinuteValues() {
        val target = row(minute + 10)
        val shifted = target.cloud().copy(time = minute - 120)
        val sameMinuteDifferent = row(minute + 20, 97).cloud()
        val result = compareMiSpO2Reads(listOf(target), listOf(shifted, sameMinuteDifferent), setOf(target.identity))
        assertEquals(1, result.missing.targets)
        assertEquals(1, result.missingInnerTimeCandidateTargets)
        assertEquals(0, result.missingSameMinuteCandidateTargets)
        assertEquals(1, result.missingSameValueCandidateTargets)
        assertEquals(0, result.missingDifferentValueCandidateTargets)
        assertEquals(0, result.missingMultipleCandidateTargets)
    }

    @Test
    fun candidateValueAndMultiplicityCountsArePerTargetAndCanOverlap() {
        val target = row(minute + 10)
        val equal = row(minute + 20).cloud().copy(value = json("""{"time":1800000020,"spo2":98.0}"""))
        val different = row(minute + 30, 96).cloud()
        val invalid = row(minute + 40).cloud().copy(value = json("""{"spo2":"98"}"""))
        val result = compareMiSpO2Reads(listOf(target), listOf(equal, different, invalid), setOf(target.identity))
        assertEquals(1, result.missingSameMinuteCandidateTargets)
        assertEquals(1, result.missingSameValueCandidateTargets)
        assertEquals(1, result.missingDifferentValueCandidateTargets)
        assertEquals(1, result.missingUnusableValueCandidateTargets)
        assertEquals(1, result.missingMultipleCandidateTargets)
        assertEquals(0, result.exactMatched.targets)
    }

    @Test
    fun overlappingReadRowsDoNotCreateMultipleCandidatesOrMultipleTargets() {
        val target = row(minute + 10)
        val near = row(minute + 20).cloud()
        val repeatedSource = target.copy(sourceRecordKeys = setOf("another-local-revision"))
        val result = compareMiSpO2Reads(listOf(target, target, repeatedSource), List(4) { near }, setOf(target.identity))
        assertEquals(1, result.sourceTargets)
        assertEquals(1, result.missing.targets)
        assertEquals(0, result.missing.sharedSourceMinuteTargets)
        assertEquals(1, result.missingSameValueCandidateTargets)
        assertEquals(0, result.missingMultipleCandidateTargets)
    }

    @Test
    fun contaminatedExactMatchesAreOtherEvenWithAStaleMissingClassification() {
        val target = row(minute)
        for (interference in listOf(
            target.cloud().copy(deleted = true),
            target.cloud().copy(sid = "other-source"),
            target.cloud().copy(sid = null),
            target.cloud().copy(sid = ""),
            row(minute, 97).cloud(),
        )) {
            val result = compareMiSpO2Reads(listOf(target), listOf(target.cloud(), interference), setOf(target.identity))
            assertEquals(0, result.exactMatched.targets)
            assertEquals(0, result.missing.targets)
            assertEquals(1, result.otherTargets)
        }
    }

    @Test
    fun exactMatchWinsOverStaleMissingInputAndAllowsServerExtensions() {
        val target = row(minute)
        val result = compareMiSpO2Reads(listOf(target), listOf(target.cloud().copy(value = buildJsonObject {
            target.value.forEach { (key, value) -> put(key, value) }
            put("server_extra", 7)
        })), setOf(target.identity))
        assertEquals(1, result.exactMatched.targets)
        assertEquals(0, result.missing.targets)
        assertEquals(0, result.otherTargets)
    }

    @Test
    fun nearbyUnknownOtherSourcesAndTombstonesAreOnlyIndependentHints() {
        val target = row(minute + 10)
        val near = row(minute + 20).cloud()
        val result = compareMiSpO2Reads(listOf(target), listOf(
            near.copy(sid = "other-source"), near.copy(sid = null), near.copy(deleted = true),
        ), setOf(target.identity))
        assertEquals(1, result.missing.targets)
        assertEquals(1, result.missingOtherSourceCandidateTargets)
        assertEquals(1, result.missingDeletedCandidateTargets)
        assertEquals(0, result.missingSameMinuteCandidateTargets)
        assertEquals(0, result.missingSameValueCandidateTargets)
        assertEquals(0, result.missingDifferentValueCandidateTargets)
        assertEquals(0, result.missingUnusableValueCandidateTargets)
        assertEquals(0, result.missingMultipleCandidateTargets)
    }

    @Test
    fun sourceContentOrOffsetCollisionsNeverChooseAnArbitraryVersion() {
        val original = row(minute)
        for (conflict in listOf(row(minute, 97), original.copy(zoneOffsetSeconds = 0))) {
            for (versions in listOf(listOf(original, conflict), listOf(conflict, original))) {
                val result = compareMiSpO2Reads(versions, listOf(original.cloud()), setOf(original.identity))
                assertEquals(1, result.sourceTargets)
                assertEquals(1, result.otherTargets)
                assertEquals(0, result.exactMatched.targets)
                assertEquals(0, result.missing.targets)
            }
        }
    }

    @Test
    fun sourceNumericFormattingAndJsonOrderingDoNotCreateACollision() {
        val target = row(minute)
        val equivalent = target.copy(value = json("""{"spo2":98.0,"time":1800000000.0}"""))
        val result = compareMiSpO2Reads(listOf(target, equivalent), listOf(target.cloud()), emptySet())
        assertEquals(1, result.sourceTargets)
        assertEquals(1, result.exactMatched.targets)
        assertEquals(0, result.otherTargets)
    }

    @Test
    fun incompleteOrUnqueriedAbsenceDoesNotBecomeMissingOrProduceMissingHints() {
        val target = row(minute + 10)
        val result = compareMiSpO2Reads(listOf(target), listOf(row(minute + 20).cloud()), emptySet())
        assertEquals(0, result.missing.targets)
        assertEquals(1, result.otherTargets)
        assertEquals(0, result.missingSameMinuteCandidateTargets)
        assertEquals(0, result.missingSameValueCandidateTargets)
    }

    @Test
    fun exactOuterTimeWithDifferentInnerTimeIsCountedWithoutVerifying() {
        val target = row(minute)
        val differentInner = row(minute + 1).cloud().copy(time = minute)
        val result = compareMiSpO2Reads(listOf(target), listOf(differentInner), emptySet())
        assertEquals(1, result.exactOuterInnerTimeDifferentTargets)
        assertEquals(1, result.otherTargets)
        assertEquals(0, result.exactMatched.targets)
        assertEquals(0, result.missing.targets)
        assertEquals(0, compareMiSpO2Reads(listOf(target), listOf(differentInner.copy(sid = "other")), emptySet())
            .exactOuterInnerTimeDifferentTargets)
        assertEquals(0, compareMiSpO2Reads(listOf(target), listOf(differentInner.copy(deleted = true)), emptySet())
            .exactOuterInnerTimeDifferentTargets)
    }

    @Test
    fun missingAndInvalidCandidateValuesKeepTargetsAndOnlyCountUnusableHints() {
        val target = row(minute + 10)
        for (value in listOf(json("{}"), json("""{"spo2":null}"""), json("""{"spo2":true}"""),
            json("""{"spo2":"98"}"""), json("""{"spo2":0}"""), json("""{"spo2":101}"""))) {
            val near = row(minute + 20).cloud().copy(value = value)
            val result = compareMiSpO2Reads(listOf(target), listOf(near), setOf(target.identity))
            assertEquals(1, result.sourceTargets)
            assertEquals(1, result.missing.targets)
            assertEquals(1, result.missingUnusableValueCandidateTargets)
            assertEquals(0, result.missingSameValueCandidateTargets)
            assertEquals(0, result.missingDifferentValueCandidateTargets)
        }
        val missingExpectedValue = target.copy(value = buildJsonObject { put("time", target.identity.time) })
        val result = compareMiSpO2Reads(listOf(missingExpectedValue), listOf(row(minute + 20).cloud()), setOf(target.identity))
        assertEquals(1, result.missing.targets)
        assertEquals(1, result.missingUnusableValueCandidateTargets)
    }

    @Test
    fun nonNumericInnerTimeDoesNotManufactureATimestampMatch() {
        val target = row(minute + 10)
        val near = target.cloud().copy(time = minute - 120, value = buildJsonObject {
            put("time", target.identity.time.toString()); put("spo2", 98)
        })
        val result = compareMiSpO2Reads(listOf(target), listOf(near), setOf(target.identity))
        assertEquals(1, result.missing.targets)
        assertEquals(0, result.missingInnerTimeCandidateTargets)
        assertEquals(0, result.missingSameMinuteCandidateTargets)
    }

    @Test
    fun otherKeysAndAggregateSourcesAreOutsideTheSpO2Comparison() {
        val target = row(minute)
        val heartRate = target.copy(identity = target.identity.copy(key = "heart_rate"))
        val daily = target.copy(identity = target.identity.copy(channel = MiHealthImportChannel.DAILY_AGGREGATE, tag = "daily_report"))
        val result = compareMiSpO2Reads(listOf(target, heartRate, daily), listOf(target.cloud().copy(key = "heart_rate")),
            setOf(target.identity, heartRate.identity, daily.identity))
        assertEquals(1, result.sourceTargets)
        assertEquals(1, result.missing.targets)
        assertEquals(0, result.exactMatched.targets)
        assertEquals(0, result.missingSameMinuteCandidateTargets)
    }

    @Test
    fun resultContainsOnlyCountsAndNeverSourceHealthPayloads() {
        val target = row(minute + 10).copy(sourceRecordKeys = setOf("private-source-id"))
        val result = compareMiSpO2Reads(listOf(target), listOf(target.cloud().copy(time = minute)), setOf(target.identity))
        val text = result.toString()
        assertFalse(text.contains("private-source-id"))
        assertFalse(text.contains(target.identity.time.toString()))
        assertFalse(text.contains(MI_HEALTH_CONNECT_IMPORT_SID))
        assertFalse(text.contains("spo2="))
        assertTrue(result.sourceTargets == result.exactMatched.targets + result.missing.targets + result.otherTargets)
        assertEquals(MiSpO2ReadComparison(), compareMiSpO2Reads(emptyList(), emptyList(), emptySet()))
    }

    private fun row(time: Long, value: Int = 98) = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, MI_HEALTH_CONNECT_IMPORT_SID, "spo2", time),
        buildJsonObject { put("time", time); put("spo2", value) }, 28_800, setOf("synthetic:$time"),
    )
    private fun MiHealthImportRow.cloud() = MiHealthImportCloudRow(identity.sid, identity.key,
        identity.time, identity.tag, value, zoneOffsetSeconds = zoneOffsetSeconds)
    private fun List<MiHealthImportRow>.identities() = map { it.identity }.toSet()
    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
