package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportEnrichment
import com.bettermifitness.sync.data.api.MiHealthImportIdentity
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiOwnedSleepRevisionStatus
import com.bettermifitness.sync.data.api.MiSleepConflictOwnership
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

class MiOwnedSleepRevisionPolicyTest {
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
        owned: String? = fingerprint(observed()), requested: Boolean = true, zoneReliable: Boolean = true) =
        prepareOwnedSleepRevision(source, actual, owned, requested, zoneReliable)
    private fun status(source: MiHealthImportRow = revised, actual: List<MiHealthImportCloudRow> = listOf(observed()),
        owned: String? = fingerprint(observed()), zoneReliable: Boolean = true) =
        assessOwnedSleepRevision(source, actual, owned, zoneReliable)

    @Test
    fun explicitOptInReplacesOwnedSleepFieldsWithoutChangingTargetOrSource() {
        assertNull(prepare(requested = false))
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
        val result = assertNotNull(prepare(actual = listOf(actual), owned = fingerprint(actual)))
        assertEquals(actual.value["server_extension"], result.row.value["server_extension"])
        assertFalse("is_uncomplete" in result.row.value)
        assertEquals(revised.value["items"], result.row.value["items"])
    }

    @Test
    fun permitsMappedPartialStagesWhenTimezoneIsKnownAndKeepsNewPartialFlag() {
        val partial = revised.copy(dayAggregationEligible = false,
            value = JsonObject(revised.value + ("is_uncomplete" to JsonPrimitive(true))))
        assertEquals(partial, assertNotNull(prepare(source = partial)).row)
        assertEquals(MiOwnedSleepRevisionStatus.SOURCE_TIMEZONE_UNCERTAIN, status(source = partial, zoneReliable = false))
    }

    @Test
    fun observedAndLegacyRecordsNeverGainReplacementPermission() {
        assertEquals(MiOwnedSleepRevisionStatus.NOT_OWNED, status(owned = null))
        assertNull(prepare(owned = null))
        assertEquals(MiSleepConflictOwnership.OBSERVED_BASELINE_UNCHANGED,
            describeSleepConflictOwnership(revised, listOf(observed()), fingerprint(observed()), null, null))
        assertEquals(MiSleepConflictOwnership.LEGACY_BASELINE_UNCHANGED,
            describeSleepConflictOwnership(revised, listOf(observed()), fingerprint(observed()), null, fingerprint(observed())))
    }

    @Test
    fun cloudContentChangeSinceOwnedReadBlocksEvenWhenMappedBaseStillMatches() {
        val actual = observed().copy(value = JsonObject(old.value + ("new_server_field" to JsonPrimitive(1))))
        assertEquals(MiOwnedSleepRevisionStatus.CLOUD_BASELINE_CHANGED, status(actual = listOf(actual)))
        assertNull(prepare(actual = listOf(actual)))
        assertEquals(MiSleepConflictOwnership.OWNED_BASELINE_CHANGED,
            describeSleepConflictOwnership(revised, listOf(actual), fingerprint(observed()), fingerprint(observed()), null))
    }

    @Test
    fun currentSourceMustResolveToExactlyOneRecord() {
        for (sources in listOf(emptySet(), setOf(""), setOf("source:one", "source:two"))) {
            assertEquals(MiOwnedSleepRevisionStatus.SOURCE_NOT_UNIQUE, status(source = revised.copy(sourceRecordKeys = sources)))
        }
    }

    @Test
    fun hrvEnrichmentAndOtherTargetsCannotUseReplacement() {
        for (source in listOf(
            revised.copy(enrichment = MiHealthImportEnrichment.SLEEP_HRV),
            revised.copy(identity = revised.identity.copy(channel = MiHealthImportChannel.DAILY_AGGREGATE)),
            revised.copy(identity = revised.identity.copy(key = "spo2")),
            revised.copy(identity = revised.identity.copy(sid = "watch")),
            revised.copy(value = JsonObject(revised.value + ("avg_hrv" to JsonPrimitive(40)))),
        )) assertEquals(MiOwnedSleepRevisionStatus.UNSUPPORTED_TARGET, status(source = source))
    }

    @Test
    fun existingHrvCannotBeSilentlyDeletedOrReassociatedToChangedSleep() {
        for (field in listOf("avg_hrv", "min_hrv", "max_hrv")) {
            val actual = observed().copy(value = JsonObject(old.value + (field to JsonPrimitive(40))))
            assertEquals(MiOwnedSleepRevisionStatus.HRV_PRESENT, status(actual = listOf(actual), owned = fingerprint(actual)))
            assertNull(prepare(actual = listOf(actual), owned = fingerprint(actual)))
        }
    }

    @Test
    fun differentOrUnknownCloudTimezoneCannotAuthorizeReplacement() {
        for (zone in listOf(null, 0)) {
            assertEquals(MiOwnedSleepRevisionStatus.TIMEZONE_CHANGED,
                status(actual = listOf(observed().copy(zoneOffsetSeconds = zone))))
        }
    }

    @Test
    fun deletedOtherAndUnknownSourcesBlockEvenAlongsideOwnedMatch() {
        for ((extra, expected) in listOf(
            observed().copy(deleted = true) to MiOwnedSleepRevisionStatus.TOMBSTONE_PRESENT,
            observed().copy(sid = "watch") to MiOwnedSleepRevisionStatus.OTHER_SOURCE_PRESENT,
            observed().copy(sid = null) to MiOwnedSleepRevisionStatus.AMBIGUOUS,
            observed().copy(sid = "") to MiOwnedSleepRevisionStatus.AMBIGUOUS,
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
            val actual = observed().copy(value = value)
            assertEquals(MiOwnedSleepRevisionStatus.INVALID_SLEEP_BASELINE,
                status(actual = listOf(actual), owned = fingerprint(actual)))
        }
        assertEquals(MiOwnedSleepRevisionStatus.INVALID_SLEEP_BASELINE,
            status(source = revised.copy(value = JsonObject(revised.value - "items"))))
    }

    @Test
    fun identicalMatchesAreNotReplacedAndIdenticalDuplicatePagesAreSafe() {
        assertEquals(MiOwnedSleepRevisionStatus.ALREADY_MATCHED, status(source = old))
        assertNull(prepare(source = old))
        assertNotNull(prepare(actual = listOf(observed(), observed())))
        assertEquals(MiOwnedSleepRevisionStatus.AMBIGUOUS, status(actual = listOf(observed(), observed(revised))))
    }

    @Test
    fun numericCloudNormalizationDoesNotInvalidateOwnedSleepBounds() {
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
        assertEquals(MiOwnedSleepRevisionStatus.MISSING, status(source = revised.copy(identity = revised.identity.copy(time = end + 1))))
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
    fun ownershipDiagnosticDoesNotImplySameSourceRevisionOrPermission() {
        assertEquals(MiSleepConflictOwnership.NO_HISTORY,
            describeSleepConflictOwnership(revised, listOf(observed()), null, null, null))
        assertEquals(MiSleepConflictOwnership.OWNED_BASELINE_UNCHANGED,
            describeSleepConflictOwnership(revised, listOf(observed()), fingerprint(observed()), fingerprint(observed()), null))
        assertEquals(MiSleepConflictOwnership.CLOUD_TARGET_UNCERTAIN,
            describeSleepConflictOwnership(
                revised, listOf(observed().copy(deleted = true)), fingerprint(observed()), fingerprint(observed()), null))
    }
}
