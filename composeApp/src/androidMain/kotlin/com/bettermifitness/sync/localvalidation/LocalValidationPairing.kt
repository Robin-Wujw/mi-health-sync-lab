package com.bettermifitness.sync.localvalidation

import com.mifitness.miclient.auth.MiCredentials
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A short-lived LAN pairing request pins the computer's certificate before sending any session. */
data class LocalValidationPairing(val host: String, val port: Int, val pin: String, val code: String, val expires: Long) :
    ValidationPairRequest {
    override val recipientLabel: String get() = "$host:$port"
    override val fingerprint: String get() = pin
    override fun send(credentials: MiCredentials, now: Long) = sendLocalValidationSession(this, credentials, now)
    override fun toString(): String = "LocalValidationPairing(redacted)"

    companion object {
        fun parse(raw: String, now: Long): LocalValidationPairing {
            require(raw.length <= 2048)
            val uri = URI(raw)
            require(uri.scheme == "healthlab-pair" && uri.host == "session" && uri.fragment == null)
            val entries = checkNotNull(uri.rawQuery).split('&').map { entry ->
                val parts = entry.split('=', limit = 2)
                require(parts.size == 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
            require(entries.map { it.first }.distinct().size == entries.size)
            val params = entries.toMap()
            require(params.keys == setOf("host", "port", "pin", "code", "expires"))
            val host = params.getValue("host")
            val octets = host.split('.').map { it.toIntOrNull() ?: -1 }
            require(octets.size == 4 && octets.all { it in 0..255 } &&
                host == octets.joinToString("."))
            val privateAddress = octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 ||
                octets[0] == 192 && octets[1] == 168
            require(privateAddress && octets.last() !in setOf(0, 255))
            val port = params.getValue("port").toInt()
            require(port in 1024..65535)
            val pin = params.getValue("pin")
            val code = params.getValue("code")
            require(pin.matches(Regex("[0-9a-f]{64}")) && code.matches(Regex("[A-Za-z0-9_-]{32,64}")))
            val expires = params.getValue("expires").toLong()
            require(expires > now && expires - now <= 900)
            return LocalValidationPairing(host, port, pin, code, expires)
        }
    }
}

/** The refresh token and password deliberately stay on the phone; the PC cannot rotate its passport chain. */
fun localValidationSessionPayload(credentials: MiCredentials): JsonObject {
    require(credentials.userId.isNotBlank() && credentials.serviceToken.isNotBlank() &&
        credentials.ssecurity.isNotBlank() && credentials.deviceId.isNotBlank())
    return buildJsonObject {
        put("userId", credentials.userId); put("serviceToken", credentials.serviceToken)
        put("ssecurity", credentials.ssecurity); put("deviceId", credentials.deviceId)
        put("cUserId", credentials.cUserId); put("region", credentials.region)
        put("passToken", "")
    }
}

fun verifyLocalValidationCertificate(certificate: X509Certificate, pin: String) {
    certificate.checkValidity()
    val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
    val expected = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    require(MessageDigest.isEqual(actual, expected)) { "Pairing certificate mismatch" }
}

fun sendLocalValidationSession(request: LocalValidationPairing, credentials: MiCredentials, now: Long) {
    require(now < request.expires)
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw java.security.cert.CertificateException("Client certificates are not used")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing peer certificate")
            try { verifyLocalValidationCertificate(certificate, request.pin) }
            catch (error: Exception) { throw java.security.cert.CertificateException("Pairing certificate mismatch", error) }
        }
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    val url = URI("https", null, request.host, request.port, "/pair/session", null, null).toURL()
    val connection = url.openConnection() as HttpsURLConnection
    try {
        connection.sslSocketFactory = context.socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { hostname, _ -> hostname == request.host }
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000; connection.readTimeout = 15_000
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Pair-Code", request.code)
        val body = localValidationSessionPayload(credentials).toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        check(connection.responseCode == 200) { "Local pairing unconfirmed" }
        val response = connection.inputStream.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (output.size() <= 4096) {
                val count = stream.read(buffer, 0, minOf(buffer.size, 4097 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(response.size <= 4096)
        val result = Json.parseToJsonElement(response.decodeToString()) as? JsonObject
        check(result?.get("state") == kotlinx.serialization.json.JsonPrimitive("paired")) { "Local pairing unconfirmed" }
    } finally { connection.disconnect() }
}
