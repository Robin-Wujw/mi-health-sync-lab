package com.bettermifitness.sync.data.api

import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Fixture requests exercise the production pagination function, without health data or a session. */
class MiHealthImportNativeReadTest {
    @Test
    fun nativeFullPullOmitsStartAndLimitAndUsesTheReturnedCursor() = runTest {
        val requests = mutableListOf<Map<String, Any?>>()
        val pages = listOf(
            page(listOf(row(90), row(100)), JsonPrimitive(true), JsonPrimitive("cursor-1")),
            page(listOf(row(80), row(90)), JsonPrimitive(false)),
        )
        val read = readMiRawHistoryPages("spo2", 100, 5) { payload ->
            requests += payload
            pages[requests.lastIndex]
        }

        assertEquals(mapOf("key" to "spo2", "end_time" to 100L, "reverse" to true), requests.first())
        assertEquals(requests.first() + ("next_key" to "cursor-1"), requests.last())
        assertTrue(requests.all { "start_time" !in it && "limit" !in it })
        // The server can include the upper endpoint; preserve our exclusive end.
        assertEquals(listOf(90L, 80L), read.rows.map { it.time })
        assertEquals(2, read.pagesRead)
        assertTrue(read.complete)
    }

    @Test
    fun reachingThePageCapPreservesRowsButCannotEstablishAbsence() = runTest {
        var requests = 0
        val read = readMiRawHistoryPages("spo2", 100, 2) {
            requests++
            page(listOf(row(90L - requests)), JsonPrimitive(true), JsonPrimitive("cursor-$requests"))
        }

        assertEquals(2, requests)
        assertEquals(2, read.pagesRead)
        assertEquals(listOf(89L, 88L), read.rows.map { it.time })
        assertFalse(read.complete)
        assertEquals(MiHistoryStopReason.PAGE_LIMIT, read.stopReason)
    }

    @Test
    fun explicitFalseOnTheLastAllowedPageCompletesTheRead() = runTest {
        val read = readMiRawHistoryPages("spo2", 100, 1) {
            page(listOf(row(90)), JsonPrimitive(false))
        }

        assertEquals(1, read.pagesRead)
        assertTrue(read.complete)
    }

    @Test
    fun omittedNullAndMalformedFlagsPreserveValidRowsWithoutClaimingEof() = runTest {
        val flags = listOf(null, JsonNull, JsonPrimitive("false"), JsonPrimitive(0), JsonObject(emptyMap()))
        for (flag in flags) {
            var requests = 0
            val read = readMiRawHistoryPages("spo2", 100, 5) {
                requests++
                page(listOf(row(90)), flag)
            }
            assertEquals(1, requests)
            assertEquals(listOf(90L), read.rows.map { it.time })
            assertFalse(read.complete)
            assertEquals(MiHistoryStopReason.INVALID_PAGINATION, read.stopReason)
        }
    }

    @Test
    fun missingBlankAndMalformedCursorsStopWithoutDiscardingRows() = runTest {
        val cursors = listOf(null, JsonNull, JsonPrimitive(""), JsonPrimitive(" "), JsonPrimitive(12))
        for (cursor in cursors) {
            var requests = 0
            val read = readMiRawHistoryPages("spo2", 100, 5) {
                requests++
                page(listOf(row(90)), JsonPrimitive(true), cursor)
            }
            assertEquals(1, requests)
            assertEquals(listOf(90L), read.rows.map { it.time })
            assertFalse(read.complete)
            assertEquals(if (cursor is JsonPrimitive && cursor != JsonNull && !cursor.isString) MiHistoryStopReason.INVALID_PAGINATION
                else MiHistoryStopReason.MISSING_CURSOR, read.stopReason)
        }
    }

    @Test
    fun aRepeatedCursorIsIncompleteAndRetainsBothPages() = runTest {
        var requests = 0
        val read = readMiRawHistoryPages("spo2", 100, 5) {
            requests++
            page(listOf(row(90L - requests)), JsonPrimitive(true), JsonPrimitive("repeat"))
        }

        assertEquals(2, requests)
        assertEquals(listOf(89L, 88L), read.rows.map { it.time })
        assertFalse(read.complete)
        assertEquals(MiHistoryStopReason.REPEATED_CURSOR, read.stopReason)
    }

