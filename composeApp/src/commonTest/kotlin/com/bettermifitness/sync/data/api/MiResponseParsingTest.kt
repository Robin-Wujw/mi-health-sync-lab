package com.bettermifitness.sync.data.api

import com.mifitness.miclient.api.MiApiException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiResponseParsingTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun failedBusinessResponseCannotBecomeNoData() {
        for (parse in listOf(::parseMiDataListResponse, ::parseMiLatestResponse)) {
            val error = assertFailsWith<MiApiException.Server> {
                parse(json("""{"code":500,"message":"failure"}"""))
            }
            assertEquals(500, error.httpOrBusinessCode)
        }
        assertFailsWith<MiApiException.Server> {
            parseMiSportRecordsPage(json("""{"code":123,"result":{"sport_records":[]}}"""))
        }
    }

    @Test
    fun successRequiresBothBusinessCodeAndResultObject() {
        for (raw in listOf("{}", "{\"code\":0}", "{\"result\":{}}", "{\"code\":0,\"result\":null}")) {
            assertFailsWith<MiApiException.Unexpected> { parseMiDataListResponse(json(raw)) }
        }
    }

    @Test
    fun legitimateEmptyResultRemainsEmpty() {
        val response = parseMiDataListResponse(json("""{"code":0,"result":{"data_list":[],"has_more":false}}"""))
        assertEquals(0, response.code)
        assertTrue(response.result!!.dataList.isEmpty())
    }

    @Test
    fun acceptsInlineObjectsAndEncodedJsonWithoutLosingSid() {
        val response = parseMiDataListResponse(json("""{"code":0,"result":{"data_list":[
            {"key":"heart_rate","time":1700000000,"sid":"watch","value":{"bpm":72}},
            {"key":"heart_rate","time":1700000010,"value":"{\"bpm\":73}"}
        ]}}"""))
        val rows = response.result!!.dataList
        assertEquals("watch", rows.first().sid)
        assertEquals("{\"bpm\":72}", rows.first().value)
        assertEquals("{\"bpm\":73}", rows.last().value)
        val latest = parseMiLatestResponse(
            json("""{"code":0,"result":{"data_list":[{"key":"sleep","time":1700000000,"sid":"watch","value":{}}]}}"""))
        assertEquals("watch", latest.result!!.dataList.single().sid)
    }

    @Test
    fun sportInlineObjectIsNotCastToPrimitive() {
        val page = parseMiSportRecordsPage(
            json("""{"code":0,"result":{"sport_records":[{"time":1700000000,"category":"running","value":{"duration":100}}]}}"""))
        assertEquals("{\"duration\":100}", page.dataList.single().value)
    }

    @Test
    fun malformedRowsDoNotDisappearAsAnEmptySuccessfulExport() {
        assertFailsWith<MiApiException.Unexpected> {
            parseMiDataListResponse(json("""{"code":0,"result":{"data_list":[{"time":1700000000,"value":null}]}}"""))
        }
    }

    @Test
    fun morePagesRequireAUsableCursor() {
        assertFailsWith<MiApiException.Unexpected> { MiPageCursorGuard(3).nextCursor(true, null) }
        assertFailsWith<MiApiException.Unexpected> { MiPageCursorGuard(3).nextCursor(true, "") }
    }

    @Test
    fun repeatedOrCyclicCursorFailsInsteadOfDuplicatingHistory() {
        val guard = MiPageCursorGuard(10)
        assertEquals("a", guard.nextCursor(true, "a"))
        assertEquals("b", guard.nextCursor(true, "b"))
        assertFailsWith<MiApiException.Unexpected> { guard.nextCursor(true, "a") }
    }

    @Test
    fun pageLimitDoesNotMasqueradeAsEndOfHistory() {
        val guard = MiPageCursorGuard(2)
        assertEquals("a", guard.nextCursor(true, "a"))
        assertFailsWith<MiApiException.Unexpected> { guard.nextCursor(true, "b") }
        val complete = MiPageCursorGuard(1)
        assertNull(complete.nextCursor(false, null))
    }
}
