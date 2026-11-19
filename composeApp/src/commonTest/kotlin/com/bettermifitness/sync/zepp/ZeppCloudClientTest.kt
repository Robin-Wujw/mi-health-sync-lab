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
        assertEquals(setOf("MANUAL_STRESS"), client.fetch(session, start, start + 3600).incomplete)
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
