package com.bettermifitness.sync.localvalidation

import com.mifitness.miclient.auth.MiCredentials
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PublicValidationPairingTest {
    private val now = 1_800_000_000L
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val base64 = Base64.getUrlEncoder().withoutPadding()
    private val pin = MessageDigest.getInstance("SHA-256").digest(keys.public.encoded).joinToString("") {
        "%02x".format(it.toInt() and 255)
    }
    private val uri = "healthlab-pair://session?transport=sealed&endpoint=" +
        URLEncoder.encode("https://synthetic-recipient.trycloudflare.com/pair/session", "UTF-8") +
        "&pin=$pin&code=${"b".repeat(43)}&expires=${now + 600}#key=${base64.encodeToString(keys.public.encoded)}"

    @Test fun remoteRequestsPinTheRecipientKeyAndRetainThePrivateLanGuard() {
        val request = assertIs<PublicValidationPairing>(parseValidationPairRequest(uri, now))
        assertEquals(pin, request.fingerprint)
        assertEquals("PublicValidationPairing(redacted)", request.toString())
        assertFails { LocalValidationPairing.parse(uri, now) }
    }
    @Test fun arbitraryRelayOriginsCredentialsPathsAndQueriesAreRejected() {
        for (url in listOf("http://synthetic-recipient.trycloudflare.com/pair/session",
            "https://synthetic-recipient.trycloudflare.com.evil.test/pair/session", "https://127.0.0.1/pair/session",
            "https://user@synthetic-recipient.trycloudflare.com/pair/session",
            "https://synthetic-recipient.trycloudflare.com:8443/pair/session",
            "https://synthetic-recipient.trycloudflare.com/another", "https://synthetic-recipient.trycloudflare.com/pair/session?q=x")) {
            val changed = uri.replace(URLEncoder.encode("https://synthetic-recipient.trycloudflare.com/pair/session", "UTF-8"),
                URLEncoder.encode(url, "UTF-8"))
            assertFails { PublicValidationPairing.parse(changed, now) }
        }
    }
    @Test fun expiredDuplicateAndChangedRecipientRequestsAreRejected() {
        assertFails { PublicValidationPairing.parse(uri, now + 601) }
        assertFails { PublicValidationPairing.parse(uri.replace("#key=", "&transport=sealed#key="), now) }
        assertFails { PublicValidationPairing.parse(uri.replace(pin, "0".repeat(64)), now) }
        assertFails { PublicValidationPairing.parse(uri.substringBefore('#'), now) }
    }
    @Test fun wrappedSessionCanOnlyBeOpenedByTheRecipientAndOmitsLongLivedTokens() {
        val request = PublicValidationPairing.parse(uri, now)
        val credentials = MiCredentials("123", "synthetic-security", "synthetic-service", "DO-NOT-EXPORT",
            "synthetic-device", "cn")
        val sealed = sealValidationSession(request, credentials)
        assertFalse(sealed.envelope.toString().contains("synthetic-service"))
        assertFalse(sealed.envelope.toString().contains("DO-NOT-EXPORT"))
        val wrapper = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.DECRYPT_MODE, keys.private,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        }
        fun binary(name: String) = Base64.getUrlDecoder().decode((sealed.envelope.getValue(name) as JsonPrimitive).content)
        val key = wrapper.doFinal(binary("wrapped_key"))
        val decoder = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, binary("nonce")))
            updateAAD(pairingAssociatedData(request))
        }
        val plaintext = Json.parseToJsonElement(decoder.doFinal(binary("ciphertext")).decodeToString()).jsonObject
        assertEquals(JsonPrimitive("synthetic-service"), plaintext["serviceToken"])
        assertEquals(JsonPrimitive(""), plaintext["passToken"])
    }
    @Test fun aRelayCannotForgeTheReceipt() {
        val request = PublicValidationPairing.parse(uri, now)
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(pairingAssociatedData(request, acknowledgement = true))
        }
        val response = buildJsonObject {
            put("nonce", base64.encodeToString(nonce)); put("ack", base64.encodeToString(cipher.doFinal("paired".toByteArray())))
        }
        verifyPairingAcknowledgement(request, key, response)
        assertFails { verifyPairingAcknowledgement(request.copy(code = "c".repeat(43)), key, response) }
        assertFails { verifyPairingAcknowledgement(request, ByteArray(32), response) }
        assertFails { verifyPairingAcknowledgement(request, key, JsonObject(emptyMap())) }
    }
}
