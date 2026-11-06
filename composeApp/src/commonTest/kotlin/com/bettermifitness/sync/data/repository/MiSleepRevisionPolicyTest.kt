package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportEnrichment
import com.bettermifitness.sync.data.api.MiHealthImportIdentity
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiSleepConflictOwnership
import com.bettermifitness.sync.data.api.MiSleepRevisionStatus
import com.bettermifitness.sync.data.api.canonicalMiHealthImportJson
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiSleepRevisionPolicyTest {
    private val end = 1_700_000_000L
    private val old = row(end - 3_600, 2)
    private val revised = row(end - 7_200, 3)

    private fun row(start: Long, state: Int) = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, "default", "sleep", end),
        json("""{"bedtime":$start,"bed_timestamp":$start,"wake_up_time":$end,"out_bed_timestamp":$end,
            "duration":${(end - start) / 60},"timezone":32,"sleep_deep_duration":0,"sleep_light_duration":0,
            "sleep_rem_duration":0,"sleep_awake_duration":0,"awake_count":0,
            "items":[{"start_time":$start,"end_time":$end,"state":$state}]}"""),
        28_800, setOf("com.huami.watch.hmwatchmanager:source-record"),
    )
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun observed(row: MiHealthImportRow = old) = MiHealthImportCloudRow(
        row.identity.sid, row.identity.key, row.identity.time, row.identity.tag, row.value,
        zoneOffsetSeconds = row.zoneOffsetSeconds,
    )
    private fun fingerprint(actual: MiHealthImportCloudRow) = HealthRecordIds.version(canonicalMiHealthImportJson(actual.value)).toString()
    private fun prepare(source: MiHealthImportRow = revised, actual: List<MiHealthImportCloudRow> = listOf(observed()),
        zoneReliable: Boolean = true) = prepareSleepRevision(source, actual, zoneReliable)
    private fun status(source: MiHealthImportRow = revised, actual: List<MiHealthImportCloudRow> = listOf(observed()),
        zoneReliable: Boolean = true) = assessSleepRevision(source, actual, zoneReliable)
    private fun withSummary(row: MiHealthImportRow, average: Int) = JsonObject(row.value + mapOf(
        "avg_hrv" to JsonPrimitive(average), "min_hrv" to JsonPrimitive(average - 10), "max_hrv" to JsonPrimitive(average + 10)))
    private fun hrvRow(average: Int = 60) = revised.copy(
        value = withSummary(revised, average),
        sourceRecordKeys = revised.sourceRecordKeys + setOf("source:sample-1", "source:sample-2"),
        enrichment = MiHealthImportEnrichment.SLEEP_HRV,
    )

    @Test
    fun differingSleepIsReplacedWithoutChangingTargetOrSource() {
        val result = assertNotNull(prepare())
        assertEquals(revised, result.row)
        assertEquals(old.identity, result.row.identity)
        assertEquals(revised.sourceRecordKeys, result.row.sourceRecordKeys)
        assertTrue(result.stillMatches(listOf(observed())))
    }

    @Test
    fun preservesCloudExtensionsAndRemovesObsoletePartialStageFlag() {
        val actual = observed().copy(value = JsonObject(old.value + mapOf(
            "server_extension" to json("""{"original":true}"""), "is_uncomplete" to JsonPrimitive(true))))
        val result = assertNotNull(prepare(actual = listOf(actual)))
        assertEquals(actual.value["server_extension"], result.row.value["server_extension"])
        assertFalse("is_uncomplete" in result.row.value)
        assertEquals(revised.value["items"], result.row.value["items"])
    }

    @Test
    fun permitsMappedPartialStagesWhenTimezoneIsKnownAndKeepsNewPartialFlag() {
        val partial = revised.copy(dayAggregationEligible = false,
            value = JsonObject(revised.value + ("is_uncomplete" to JsonPrimitive(true))))
        assertEquals(partial, assertNotNull(prepare(source = partial)).row)
        assertEquals(MiSleepRevisionStatus.SOURCE_TIMEZONE_UNCERTAIN, status(source = partial, zoneReliable = false))
    }

    @Test
    fun sleepRevisionKeepsAnExistingHrvSummaryForTheHrvImportToRecompute() {
        for (field in listOf("avg_hrv", "min_hrv", "max_hrv")) {
            val actual = observed().copy(value = JsonObject(old.value + (field to JsonPrimitive(40))))
            assertEquals(MiSleepRevisionStatus.ELIGIBLE, status(actual = listOf(actual)))
            val result = assertNotNull(prepare(actual = listOf(actual)))
            assertEquals(JsonPrimitive(40), result.row.value[field])
            assertEquals(revised.value["bedtime"], result.row.value["bedtime"])
        }
    }

    @Test
    fun hrvRowReplacesTheSleepAndItsSummaryAndKeepsCloudExtensions() {
        val actual = observed().copy(value = JsonObject(withSummary(old, 40) + ("server_extension" to JsonPrimitive("kept"))))
        val result = assertNotNull(prepare(source = hrvRow(60), actual = listOf(actual)))
        assertEquals(JsonPrimitive(60), result.row.value["avg_hrv"])
        assertEquals(JsonPrimitive(50), result.row.value["min_hrv"])
        assertEquals(revised.value["bedtime"], result.row.value["bedtime"])
        assertEquals(JsonPrimitive("kept"), result.row.value["server_extension"])
    }

    @Test
    fun hrvRowCanAddAMissingSummaryToAMatchingSleep() {
        val result = assertNotNull(prepare(source = hrvRow(), actual = listOf(observed(revised))))
        assertEquals(hrvRow().value, result.row.value)
    }

    @Test
    fun identicalHrvRowIsNotReplaced() {
        assertEquals(MiSleepRevisionStatus.ALREADY_MATCHED,
            status(source = hrvRow(), actual = listOf(observed(revised.copy(value = hrvRow().value)))))
    }

    @Test
    fun currentSleepSourceMustResolveToExactlyOneRecord() {
        for (sources in listOf(emptySet(), setOf(""), setOf("source:one", "source:two"))) {
            assertEquals(MiSleepRevisionStatus.SOURCE_NOT_UNIQUE, status(source = revised.copy(sourceRecordKeys = sources)))
        }
    }

    @Test
    fun hrvRowMayListItsSamplesAsSourcesButNeedsAtLeastOneValidSource() {
        assertEquals(MiSleepRevisionStatus.ELIGIBLE, status(source = hrvRow()))
        for (sources in listOf(emptySet(), setOf("source:one", ""))) {
            assertEquals(MiSleepRevisionStatus.SOURCE_NOT_UNIQUE, status(source = hrvRow().copy(sourceRecordKeys = sources)))
        }
    }

    @Test
    fun otherTargetsCannotUseReplacement() {
        for (source in listOf(
            revised.copy(identity = revised.identity.copy(channel = MiHealthImportChannel.DAILY_AGGREGATE)),
            revised.copy(identity = revised.identity.copy(key = "spo2")),
            revised.copy(identity = revised.identity.copy(sid = "watch")),
            revised.copy(value = JsonObject(revised.value + ("avg_hrv" to JsonPrimitive(40)))),
            revised.copy(value = JsonObject(revised.value + ("unknown_field" to JsonPrimitive(1))),
                enrichment = MiHealthImportEnrichment.SLEEP_HRV),
        )) assertEquals(MiSleepRevisionStatus.UNSUPPORTED_TARGET, status(source = source))
    }

    @Test
    fun differentOrUnknownCloudTimezoneCannotAuthorizeReplacement() {
        for (zone in listOf(null, 0)) {
            assertEquals(MiSleepRevisionStatus.TIMEZONE_CHANGED,
                status(actual = listOf(observed().copy(zoneOffsetSeconds = zone))))
        }
    }

    @Test
    fun deletedOtherAndUnknownSourcesStillBlockTheReplacement() {
        for ((extra, expected) in listOf(
            observed().copy(deleted = true) to MiSleepRevisionStatus.TOMBSTONE_PRESENT,
            observed().copy(sid = "watch") to MiSleepRevisionStatus.OTHER_SOURCE_PRESENT,
            observed().copy(sid = null) to MiSleepRevisionStatus.AMBIGUOUS,
            observed().copy(sid = "") to MiSleepRevisionStatus.AMBIGUOUS,
        )) {
            val actual = listOf(observed(), extra)
            assertEquals(expected, status(actual = actual))
            assertNull(prepare(actual = actual))
        }
    }

    @Test
    fun inconsistentOrMissingSleepBoundsBlockReplacement() {
        for (value in listOf(
            JsonObject(old.value - "bed_timestamp"),
            JsonObject(old.value + ("out_bed_timestamp" to JsonPrimitive(end + 1))),
            JsonObject(old.value + ("bedtime" to JsonPrimitive(end + 1))),
            JsonObject(old.value + ("wake_up_time" to JsonPrimitive(end.toString()))),
        )) {
            assertEquals(MiSleepRevisionStatus.INVALID_SLEEP_BASELINE, status(actual = listOf(observed().copy(value = value))))
        }
        assertEquals(MiSleepRevisionStatus.INVALID_SLEEP_BASELINE,
            status(source = revised.copy(value = JsonObject(revised.value - "items"))))
    }

    @Test
    fun identicalMatchesAreNotReplacedAndIdenticalDuplicatePagesAreSafe() {
        assertEquals(MiSleepRevisionStatus.ALREADY_MATCHED, status(source = old))
        assertNull(prepare(source = old))
        assertNotNull(prepare(actual = listOf(observed(), observed())))
        assertEquals(MiSleepRevisionStatus.AMBIGUOUS, status(actual = listOf(observed(), observed(revised))))
    }

    @Test
    fun numericCloudNormalizationDoesNotInvalidateSleepBounds() {
        val normalized = observed().copy(value = JsonObject(old.value + mapOf(
            "bedtime" to JsonPrimitive((end - 3_600).toDouble()),
            "bed_timestamp" to JsonPrimitive((end - 3_600).toDouble()),
            "wake_up_time" to JsonPrimitive(end.toDouble()),
            "out_bed_timestamp" to JsonPrimitive(end.toDouble()),
        )))
        assertEquals(fingerprint(observed()), fingerprint(normalized))
        assertNotNull(prepare(actual = listOf(normalized)))
    }

    @Test
    fun changedTargetIdentityCannotCreateANewRecordThroughRevisionPath() {
        assertEquals(MiSleepRevisionStatus.MISSING, status(source = revised.copy(identity = revised.identity.copy(time = end + 1))))
        assertNull(prepare(actual = emptyList()))
    }

    @Test
    fun finalReadRejectsChangedBaselineZoneDeletionAndOtherSources() {
        val prepared = assertNotNull(prepare())
        for (rows in listOf(
            emptyList(), listOf(observed(revised)), listOf(observed().copy(zoneOffsetSeconds = null)),
            listOf(observed().copy(deleted = true)), listOf(observed(), observed().copy(sid = "watch")),
            listOf(observed(), observed().copy(sid = null)),
        )) assertFalse(prepared.stillMatches(rows))
    }

    @Test
    fun ownershipDiagnosticReportsTheHistoryOfTheCloudRow() {
        val current = fingerprint(observed())
        assertEquals(MiSleepConflictOwnership.NO_HISTORY,
            describeSleepConflictOwnership(revised, listOf(observed()), null, null, null))
        assertEquals(MiSleepConflictOwnership.OBSERVED_BASELINE_UNCHANGED,
            describeSleepConflictOwnership(revised, listOf(observed()), current, null, null))
        assertEquals(MiSleepConflictOwnership.LEGACY_BASELINE_UNCHANGED,
            describeSleepConflictOwnership(revised, listOf(observed()), current, null, current))
        assertEquals(MiSleepConflictOwnership.OWNED_BASELINE_UNCHANGED,
            describeSleepConflictOwnership(revised, listOf(observed()), current, current, null))
        assertEquals(MiSleepConflictOwnership.OWNED_BASELINE_CHANGED,
            describeSleepConflictOwnership(revised, listOf(observed()), "other", "other", null))
        assertEquals(MiSleepConflictOwnership.CLOUD_TARGET_UNCERTAIN,
            describeSleepConflictOwnership(revised, listOf(observed().copy(deleted = true)), current, current, null))
    }
}
