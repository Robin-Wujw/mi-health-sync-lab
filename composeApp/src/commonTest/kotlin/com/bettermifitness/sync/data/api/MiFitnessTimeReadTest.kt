package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.data.repository.collectFitnessPages
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MiFitnessTimeReadTest {
    @Test fun retainsBoundarySampleAndFiltersExpandedAndUpperEdges() = runTest {
        val response = readMiFitnessTimePage("heart_rate", 100, 200, null) { payload ->
            assertEquals(99L, payload["start_time"])
            assertEquals(200L, payload["end_time"])
            assertFalse("limit" in payload)
            Json.parseToJsonElement("""{"code":0,"result":{"data_list":[
                {"key":"heart_rate","sid":"watch","time":99,"value":{"bpm":60}},
                {"key":"heart_rate","sid":"watch","time":100,"value":{"bpm":61}},
                {"key":"heart_rate","sid":"watch","time":199,"value":{"bpm":62}},
                {"key":"heart_rate","sid":"watch","time":200,"value":{"bpm":63}}
            ],"has_more":false,"watermark":"synthetic-watermark"}}""")
        }
        assertEquals(listOf(100L, 199L), response.result!!.dataList.map { it.time })
        assertEquals("watch", response.result!!.dataList.first().sid)
        assertEquals("synthetic-watermark", response.result!!.watermark)
    }

    @Test fun filteredEmptyPageStillContinuesWithSameExpandedBounds() = runTest {
        var requests = 0
        val result = collectFitnessPages(maxPages = 3) { next ->
            readMiFitnessTimePage("spo2", 100, 200, next) { payload ->
                requests++
                assertEquals(99L, payload["start_time"])
                assertEquals(200L, payload["end_time"])
                if (next == null) Json.parseToJsonElement("""{"code":0,"result":{"data_list":[
                    {"key":"spo2","time":99,"value":{"spo2":97}}
                ],"has_more":true,"next_key":"synthetic-next"}}""") else {
                    assertEquals("synthetic-next", payload["next_key"])
                    Json.parseToJsonElement("""{"code":0,"result":{"data_list":[
                        {"key":"spo2","time":100,"value":{"spo2":98}}
                    ],"has_more":false}}""")
                }
            }
        }
        assertEquals(2, requests)
        assertEquals(listOf(100L), result.map { it.time })
    }

    @Test fun epochLowerBoundDoesNotUnderflow() = runTest {
        readMiFitnessTimePage("weight", 0, 200, null) { payload ->
            assertEquals(0L, payload["start_time"])
            Json.parseToJsonElement("""{"code":0,"result":{"data_list":[],"has_more":false}}""")
        }
    }

    @Test fun rejectedReadCannotBecomeAnEmptySuccess() = runTest {
        assertFailsWith<MiApiException.Server> {
            readMiFitnessTimePage("heart_rate", 100, 200, null) {
                Json.parseToJsonElement("""{"code":500,"result":{"data_list":[]}}""")
            }
        }
    }

    @Test fun invalidWindowDoesNotMakeANetworkRequest() = runTest {
        assertFailsWith<IllegalArgumentException> {
            readMiFitnessTimePage("heart_rate", 200, 100, null) { error("Must not send") }
        }
    }
}
