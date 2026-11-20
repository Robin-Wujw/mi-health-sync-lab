package com.bettermifitness.sync.zepp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class ZeppCloudClientTest {
    @Test fun denseWorkoutHeartRateBindsTheDetailToTheExactIndexedSourceAndSession() = runTest {
        var requests=0
        val end=start+3
        val client=ZeppCloudClient(HttpClient(MockEngine { request ->
            requests++
            when(request.url.encodedPath) {
                "/v1/sport/run/history.json" -> respond("""{"data":{"summary":[
                    {"trackid":"$start","start_time":$start,"end_time":$end,"source":"synthetic"}],"next":0}}""")
                "/v1/sport/run/detail.json" -> {
                    assertEquals("synthetic",request.url.parameters["source"])
                    assertEquals(start.toString(),request.url.parameters["trackid"])
                    respond("""{"data":{"trackid":"$start","source":"synthetic","heart_rate":"0,100;2,1"}}""")
                }
                else -> error("Unexpected source request")
            }
        }))
        val points=client.workoutHeartRate(session,start,end)
        assertEquals(listOf(100.0,101.0,101.0),points.map {it.value})
        assertEquals(2,requests)
    }
    @Test fun paiOnlyNeverRequestsDisabledStressLoadOrWorkoutStreams() = runTest {
        var requests = 0
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            requests++
            assertEquals("PaiHealthInfo", request.url.parameters["eventType"])
            respond("""{"data":{"items":[]}}""")
        }))
        assertTrue(client.fetch(session, start, start + 3600, setOf(ZeppSyncMetric.PAI)).observations.isEmpty())
        assertEquals(1, requests)
    }

    @Test fun emptySelectionDoesNotReadAnySourceStream() = runTest {
        val client = ZeppCloudClient(HttpClient(MockEngine { error("Disabled sources must not be requested") }))
        val source = client.fetch(session, start, start + 3600, emptySet())
        assertTrue(source.observations.isEmpty() && source.workouts.isEmpty() && source.unavailable.isEmpty())
    }

    @Test fun cappedEventWindowSplitsBeforeReportingSuccess() = runTest {
        val windows = mutableListOf<Pair<Long, Long>>()
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            val from = requireNotNull(request.url.parameters["from"]).toLong() / 1_000
            val until = requireNotNull(request.url.parameters["to"]).toLong() / 1_000
            windows += from to until
            val items = if (until - from > 1_800) JsonArray(List(1000) { buildJsonObject {} }) else JsonArray(emptyList())
            respond(buildJsonObject { put("data", buildJsonObject { put("items", items) }) }.toString())
        }))
        val source = client.fetch(session, start, start + 3_600, setOf(ZeppSyncMetric.PAI))
        assertEquals(listOf(start to start + 3_600, start to start + 1_800, start + 1_800 to start + 3_600), windows)
        assertEquals(emptySet(), source.incomplete)
        assertEquals(0, source.invalidItems)
    }

    @Test fun eventCountsAtAndAboveTheCapAreRecoveredWithNoDuplicateBoundary() = runTest {
        for (count in listOf(999, 1000, 1001)) {
            val times = (0 until count).map { start + 2L * it }
            var requests = 0
            val http = HttpClient(MockEngine { request ->
                requests++
                val from = requireNotNull(request.url.parameters["from"]).toLong() / 1_000
                val to = requireNotNull(request.url.parameters["to"]).toLong() / 1_000
                val items = times.filter { it in from..to }.take(1000).map { time ->
                    buildJsonObject { put("timestamp", time); put("dailyPai", 40) }
                }
                respond(buildJsonObject { put("data", JsonArray(items)) }.toString())
            })
            try {
                val result = ZeppCloudClient(http).fetch(session, start, start + 3600, setOf(ZeppSyncMetric.PAI))
                assertEquals(if (count < 1000) 1 else 3, requests)
                assertTrue(result.incomplete.isEmpty())
                assertEquals(times, result.observations.map { it.observedSeconds })
            } finally { http.close() }
        }
    }

    @Test fun explicitContinuationAndMissingChildCannotTurnIntoAnEmptyOrCompleteStream() = runTest {
        val midpoint = start + 1800
        var recover = false
        val http = HttpClient(MockEngine { request ->
            val from = requireNotNull(request.url.parameters["from"]).toLong() / 1_000
            val to = requireNotNull(request.url.parameters["to"]).toLong() / 1_000
            when {
                to - from > 1800 -> respond("""{"data":{"items":[],"hasMore":true,"next":"opaque"}}""")
                from == midpoint && !recover -> respond("", HttpStatusCode.NotFound)
                else -> respond("""{"data":{"items":[{"timestamp":$from,"dailyPai":40}],"hasMore":false,"next":0}}""")
            }
        })
        try {
            val client = ZeppCloudClient(http)
            val partial = client.fetch(session, start, start + 3600, setOf(ZeppSyncMetric.PAI))
            assertEquals(setOf("PAI"), partial.incomplete)
            assertTrue(partial.unavailable.isEmpty())
            assertEquals(listOf(start), partial.observations.map { it.observedSeconds })
            recover = true
            val complete = client.fetch(session, start, start + 3600, setOf(ZeppSyncMetric.PAI))
            assertTrue(complete.incomplete.isEmpty())
            assertEquals(listOf(start, midpoint), complete.observations.map { it.observedSeconds })
        } finally { http.close() }
    }

    @Test fun workoutScoresOnlyUseTheAccountWideWorkoutFeed() = runTest {
        var requests = 0
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            requests++
            assertEquals("/v1/sport/run/history.json", request.url.encodedPath)
            respond("""{"data":{"summary":[],"next":0}}""")
        }))
        assertTrue(client.fetch(session, start, start + 3600, setOf(ZeppSyncMetric.WORKOUT_SCORES)).workouts.isEmpty())
        assertEquals(1, requests)
    }
    private val session = ZeppSession("123", "synthetic-app-token", "synthetic-login-token", "api-mifitcn.zepp.com")
    private val start = 1_800_000_000L
    @Test fun authenticatedReadsUseOnlyRegionalOriginAndCorrectEventUnits() = runTest {
        val calls = mutableListOf<String>()
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            assertEquals("api-mifitcn.zepp.com", request.url.host)
            assertEquals("synthetic-app-token", request.headers["apptoken"])
            assertNull(request.url.parameters["apptoken"])
            assertEquals(HttpMethod.Get, request.method)
            calls += request.url.encodedPath
            if (request.url.encodedPath.endsWith("/events")) {
                assertTrue(checkNotNull(request.url.parameters["from"]).toLong() > 1_000_000_000_000)
                assertEquals("1000", request.url.parameters["limit"])
            }
            respond("""{"data":{"items":[],"next":0}}""", HttpStatusCode.OK)
        }), now = { start + 10 })
        val out = client.fetch(session, start, start + 3600)
        assertEquals(6, calls.size)
        assertTrue(out.observations.isEmpty() && out.workouts.isEmpty() && out.incomplete.isEmpty())
    }
    @Test fun optional404DoesNotBlockOtherStreams() = runTest {
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            if (request.url.parameters["eventType"] == "PaiHealthInfo") respond("", HttpStatusCode.NotFound)
            else respond("""{"data":{"items":[],"next":0}}""", HttpStatusCode.OK)
        }))
        assertEquals(setOf("PAI"), client.fetch(session, start, start + 3600).unavailable)
    }
    @Test fun authErrorIsSafeAndNeverIncludesResponseBody() = runTest {
        val client = ZeppCloudClient(HttpClient(MockEngine { respond("private-response", HttpStatusCode.Unauthorized) }))
        val error = assertFailsWith<ZeppException> { client.fetch(session, start, start + 3600) }
        assertEquals(ZeppFailure.AUTH, error.failure)
        assertFalse(error.toString().contains("private-response"))
    }
    @Test fun vendorFailureIsNotReportedAsAnEmptySuccess() = runTest {
        val client = ZeppCloudClient(HttpClient(MockEngine { respond("""{"code":500,"data":{"items":[]}}""") }))
        assertEquals(ZeppFailure.RESPONSE, assertFailsWith<ZeppException> { client.fetch(session, start, start + 3600) }.failure)
    }
    @Test fun unrecognizedPageIsRejected() {
        val client = ZeppCloudClient(HttpClient(MockEngine { respond("") }))
        assertFailsWith<ZeppException> { client.page(buildJsonObject { put("success", true) }, 1000) }
    }
    @Test fun fullEventPageIsExplicitlyIncomplete() = runTest {
        val items = JsonArray((0 until 1000).map { buildJsonObject { put("timestamp", start); put("value", 40); put("timeZone", 32) } })
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            if (request.url.parameters["eventType"] == "single_stress") respond(buildJsonObject { put("data", items) }.toString())
            else respond("""{"data":{"items":[],"next":0}}""")
        }))
        val source = client.fetch(session, start, start + 3600)
        assertEquals(setOf("MANUAL_STRESS"), source.incomplete)
        assertTrue(source.observations.none { it.metric == "single_stress" })
    }

    @Test fun scheduledOrdinarySaturationReturnsBeforeReadingAnyChild() = runTest {
        val bounds = mutableListOf<Pair<String?, String?>>()
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            bounds += request.url.parameters["from"] to request.url.parameters["to"]
            respond("""{"data":{"items":[],"hasMore":true}}""")
        }))
        val until = start + 3_600
        val split = assertFailsWith<ZeppEventWindowSplitRequired> {
            client.fetch(session, start, until, setOf(ZeppSyncMetric.PAI), deferEventSplit = true)
        }
        assertEquals(setOf(ZeppStream.PAI), split.requiredEvents)
        assertEquals((start * 1000).toString(), bounds.single().first)
        assertEquals((until * 1000).toString(), bounds.single().second)
    }

    @Test fun splitChildCannotTreatPreviouslyPresentEventStreamAsUnsupported() = runTest {
        val client = ZeppCloudClient(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        val metrics = setOf(ZeppSyncMetric.PAI)
        val initial = client.fetch(session, start, start + 3600, metrics)
        assertEquals(setOf("PAI"), initial.unavailable)
        val child = client.fetch(session, start, start + 1800, metrics, requiredEvents = setOf(ZeppStream.PAI))
        assertEquals(setOf("PAI"), child.incomplete)
        assertTrue(child.unavailable.isEmpty())
    }

    @Test fun saturatedStressDefersLogicalSplitAndChildrenStillReadFullContext() = runTest {
        val day = 86_400L
        val middle = start + day / 2
        val end = start + day
        var saturated = true
        val bounds = mutableListOf<Pair<Long, Long>>()
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            if (request.url.parameters["eventType"] != "all_day_stress") {
                respond("""{"data":{"items":[]}}""")
            } else {
                val from = checkNotNull(request.url.parameters["from"]).toLong() / 1000
                val until = checkNotNull(request.url.parameters["to"]).toLong() / 1000
                bounds += from to until
                if (saturated) respond("""{"data":{"items":[],"hasMore":true}}""")
                else respond("""{"data":{"items":[{"time":${start - 1},"timeZone":32,"value":{"data":[
                    {"time":${middle - 1},"value":41},{"time":$middle,"value":42}]}}]}}""")
            }
        }))
        val metrics = setOf(ZeppSyncMetric.STRESS)
        val split = assertFailsWith<ZeppEventWindowSplitRequired> {
            client.fetch(session, start, end, metrics, deferEventSplit = true)
        }
        assertEquals(setOf(ZeppStream.STRESS), split.requiredEvents)
        assertEquals(start - day to end + day, bounds.first())
        assertTrue(bounds.size <= 8)
        saturated = false
        bounds.clear()
        val first = client.fetch(session, start, middle, metrics, requiredEvents = split.requiredEvents)
        val second = client.fetch(session, middle, end, metrics, requiredEvents = split.requiredEvents)
        assertEquals(listOf(start - day to middle + day, middle - day to end + day), bounds)
        assertTrue(first.incomplete.isEmpty())
        assertTrue(second.incomplete.isEmpty())
        assertEquals(middle - 1, first.observations.single().observedSeconds)
        assertEquals(middle, second.observations.single().observedSeconds)
    }

    @Test fun missingStressContextDoesNotReleasePartialTimelineButManualStressRemainsIndependent() = runTest {
        val until = start + 3600
        var stressCalls = 0
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            if (request.url.parameters["eventType"] == "single_stress") {
                respond("""{"data":[{"time":$start,"timeZone":32,"value":60}]}""")
            } else when (++stressCalls) {
                1 -> respond("""{"data":{"items":[],"hasMore":true}}""")
                2 -> respond("", HttpStatusCode.NotFound)
                else -> respond("""{"data":[{"time":$until,"timeZone":32,"value":{"data":[{"time":$start,"value":41}]}}]}""")
            }
        }))
        val source = client.fetch(session, start, until, setOf(ZeppSyncMetric.STRESS))
        assertEquals(setOf("STRESS"), source.incomplete)
        assertEquals("single_stress", source.observations.single().metric)
        assertEquals(3, stressCalls)
    }

    @Test fun saturatedMinimumStressWindowRemainsIncompleteWithoutAnotherScheduledSplit() = runTest {
        var stressCalls = 0
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            if (request.url.parameters["eventType"] == "all_day_stress") {
                stressCalls++
                respond("""{"data":{"items":[],"hasMore":true}}""")
            } else respond("""{"data":{"items":[]}}""")
        }))
        val source = client.fetch(session, start, start + 3 * 3600, setOf(ZeppSyncMetric.STRESS), deferEventSplit = true)
        assertEquals(setOf("STRESS"), source.incomplete)
        assertTrue(source.observations.isEmpty())
        assertTrue(stressCalls in 1..48)
    }
    @Test fun loginExchangesPasswordForTokensAndDoesNotSendItToRegionalApi() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            requests += request
            when (request.url.host) {
                "api-user.huami.com" -> respond("""{"access":"synthetic-access","country_code":"CN"}""")
                "account.huami.com" -> respond("""{"result":"ok","token_info":{"user_id":"123","app_token":"synthetic-app-token",
                    "login_token":"synthetic-login-token"},"domains":[{"cnames":["api-mifitcn.zepp.com"]}]}""")
                else -> error("Unexpected host")
            }
        }))
        assertEquals(session.host, client.login("synthetic@example.test", "synthetic-password").host)
        assertEquals(2, requests.size)
        val firstBody = (requests[0].body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val secondBody = (requests[1].body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue(firstBody.contains("password=synthetic-password"))
        assertFalse(secondBody.contains("synthetic-password"))
    }
    @Test fun loginRejectsUnsafeRegionalHostBeforeAnyCredentialIsSentThere() = runTest {
        var count = 0
        val client = ZeppCloudClient(HttpClient(MockEngine {
            count++
            if (count == 1) respond("""{"access":"synthetic"}""")
            else respond("""{"result":"ok","token_info":{"user_id":"123","app_token":"synthetic","login_token":"synthetic"},
                "domains":[{"cnames":["https://evil.test"]}]}""")
        }))
        assertEquals(ZeppFailure.RESPONSE, assertFailsWith<ZeppException> { client.login("synthetic@example.test", "test") }.failure)
        assertEquals(2, count)
    }
    @Test fun refreshKeepsTheAccountAndOriginalAllowlistedRegion() = runTest {
        val client = ZeppCloudClient(HttpClient(MockEngine { request ->
            assertEquals("account.huami.com", request.url.host)
            assertEquals("synthetic-login-token", request.url.parameters["login_token"])
            respond("""{"token_info":{"user_id":"123","app_token":"synthetic-rotated-app"}}""")
        }))
        val rotated = client.refresh(session)
        assertEquals("synthetic-rotated-app", rotated.appToken)
        assertEquals(session.loginToken, rotated.loginToken)
        assertEquals(session.host, rotated.host)
    }
    @Test fun refreshRefusesAnAccountChange() = runTest {
        val client = ZeppCloudClient(HttpClient(MockEngine {
            respond("""{"token_info":{"user_id":"456","app_token":"synthetic-other-user"}}""")
        }))
        assertEquals(ZeppFailure.AUTH, assertFailsWith<ZeppException> { client.refresh(session) }.failure)
    }
}
