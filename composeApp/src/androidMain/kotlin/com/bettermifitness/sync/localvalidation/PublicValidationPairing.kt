package com.bettermifitness.sync.localvalidation

import com.mifitness.miclient.auth.MiCredentials
import java.net.URI
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface ValidationPairRequest {
    val recipientLabel: String
    val fingerprint: String
    fun send(credentials: MiCredentials, now: Long)
}

fun parseValidationPairRequest(raw: String, now: Long): ValidationPairRequest =
    if (URI(raw).rawFragment != null) PublicValidationPairing.parse(raw, now) else LocalValidationPairing.parse(raw, now)

/** The relay transports an encrypted envelope. Only the computer holding the RSA private key can open it. */
data class PublicValidationPairing(
    val endpoint: String,
    val publicKey: PublicKey,
    override val fingerprint: String,
    val code: String,
    val expires: Long,
) : ValidationPairRequest {
    override val recipientLabel: String get() = "通过互联网连接你的验证电脑"
    override fun toString(): String = "PublicValidationPairing(redacted)"
    override fun send(credentials: MiCredentials, now: Long) = sendPublicValidationSession(this, credentials, now)

    companion object {
        fun parse(raw: String, now: Long): PublicValidationPairing {
            require(raw.length <= 4096)
            val uri = URI(raw)
            require(uri.scheme == "healthlab-pair" && uri.host == "session" && uri.rawPath.orEmpty().isEmpty())
            val pairs = checkNotNull(uri.rawQuery).split('&').map {
                val split = it.split('=', limit = 2)
                require(split.size == 2)
                URLDecoder.decode(split[0], "UTF-8") to URLDecoder.decode(split[1], "UTF-8")
            }
            require(pairs.map { it.first }.distinct().size == pairs.size)
            val params = pairs.toMap()
            require(params.keys == setOf("transport", "endpoint", "pin", "code", "expires"))
            require(params.getValue("transport") == "sealed")
            val endpoint = params.getValue("endpoint")
            val target = URI(endpoint)
            val host = checkNotNull(target.host)
            require(host.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*\\.trycloudflare\\.com")))
            require(endpoint == "https://$host/pair/session")
            val code = params.getValue("code")
            val pin = params.getValue("pin")
            require(code.matches(Regex("[A-Za-z0-9_-]{32,64}")) && pin.matches(Regex("[0-9a-f]{64}")))
            val expires = params.getValue("expires").toLong()
            require(expires > now && expires - now <= 900)
            val fragment = checkNotNull(uri.rawFragment)
            require(fragment.startsWith("key=") && fragment.removePrefix("key=").matches(Regex("[A-Za-z0-9_-]{350,1500}")))
            val encoded = Base64.getUrlDecoder().decode(fragment.removePrefix("key="))
            val expected = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(encoded), expected))
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded)) as RSAPublicKey
            require(key.modulus.bitLength() in 2048..4096)
            return PublicValidationPairing(endpoint, key, pin, code, expires)
        }
    }
}

internal data class SealedValidationSession(val envelope: JsonObject, val key: ByteArray)

internal fun pairingAssociatedData(request: PublicValidationPairing, acknowledgement: Boolean = false): ByteArray =
    ("healthlab-public-pair-v1|${request.code}|${request.expires}|${request.fingerprint}" +
        if (acknowledgement) "|ack" else "").toByteArray(Charsets.UTF_8)

internal fun sealValidationSession(request: PublicValidationPairing, credentials: MiCredentials): SealedValidationSession {
    val random = SecureRandom()
    val key = ByteArray(32).also(random::nextBytes)
    val nonce = ByteArray(12).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(pairingAssociatedData(request))
    }
    val plaintext = localValidationSessionPayload(credentials).toString().toByteArray(Charsets.UTF_8)
    require(plaintext.size <= 16_384)
    val encrypted = cipher.doFinal(plaintext)
    val wrapper = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
        init(Cipher.ENCRYPT_MODE, request.publicKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
    }
    val base64 = Base64.getUrlEncoder().withoutPadding()
    return SealedValidationSession(buildJsonObject {
        put("version", 1); put("expires", request.expires); put("recipient", request.fingerprint)
        put("wrapped_key", base64.encodeToString(wrapper.doFinal(key)))
        put("nonce", base64.encodeToString(nonce)); put("ciphertext", base64.encodeToString(encrypted))
    }, key)
}

internal fun verifyPairingAcknowledgement(request: PublicValidationPairing, key: ByteArray, response: JsonObject) {
    fun binary(name: String) = Base64.getUrlDecoder().decode((response[name] as? JsonPrimitive)?.content ?: error("Missing receipt"))
    val nonce = binary("nonce")
    require(nonce.size == 12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(pairingAssociatedData(request, acknowledgement = true))
    }
    check(cipher.doFinal(binary("ack")).decodeToString() == "paired") { "Unverified pairing receipt" }
}

private fun sendPublicValidationSession(request: PublicValidationPairing, credentials: MiCredentials, now: Long) {
    require(now < request.expires)
    val sealed = sealValidationSession(request, credentials)
    val connection = URI(request.endpoint).toURL().openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000; connection.readTimeout = 30_000
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Pair-Code", request.code)
        val body = sealed.envelope.toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        check(connection.responseCode == 200) { "Pairing not confirmed" }
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
        verifyPairingAcknowledgement(request, sealed.key, Json.parseToJsonElement(response.decodeToString()) as JsonObject)
    } finally {
        sealed.key.fill(0)
        connection.disconnect()
    }
}
