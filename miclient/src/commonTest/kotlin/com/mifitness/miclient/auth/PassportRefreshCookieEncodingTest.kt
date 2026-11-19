package com.mifitness.miclient.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PassportRefreshCookieEncodingTest {
    @Test fun passportReceivesTheSavedTokenVerbatimIncludingCookieSafePunctuation() = runBlocking {
        for (token in listOf("plain-token", "V1:synthetic+token/with=padding", "synthetic%token")) {
            var presented = ""
            val auth = MiAuth(httpClientFactory = { cookies ->
                HttpClient(MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/pass/serviceLogin")) {
                        presented = request.headers[HttpHeaders.Cookie].orEmpty().split(';')
                            .map { it.trim() }.single { it.startsWith("passToken=") }.removePrefix("passToken=")
                        assertEquals(token, presented, "The refresh request must preserve the stored cookie value")
                        if (presented == token) respond(
                            """{"code":0,"ssecurity":"c2VjdXJpdHk=","nonce":123,"userId":1,
                                "location":"https://sts-hlth.io.mi.com/healthapp/sts?p_ur=SG"}""",
                            HttpStatusCode.OK,
                        ) else respond("""{"code":70016,"desc":"token bytes differ"}""", HttpStatusCode.OK)
                    } else respond("ok", HttpStatusCode.OK,
                        headersOf(HttpHeaders.SetCookie, "serviceToken=service-new; Path=/"))
                }) { followRedirects = false; install(HttpCookies) { storage = cookies } }
            })
            val result = auth.refreshWithPassToken(MiCredentials("1", "c2VjdXJpdHk=", "service-old", token, "device", "sg"))
            assertEquals(token, presented)
            assertEquals("service-new", result.serviceToken)
        }
    }

    @Test fun rotatedTokensAreSavedAndReusedVerbatimOnTheNextRenewal() = runBlocking {
        var expected = "V1:seed+token/with=padding"
        var rotations = 0
        val committed = mutableListOf<String>()
        val auth = MiAuth(httpClientFactory = { cookies ->
            HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("/pass/serviceLogin")) {
                    val presented = request.headers[HttpHeaders.Cookie].orEmpty().split(';')
                        .map { it.trim() }.single { it.startsWith("passToken=") }.removePrefix("passToken=")
                    assertEquals(expected, presented)
                    rotations++
                    expected = "V1:rotated+$rotations/token=padding%literal"
                    respond(
                        """{"code":0,"ssecurity":"c2VjdXJpdHk=","nonce":123,"userId":1,
                            "location":"https://sts-hlth.io.mi.com/healthapp/sts?p_ur=SG"}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "passToken=$expected; Path=/"),
                    )
                } else respond("ok", HttpStatusCode.OK,
                    headersOf(HttpHeaders.SetCookie, "serviceToken=service-new; Path=/"))
            }) { followRedirects = false; install(HttpCookies) { storage = cookies } }
        })
        var credentials = MiCredentials("1", "c2VjdXJpdHk=", "service-old", expected, "device", "sg")
        repeat(2) {
            credentials = auth.refreshWithPassToken(credentials, onPassTokenRotated = { committed += it })
            assertEquals(expected, credentials.passToken)
        }
        assertEquals(2, rotations)
        assertEquals(listOf("V1:rotated+1/token=padding%literal", "V1:rotated+2/token=padding%literal"), committed)
    }
}
