package com.mifitness.miclient

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the real Android/JVM OkHttp engine; only synthetic localhost traffic. */
class HttpClientReplayPolicyTest {
    private val fixturePayload = "opaque-local-fixture-body"

    @Test
    fun healthDataPostIsNotResubmittedAfter307Redirect() = runBlocking {
        LoopbackFixture { request, _ ->
            if (request.path == "/upload") Reply.Http(307, mapOf("Location" to "/redirected"))
            else Reply.Http(200, body = "unexpected replay")
        }.use { server ->
            val client = createPlatformHttpClient(allowAutomaticReplay = false)
            try {
                val response = client.post(server.url("/upload")) {
                    contentType(ContentType.Text.Plain)
                    setBody(fixturePayload)
                }
                assertEquals(307, response.status.value)
                response.bodyAsText()
                server.assertHealthy()
                assertEquals(listOf("POST /upload"), server.requests.map { "${it.method} ${it.path}" })
                assertEquals(fixturePayload, server.requests.single().body)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun healthDataDisablesKtorRedirectsWhileDefaultAuthenticationTransportKeepsThem() = runBlocking {
        for (allowReplay in listOf(false, true)) {
            LoopbackFixture { request, _ ->
                if (request.path == "/start") Reply.Http(302, mapOf("Location" to "/final"))
                else Reply.Http(200, body = "fixture result")
            }.use { server ->
                // The no-argument path is the existing Passport/FDS behavior.
                val client = if (allowReplay) createPlatformHttpClient()
                    else createPlatformHttpClient(allowAutomaticReplay = false)
                try {
                    val response = client.get(server.url("/start"))
                    response.bodyAsText()
                    server.assertHealthy()
                    assertEquals(if (allowReplay) 200 else 302, response.status.value)
                    assertEquals(if (allowReplay) 2 else 1, server.requests.size)
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun oneShotBodyStops503ReplayThatRetryOnConnectionFailureAloneDoesNotStop() = runBlocking {
        for (allowReplay in listOf(false, true)) {
            LoopbackFixture { _, index ->
                if (index == 1) Reply.Http(503, mapOf("Retry-After" to "0"))
                else Reply.Http(200, body = "fixture result")
            }.use { server ->
                val client = if (allowReplay) createPlatformHttpClient()
                    else createPlatformHttpClient(allowAutomaticReplay = false)
                try {
                    val response = client.post(server.url("/upload")) {
                        contentType(ContentType.Text.Plain)
                        setBody(fixturePayload)
                    }
                    response.bodyAsText()
                    server.assertHealthy()
                    assertEquals(if (allowReplay) 200 else 503, response.status.value)
                    assertEquals(if (allowReplay) 2 else 1, server.requests.size)
                    assertTrue(server.requests.all { it.method == "POST" && it.body == fixturePayload })
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun acceptedPostWithDroppedResponseFailsWithoutSendingTheBodyAgain() = runBlocking {
        LoopbackFixture { request, _ ->
            if (request.path == "/warmup") Reply.Http(200, body = "fixture ready")
            else Reply.Disconnect
        }.use { server ->
            val client = createPlatformHttpClient(allowAutomaticReplay = false)
            try {
                // Warm the pool so the lost response can occur on a reusable
                // connection, rather than testing only a fresh connection.
                assertEquals("fixture ready", client.get(server.url("/warmup")).bodyAsText())
                val result = runCatching {
                    client.post(server.url("/upload")) {
                        contentType(ContentType.Text.Plain)
                        setBody(fixturePayload)
                    }.bodyAsText()
                }
                assertTrue(result.isFailure)
                server.assertHealthy()
                assertEquals(listOf("GET /warmup", "POST /upload"),
                    server.requests.map { "${it.method} ${it.path}" })
                assertEquals(fixturePayload, server.requests.last().body)
            } finally {
                client.close()
            }
        }
    }

    private data class CapturedRequest(val method: String, val path: String, val body: String)

    private sealed interface Reply {
        data class Http(
            val status: Int,
            val headers: Map<String, String> = emptyMap(),
            val body: String = "",
        ) : Reply
        data object Disconnect : Reply
    }

    private class LoopbackFixture(
        private val reply: (CapturedRequest, Int) -> Reply,
    ) : Closeable {
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        private val failure = AtomicReference<Throwable?>()
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "miclient-loopback-fixture").apply { isDaemon = true }
        }
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0,
        ).apply {
            this.executor = this@LoopbackFixture.executor
            createContext("/") { exchange -> handle(exchange) }
            start()
        }

        fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

        private fun handle(exchange: HttpExchange) {
            try {
                val request = CapturedRequest(exchange.requestMethod, exchange.requestURI.path,
                    exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
                requests += request
                when (val response = reply(request, requests.size)) {
                    Reply.Disconnect -> exchange.close()
                    is Reply.Http -> {
                        response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                        val bytes = response.body.toByteArray(Charsets.UTF_8)
                        exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
                        exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
                    }
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                exchange.close()
            }
        }

        fun assertHealthy() {
            failure.get()?.let { throw AssertionError("Local HTTP fixture failed", it) }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
