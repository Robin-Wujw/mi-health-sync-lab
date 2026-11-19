package com.bettermifitness.sync.localvalidation

import com.mifitness.miclient.auth.MiCredentials
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LocalValidationTlsTest {
    @Test
    fun pinnedTlsTransfersOnlyTheShortSessionAndWrongPinSendsNoPayload() {
        val loader = checkNotNull(javaClass.classLoader)
        val certificate = loader.getResourceAsStream("localvalidation/synthetic-cert.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val pem = loader.getResourceAsStream("localvalidation/synthetic-test-key.pem")!!.bufferedReader().use { it.readText() }
        val encoded = pem.lineSequence().filterNot { it.startsWith("---") }.joinToString("")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        val password = "synthetic-test-only".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null); setKeyEntry("test", key, password, arrayOf(certificate))
        }
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val context = SSLContext.getInstance("TLS").apply { init(manager.keyManagers, null, null) }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        val received = AtomicInteger()
        server.createContext("/pair/session") { exchange ->
            val value = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            assertEquals(JsonPrimitive(""), value["passToken"])
            assertEquals(JsonPrimitive("synthetic-service"), value["serviceToken"])
            assertEquals("b".repeat(43), exchange.requestHeaders.getFirst("X-Pair-Code"))
            received.incrementAndGet()
            val response = "{\"state\":\"paired\"}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val pin = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
            val now = System.currentTimeMillis() / 1000
            val request = LocalValidationPairing("127.0.0.1", server.address.port, pin, "b".repeat(43), now + 60)
            val credentials = MiCredentials("123", "synthetic-security", "synthetic-service", "MUST-STAY-ON-PHONE",
                "synthetic-device", "cn")
            assertFails { sendLocalValidationSession(request.copy(pin = "0".repeat(64)), credentials, now) }
            assertEquals(0, received.get())
            sendLocalValidationSession(request, credentials, now)
            assertEquals(1, received.get())
            assertFails { sendLocalValidationSession(request, credentials, now + 61) }
            assertEquals(1, received.get())
        } finally { server.stop(0) }
    }
}
