package com.bettermifitness.sync.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiWorkoutUploadExperimentTest {
    private val target = MiWorkoutExperimentTarget("hc-source", "revision-1", 1_800_000_000, 1_800_001_800, 28_800, 3000, 200)
    private val journal = Slot()
    private val cloud = Cloud()
    private val runner = MiWorkoutUploadExperiment(cloud, journal)

    @Test
    fun payloadUsesNativeEnvelopeAndOnlyRealSummaryFields() {
        val payload = target.payload("existing-phone")
        assertEquals(setOf("phone_id", "sport_records"), payload.keys)
        val row = (payload["sport_records"] as JsonArray).single() as JsonObject
        assertEquals("default", row.getValue("sid").jsonPrimitive.content)
        assertEquals("indoor_running", row.getValue("key").jsonPrimitive.content)
        assertEquals("running", row.getValue("category").jsonPrimitive.content)
        assertEquals(1800, target.value().getValue("duration").jsonPrimitive.content.toInt())
        assertEquals(32, target.value().getValue("timezone").jsonPrimitive.content.toInt())
        assertFalse(target.value().keys.any { it in setOf("did", "pai", "vitality", "train_load", "vo2_max") })
        assertEquals("0", target.value().getValue("version").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { target.payload("xiaomiwear_app_manually") }
    }

    @Test
    fun missingOptionalMetricsAreOmitted() {
        val value = target.copy(distanceMeters = null, activeCaloriesKcal = null).value()
        assertFalse("distance" in value)
        assertFalse("calories" in value)
    }

    @Test
    fun invalidSourceAndTimeAndUnitsAreRejected() {
        assertFailsWith<IllegalArgumentException> { target.copy(sourceId = "") }
        assertFailsWith<IllegalArgumentException> { target.copy(end = target.start) }
        assertFailsWith<IllegalArgumentException> { target.copy(zoneOffsetSeconds = 1) }
        assertFailsWith<IllegalArgumentException> { target.copy(distanceMeters = -1) }
        assertFailsWith<IllegalArgumentException> { target.copy(activeCaloriesKcal = 30_000) }
    }

    @Test
    fun previewDoesNotReserveOrUpload() = runTest {
        assertEquals("PREVIEW_READY", runner.run(listOf(target), false).outcome)
        assertEquals(null, journal.slot)
        assertEquals(0, cloud.uploads)
    }

    @Test
    fun oneWriteIsReservedFirstAndVerifiedByReadback() = runTest {
        cloud.beforeUpload = { assertEquals(target, journal.slot?.target) }
        val result = runner.run(listOf(target), true)
        assertEquals("UPLOADED_READBACK_VERIFIED", result.outcome)
        assertEquals(1, result.attemptedUploads)
        assertEquals(1, cloud.uploads)
        assertTrue(journal.slot!!.verified)
    }

    @Test
    fun freshRunnerAndNewerWorkoutCannotAdvanceTheSingleSlot() = runTest {
        runner.run(listOf(target), true)
        val newer = target.copy(sourceId = "next", start = target.end + 100, end = target.end + 200)
        assertEquals("ALREADY_PRESENT_VERIFIED", MiWorkoutUploadExperiment(cloud, journal).run(listOf(target, newer), true).outcome)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun successfulResponseWithoutPersistenceIsPendingAndNotRetried() = runTest {
        cloud.persist = false
        assertEquals("UNVERIFIED_PENDING", runner.run(listOf(target), true).outcome)
        assertEquals("PENDING_NOT_RETRIED", runner.run(listOf(target), true).outcome)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun responseLostAfterPersistenceCanBeVerified() = runTest {
        cloud.uploadError = IllegalStateException("response lost")
        assertEquals("UPLOADED_READBACK_VERIFIED", runner.run(listOf(target), true).outcome)
        assertTrue(journal.slot!!.verified)
    }

    @Test
    fun cancellationAfterWriteLeavesDurablePending() = runTest {
        cloud.uploadError = CancellationException()
        assertFailsWith<CancellationException> { runner.run(listOf(target), true) }
        assertFalse(journal.slot!!.verified)
        cloud.uploadError = null
        assertEquals("ALREADY_PRESENT_VERIFIED", runner.run(listOf(target), true).outcome)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun readbackFailureKeepsPendingAndLaterReconcilesWithoutWrite() = runTest {
        cloud.failReadNumber = 3
        assertEquals("READBACK_FAILED_PENDING", runner.run(listOf(target), true).outcome)
        cloud.failReadNumber = null
        assertEquals("ALREADY_PRESENT_VERIFIED", runner.run(listOf(target), true).outcome)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun conflictWithOtherSourceIsNeverOverwritten() = runTest {
        cloud.rows += row().copy(sid = "watch")
        assertEquals("CLOUD_CONFLICT", runner.run(listOf(target), true).outcome)
        assertEquals(0, cloud.uploads)
        assertEquals(null, journal.slot)
    }

    @Test
    fun deletedOrDuplicateRowsBlock() = runTest {
        cloud.rows += row().copy(deleted = true)
        assertEquals("CLOUD_CONFLICT", runner.run(listOf(target), true).outcome)
        cloud.rows.clear()
        cloud.rows += listOf(row(), row())
        assertEquals("CLOUD_CONFLICT", runner.run(listOf(target), true).outcome)
        assertEquals(0, cloud.uploads)
    }

    @Test
    fun changedCloudBetweenPrechecksPreventsWrite() = runTest {
        cloud.beforeRead = { if (cloud.reads == 2) cloud.rows += row() }
        assertEquals("CLOUD_CHANGED_BEFORE_WRITE", runner.run(listOf(target), true).outcome)
        assertEquals(0, cloud.uploads)
        assertEquals(null, journal.slot)
    }

    @Test
    fun missingVerifiedHistoryIsNotRecreated() = runTest {
        runner.run(listOf(target), true)
        cloud.rows.clear()
        assertEquals("VERIFIED_HISTORY_MISSING", runner.run(listOf(target), true).outcome)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun changedOrMissingPinnedSourceDoesNotSelectAnotherWorkout() = runTest {
        journal.slot = MiWorkoutExperimentReservation(target)
        assertEquals("PINNED_SOURCE_CHANGED", runner.run(listOf(target.copy(start = target.start + 1)), true).outcome)
        assertEquals("PINNED_SOURCE_MISSING_OR_AMBIGUOUS", runner.run(emptyList(), true).outcome)
        assertEquals(0, cloud.uploads)
    }

    @Test
    fun sourceTimeCollisionBlocksEvenWithDifferentIds() = runTest {
        val result = runner.run(listOf(target, target.copy(sourceId = "another")), true)
        assertEquals("SOURCE_OVERLAP_OR_COLLISION", result.outcome)
        assertEquals(0, cloud.uploads)
    }

    @Test
    fun failedDurableReservationCannotUpload() = runTest {
        journal.refuseReserve = true
        assertEquals("RESERVATION_CHANGED", runner.run(listOf(target), true).outcome)
        assertEquals(0, cloud.uploads)
    }

    @Test
    fun failedPreflightDoesNotReserveOrWrite() = runTest {
        cloud.failReadNumber = 1
        assertFailsWith<IllegalStateException> { runner.run(listOf(target), true) }
        assertEquals(null, journal.slot)
        assertEquals(0, cloud.uploads)
    }

    @Test
    fun mismatchInAnyMappedFieldCannotVerify() {
        for ((key, _) in target.value()) {
            val changed = JsonObject(target.value() + (key to JsonPrimitive(-123)))
            assertFalse(row().copy(value = changed).matches(target), key)
        }
        assertFalse(row().copy(zoneOffset = null).matches(target))
        assertFalse(row().copy(key = "outdoor_running").matches(target))
        assertFalse(row().copy(category = "walking").matches(target))
    }

    @Test
    fun numericStringsDoNotPassReadback() {
        val stringNumbers = JsonObject(target.value().mapValues { JsonPrimitive(it.value.jsonPrimitive.content) })
        assertFalse(row().copy(value = stringNumbers).matches(target))
    }

    @Test
    fun explicitRejectionDoesNotClearReservationOrRetry() = runTest {
        cloud.persist = false
        cloud.response = Json.parseToJsonElement("""{"code":123,"result":{"result":false}}""")
        val result = runner.run(listOf(target), true)
        assertEquals(123, result.businessCode)
        assertEquals(false, result.accepted)
        assertEquals("PENDING_NOT_RETRIED", runner.run(listOf(target), true).outcome)
        assertEquals(1, cloud.uploads)
    }

    @Test
    fun parserRejectsMissingIdentityAndMalformedDeletionStatus() {
        assertEquals(row(), parseMiWorkoutExperimentRow(target.row()))
        assertFailsWith<IllegalStateException> { parseMiWorkoutExperimentRow(JsonObject(target.row() - "sid")) }
        assertFailsWith<IllegalStateException> {
            parseMiWorkoutExperimentRow(JsonObject(target.row() + ("deleted" to JsonPrimitive("false"))))
        }
    }

    @Test
    fun cloudRowsWithoutReadableIntervalsCannotEstablishAbsence() {
        for (key in listOf("start_time", "end_time")) {
            val malformed = JsonObject(target.row() + ("value" to JsonObject(target.value() - key)))
            assertFailsWith<IllegalStateException> { parseMiWorkoutExperimentRow(malformed) }
        }
    }

    private fun row() = parseMiWorkoutExperimentRow(target.row())

    private class Slot : MiWorkoutExperimentJournal {
        var slot: MiWorkoutExperimentReservation? = null
        var refuseReserve = false
        override suspend fun load() = slot
        override suspend fun reserve(target: MiWorkoutExperimentTarget): Boolean {
            if (slot != null || refuseReserve) return false
            slot = MiWorkoutExperimentReservation(target)
            return true
        }
        override suspend fun verified(target: MiWorkoutExperimentTarget) {
            check(slot?.target == target)
            slot = MiWorkoutExperimentReservation(target, true)
        }
    }

    private class Cloud : MiWorkoutExperimentCloud {
        val rows = mutableListOf<MiWorkoutExperimentCloudRow>()
        var reads = 0
        var uploads = 0
        var persist = true
        var uploadError: Exception? = null
        var failReadNumber: Int? = null
        var beforeRead: () -> Unit = {}
        var beforeUpload: () -> Unit = {}
        var response = Json.parseToJsonElement("""{"code":0,"result":{"result":true}}""")
        override suspend fun read(from: Long, until: Long): List<MiWorkoutExperimentCloudRow> {
            reads++
            beforeRead()
            check(reads != failReadNumber)
            return rows.toList()
        }
        override suspend fun upload(target: MiWorkoutExperimentTarget): kotlinx.serialization.json.JsonElement {
            beforeUpload()
            uploads++
            if (persist) rows += parseMiWorkoutExperimentRow(target.row())
            uploadError?.let { throw it }
            return response
        }
    }
}