    @Test
    fun recoveryMayRead32PagesButOldTimestampsNeverImplyEarlyCompletion() = runTest {
        var requests = 0
        val read = readMiRawHistoryPages("spo2", 10_000, 32) {
            requests++
            page(listOf(row(32L - requests)), JsonPrimitive(true), JsonPrimitive("page-$requests"))
        }

        assertEquals(32, requests)
        assertEquals(32, read.pagesRead)
        assertEquals(32, read.rows.size)
        assertFalse(read.complete)
    }

    @Test
    fun businessAndRowParsingFailuresNeverBecomeSuccessfulEmptyReads() = runTest {
        assertFailsWith<MiHealthImportTransportException> {
            readMiRawHistoryPages("spo2", 100, 5) {
                Json.parseToJsonElement("""{"code":100,"result":{"data_list":[],"has_more":false}}""")
            }
        }
        assertFailsWith<MiApiException.Unexpected> {
            readMiRawHistoryPages("spo2", 100, 5) {
                Json.parseToJsonElement("""{"code":0,"result":{"has_more":false}}""")
            }
        }
        assertFailsWith<MiApiException.Unexpected> {
            readMiRawHistoryPages("spo2", 100, 5) {
                page(listOf(buildJsonObject { put("time", 90) }), JsonPrimitive(false))
            }
        }
    }

    @Test
    fun ordinaryReadersKeepStrictPaginationValidation() {
        assertFailsWith<MiApiException.Unexpected> {
            parseMiHealthImportReadPage(page(listOf(row(90)), JsonPrimitive(1)), MiHealthImportChannel.RAW, "spo2")
        }
        assertFailsWith<MiApiException.Unexpected> {
            parseMiHealthImportReadPage(page(listOf(row(90)), JsonPrimitive(true), JsonPrimitive(12)),
                MiHealthImportChannel.RAW, "spo2")
        }
    }

    @Test
    fun expiredSessionsAndCancellationPropagateUnchanged() = runTest {
        val expired = MiApiException.AuthExpired()
        val cancelled = CancellationException("Fixture cancellation")
        assertSame(expired, assertFailsWith<MiApiException.AuthExpired> {
            readMiRawHistoryPages("spo2", 100, 5) { throw expired }
        })
        assertSame(cancelled, assertFailsWith<CancellationException> {
            readMiRawHistoryPages("spo2", 100, 5) { throw cancelled }
        })
    }

    @Test
    fun invalidRequestsAreRejectedBeforeAnyNetworkCallback() = runTest {
        var requests = 0
        val request: suspend (Map<String, Any?>) -> JsonElement = {
            requests++
            page(emptyList(), JsonPrimitive(false))
        }
        assertFailsWith<IllegalArgumentException> { readMiRawHistoryPages("spo2", 100, 0, request) }
        assertFailsWith<IllegalArgumentException> { readMiRawHistoryPages("spo2", 100, 33, request) }
        assertFailsWith<IllegalArgumentException> { readMiRawHistoryPages("spo2", 0, 5, request) }
        assertFailsWith<IllegalArgumentException> { readMiRawHistoryPages("unsupported", 100, 5, request) }
        assertEquals(0, requests)
    }

    private fun row(time: Long): JsonObject = buildJsonObject {
        put("sid", MI_HEALTH_CONNECT_IMPORT_SID)
        put("key", "spo2")
        put("time", time)
        put("value", buildJsonObject { put("time", time); put("spo2", 98) }.toString())
        put("zone_offset", 28_800)
        put("deleted", false)
    }

    private fun page(rows: List<JsonObject>, hasMore: JsonElement? = null, nextKey: JsonElement? = null) =
        buildJsonObject {
            put("code", 0)
            put("result", buildJsonObject {
                put("data_list", JsonArray(rows))
                if (hasMore != null) put("has_more", hasMore)
                if (nextKey != null) put("next_key", nextKey)
            })
        }
}
