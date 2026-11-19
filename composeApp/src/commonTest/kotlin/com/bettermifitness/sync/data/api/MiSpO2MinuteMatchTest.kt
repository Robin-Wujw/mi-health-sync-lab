package com.bettermifitness.sync.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiSpO2MinuteMatchTest {
    private val minute = 1_800_000_000L

    @Test
    fun uniqueMinuteMatchRequiresBothNormalizedTimesAndKeepsSourceUnchanged() {
        val source = row(minute + 23)
        val cloud = row(minute).cloud()
        val policy = MiSpO2MinuteMatchPolicy(listOf(source))
        assertEquals(cloud, policy.find(source, listOf(cloud)))
        assertEquals(cloud, policy.find(source, listOf(cloud, cloud)))
        assertEquals(minute + 23, source.identity.time)
        assertEquals(json("""{"time":1800000023,"spo2":98}"""), source.value)
        assertEquals(row(minute).identity, cloud.importIdentity())
        assertEquals(row(minute).identity, observedMinuteIdentity(source))
    }

    @Test
    fun alternateIdentityHelperOnlyPermitsNonalignedRawDefaultSpO2() {
        val source = row(minute + 23)
        for (unsupported in listOf(row(minute), row(-1),
            source.copy(identity = source.identity.copy(key = "heart_rate")),
            source.copy(identity = source.identity.copy(channel = MiHealthImportChannel.DAILY_AGGREGATE)),
            source.copy(identity = source.identity.copy(sid = "other")),
            source.copy(identity = source.identity.copy(tag = "daily_report")))) {
            assertNull(observedMinuteIdentity(unsupported))
        }
    }

    @Test
    fun fieldComparisonAllowsEquivalentNumbersAndServerExtensionsButNotChangedValues() {
        val source = row(minute + 23)
        val cloud = row(minute).cloud().copy(value = json("""{"time":1800000000.0,"spo2":98.0,"extension":true}"""))
        val policy = MiSpO2MinuteMatchPolicy(listOf(source))
        assertEquals(cloud, policy.find(source, listOf(cloud)))
        for (value in listOf(json("""{"time":1800000000,"spo2":97}"""),
            json("""{"time":1800000000,"spo2":"98"}"""), json("""{"time":1800000000}"""))) {
            assertNull(policy.find(source, listOf(cloud.copy(value = value))))
        }
    }

    @Test
    fun nearestTimeOrSameValueAloneNeverMatches() {
        val source = row(minute + 23)
        val policy = MiSpO2MinuteMatchPolicy(listOf(source))
        for (cloud in listOf(
            row(minute + 1).cloud(), row(minute - 60).cloud(), row(minute + 60).cloud(),
            source.cloud().copy(time = minute), row(minute).cloud().copy(time = minute + 1),
            row(minute).cloud().copy(value = json("""{"time":"1800000000","spo2":98}""")),
        )) assertNull(policy.find(source, listOf(cloud)))
    }

    @Test
    fun exactIdentityObservationsAlwaysBlockTheMinuteFallback() {
        val source = row(minute + 23)
        val floor = row(minute).cloud()
        val policy = MiSpO2MinuteMatchPolicy(listOf(source))
        for (exact in listOf(source.cloud(), source.cloud().copy(deleted = true), source.cloud().copy(sid = null),
            source.cloud().copy(sid = "other"), source.cloud().copy(value = json("{}")))) {
            assertNull(policy.find(source, listOf(floor, exact)))
        }
    }

    @Test
    fun everyAdditionalCloudIdentityValueOrTombstoneInTheMinuteBlocks() {
        val source = row(minute + 23)
        val floor = row(minute).cloud()
        val policy = MiSpO2MinuteMatchPolicy(listOf(source))
        for (extra in listOf(
            floor.copy(sid = "other"), floor.copy(sid = null), floor.copy(sid = ""), floor.copy(deleted = true),
            row(minute + 40).cloud(), floor.copy(value = json("""{"time":1800000000,"spo2":97}""")),
            floor.copy(zoneOffsetSeconds = 0), floor.copy(tag = "daily_report"),
        )) {
            assertNull(policy.find(source, listOf(extra)))
            assertNull(policy.find(source, listOf(floor, extra)))
            assertTrue(policy.hasMinuteObservation(source, listOf(extra)))
        }
    }

    @Test
    fun missingOrConflictingCloudOffsetCannotEstablishTheMinuteMatch() {
        val source = row(minute + 23)
        val policy = MiSpO2MinuteMatchPolicy(listOf(source))
        for (offset in listOf<Int?>(null, 0, -28_800)) {
            assertNull(policy.find(source, listOf(row(minute).cloud().copy(zoneOffsetSeconds = offset))))
        }
    }

    @Test
    fun multipleOriginalTargetsWithinOneMinuteBlockEvenWhenValuesAreEqual() {
        val first = row(minute + 23)
        for (second in listOf(row(minute + 24), row(minute), row(minute + 24, 97),
            row(minute + 24).copy(identity = row(minute + 24).identity.copy(sid = "another-source")))) {
            val policy = MiSpO2MinuteMatchPolicy(listOf(first, second))
            assertEquals(setOf(first.identity, second.identity), policy.collisions)
            assertNull(policy.find(first, listOf(row(minute).cloud())))
        }
    }

    @Test
    fun duplicateVersionsCannotHideUnreliableSourceMetadataOrContentCollisions() {
        val source = row(minute + 23)
        for (changed in listOf(
            source.copy(value = json("""{"time":1800000023,"spo2":97}""")),
            source.copy(zoneOffsetSeconds = 0), source.copy(dayAggregationEligible = false),
            source.copy(sourceRecordKeys = emptySet()), source.copy(enrichment = MiHealthImportEnrichment.SLEEP_HRV),
        )) {
            val policy = MiSpO2MinuteMatchPolicy(listOf(source, changed))
            assertNull(policy.find(source, listOf(row(minute).cloud())))
        }
        val duplicate = source.copy(sourceRecordKeys = setOf("second-valid-local-id"))
        val policy = MiSpO2MinuteMatchPolicy(listOf(source, duplicate))
        assertTrue(policy.collisions.isEmpty())
        assertEquals(row(minute).cloud(), policy.find(source, listOf(row(minute).cloud())))
    }

    @Test
    fun sourceMustBeKnownToThePolicyAndMatchItsCurrentPayload() {
        val source = row(minute + 23)
        val cloud = row(minute).cloud()
        assertNull(MiSpO2MinuteMatchPolicy(emptyList()).find(source, listOf(cloud)))
        val policy = MiSpO2MinuteMatchPolicy(listOf(source))
        assertNull(policy.find(source.copy(value = json("""{"time":1800000023,"spo2":97}""")), listOf(cloud)))
        assertNull(policy.find(source.copy(zoneOffsetSeconds = 0), listOf(cloud)))
    }

    @Test
    fun onlyReliableRawSpO2WithOriginalInnerTimeAndValidWholePercentageCanUseFallback() {
        val source = row(minute + 23)
        val invalid = listOf(
            source.copy(identity = source.identity.copy(key = "heart_rate")),
            source.copy(identity = source.identity.copy(channel = MiHealthImportChannel.DAILY_AGGREGATE)),
            source.copy(identity = source.identity.copy(sid = "other")),
            source.copy(identity = source.identity.copy(tag = "daily_report")),
            source.copy(dayAggregationEligible = false), source.copy(zoneOffsetSeconds = 64_801),
            source.copy(sourceRecordKeys = emptySet()), source.copy(enrichment = MiHealthImportEnrichment.SLEEP_HRV),
            source.copy(value = json("""{"time":1800000022,"spo2":98}""")),
            source.copy(value = json("""{"time":"1800000023","spo2":98}""")),
            source.copy(value = json("""{"time":1800000023,"spo2":98,"unexpected":1}""")),
            source.copy(value = json("""{"time":1800000023,"spo2":"98"}""")),
            source.copy(value = json("""{"time":1800000023,"spo2":98.5}""")),
            source.copy(value = json("""{"time":1800000023,"spo2":0}""")),
            source.copy(value = json("""{"time":1800000023,"spo2":101}""")),
        )
        for (target in invalid) assertNull(MiSpO2MinuteMatchPolicy(listOf(target)).find(target, listOf(row(minute).cloud())))
        assertNull(MiSpO2MinuteMatchPolicy(listOf(row(minute))).find(row(minute), listOf(row(minute).cloud())))
        assertNull(MiSpO2MinuteMatchPolicy(listOf(row(-1))).find(row(-1), listOf(row(-60).cloud())))
    }

    @Test
    fun otherMetricsAndOtherMinutesDoNotInterfereWithTheCurrentMinute() {
        val source = row(minute + 23)
        val unrelated = source.copy(identity = source.identity.copy(key = "heart_rate"))
        val policy = MiSpO2MinuteMatchPolicy(listOf(source, unrelated, row(minute + 61)))
        assertTrue(policy.collisions.isEmpty())
        val cloud = row(minute).cloud()
        assertEquals(cloud, policy.find(source, listOf(cloud, unrelated.cloud(), row(minute + 60).cloud().copy(deleted = true))))
        assertFalse(policy.hasMinuteObservation(unrelated, listOf(cloud)))
        assertFalse(policy.hasMinuteObservation(source, listOf(row(minute + 60).cloud())))
    }

    @Test
    fun finalRepresentableMinuteDoesNotOverflowItsUpperBound() {
        val source = row(Long.MAX_VALUE)
        val floor = Long.MAX_VALUE.floorDiv(60L) * 60L
        assertEquals(row(floor).cloud(), MiSpO2MinuteMatchPolicy(listOf(source)).find(source, listOf(row(floor).cloud())))
    }

    private fun row(time: Long, spo2: Int = 98) = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, MI_HEALTH_CONNECT_IMPORT_SID, "spo2", time),
        buildJsonObject { put("time", time); put("spo2", spo2) }, 28_800, setOf("synthetic:$time"),
    )
    private fun MiHealthImportRow.cloud() = MiHealthImportCloudRow(identity.sid, identity.key, identity.time,
        identity.tag, value, zoneOffsetSeconds = zoneOffsetSeconds)
    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
