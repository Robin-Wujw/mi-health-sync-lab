package com.bettermifitness.sync.data.api

import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiHealthImportVerifierTest {
    @Test fun denseReadIsIndexedOnceAndEveryConflictingCandidateRemainsVisible() {
        var inspected = 0
        val observations = object : AbstractList<MiHealthImportCloudRow>() {
            override val size = 10_000
            override fun get(index: Int): MiHealthImportCloudRow {
                inspected++
                return observed().copy(time = row.identity.time + index)
            }
        }
        val index = MiHealthImportObservationIndex(observations)
        repeat(10_000) { offset ->
            val target = row.copy(identity = row.identity.copy(time = row.identity.time + offset))
            assertEquals(1, index.atTarget(target).size)
            assertEquals(MiHealthImportVerificationStatus.VERIFIED, index.verify(target).status)
        }
        assertEquals(10_000, inspected)
        val conflict = observed(json("""{"time":1700000000,"bpm":66}"""))
        val candidates = listOf(observed(), conflict, observed(sid = null), observed().copy(deleted = true))
        assertEquals(MiHealthImportVerifier.verify(row, candidates), MiHealthImportObservationIndex(candidates).verify(row))
    }

    @Test fun rawReadsKeepNativePageSizeAndDailyReadsKeepTheirOwnContract() {
        val raw = buildMiHealthImportReadPayload(MiHealthImportChannel.RAW, "heart_rate", 100, 200, "cursor")
        assertEquals(mapOf("key" to "heart_rate", "start_time" to 100L, "end_time" to 200L,
            "reverse" to true, "next_key" to "cursor"), raw)
        assertFalse("limit" in raw)
        val daily = buildMiHealthImportReadPayload(MiHealthImportChannel.DAILY_AGGREGATE, "heart_rate", 100, 200, null)
        assertEquals(100, daily["limit"])
        assertEquals("daily_report", daily["tag"])
        assertFalse("next_key" in daily)
    }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private val row = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, "default", "heart_rate", 1_700_000_000),
        json("""{"time":1700000000,"bpm":65}"""), 28_800, setOf("source:record"),
    )
    private fun observed(value: JsonObject = row.value, sid: String? = "default") = MiHealthImportCloudRow(
        sid, "heart_rate", row.identity.time, null, value,
    )

    @Test
    fun verifiesSentFieldsSemanticallyWhileAllowingAdditionalServerFields() {
        val result = MiHealthImportVerifier.verify(row,
            listOf(observed(json("""{"server_extension":1,"bpm":65.0,"time":1700000000.0}"""))))
        assertEquals(MiHealthImportVerificationStatus.VERIFIED, result.status)
        assertEquals(canonicalMiHealthImportJson(row.value), canonicalMiHealthImportJson(json("""{"bpm":65.0,"time":1700000000}""")))
    }

    @Test
    fun aRecordFromAnotherOrUnknownSourceCannotProveOurUpload() {
        assertEquals(MiHealthImportVerificationStatus.MISSING,
            MiHealthImportVerifier.verify(row, listOf(observed(sid = "watch"))).status)
        assertEquals(MiHealthImportVerificationStatus.AMBIGUOUS,
            MiHealthImportVerifier.verify(row, listOf(observed(sid = null))).status)
    }

    @Test
    fun missingOrChangedFieldsAndConflictingDuplicatesAreReported() {
        val missing = MiHealthImportVerifier.verify(row, listOf(observed(json("""{"time":1700000000}"""))))
        assertEquals(MiHealthImportVerificationStatus.MISMATCH, missing.status)
        assertEquals(setOf("bpm"), missing.mismatchedFields)
        val conflict = MiHealthImportVerifier.verify(row,
            listOf(observed(), observed(json("""{"time":1700000000,"bpm":66}"""))))
        assertEquals(MiHealthImportVerificationStatus.AMBIGUOUS, conflict.status)
        assertEquals(MiHealthImportVerificationStatus.MISSING,
            MiHealthImportVerifier.verify(row, listOf(observed().copy(deleted = true))).status)
    }

    @Test
    fun sleepStageMismatchIdentifiesTheActualChangedField() {
        val sleep = row.copy(value = json("""{"items":[{"start_time":1,"end_time":2,"state":2}]}"""))
        val result = MiHealthImportVerifier.verify(sleep,
            listOf(observed(json("""{"items":[{"start_time":1,"end_time":2,"state":3}]}"""))))
        assertEquals(setOf("items[0].state"), result.mismatchedFields)
    }

    @Test
    fun payloadPreservesProtocolEncodingAndDoesNotUploadLocalProvenance() {
        val payload = buildMiHealthImportUploadPayload(listOf(row), "actual-session-phone-id")
        assertEquals("actual-session-phone-id", payload.getValue("phone_id").jsonPrimitive.content)
        val item = payload.getValue("data_list").jsonArray.single().jsonObject
        assertTrue(item.getValue("value").jsonPrimitive.isString)
        assertEquals(row.value, Json.parseToJsonElement(item.getValue("value").jsonPrimitive.content))
        assertFalse("sourceRecordKeys" in item)
        assertFalse("recordId" in item)
        assertEquals("default", item.getValue("sid").jsonPrimitive.content)
        assertEquals("", item.getValue("zone_name").jsonPrimitive.content)
    }

    @Test
    fun readbackParsesStringAndObjectValuesButNeverConvertsAFailureIntoAnEmptyCloud() {
        val page = parseMiHealthImportReadPage(Json.parseToJsonElement("""{
            "code":0,"result":{"data_list":[
                {"sid":"default","time":1700000000,"value":"{\"bpm\":65}"},
                {"sid":"watch","time":1700000001,"value":{"bpm":66}}
            ],"has_more":true,"next_key":"next"}}
        """), MiHealthImportChannel.RAW, "heart_rate")
        assertEquals(2, page.rows.size)
        assertEquals("default", page.rows.first().sid)
        assertTrue(page.hasMore)
        assertEquals("next", page.nextKey)
        assertFailsWith<MiHealthImportTransportException> {
            parseMiHealthImportReadPage(json("""{"code":9,"result":{"data_list":[]}}"""), MiHealthImportChannel.RAW, "heart_rate")
        }
        assertFailsWith<MiApiException.Unexpected> {
            parseMiHealthImportReadPage(json("""{"code":0,"result":{}}"""), MiHealthImportChannel.RAW, "heart_rate")
        }
    }

    @Test
    fun disabledExperimentFailsBeforeObtainingAClientOrMakingARequest() = runTest {
        var clientRequested = false
        val cloud = MiDirectHealthImportCloud(apiProvider = {
            clientRequested = true
            error("Must not resolve a client while disabled")
        })
        assertFailsWith<IllegalStateException> { cloud.uploadRows(listOf(row)) }
        assertFalse(clientRequested)
    }

    @Test
    fun explicitBusinessRejectionIsDifferentFromAnUncertainResponse() {
        assertEquals(MiHealthImportUploadResult(false, 7, resultShape = MiHealthImportUploadResultShape.MISSING),
            parseMiHealthImportUploadResponse(json("""{"code":7}""")))
        assertFalse(parseMiHealthImportUploadResponse(json("""{"code":0,"result":{"result":false}}""")).accepted)
        assertTrue(parseMiHealthImportUploadResponse(json("""{"code":0,"result":{"result":true}}""")).accepted)
        assertFailsWith<MiApiException.Unexpected> { parseMiHealthImportUploadResponse(json("{}")) }
    }
}
