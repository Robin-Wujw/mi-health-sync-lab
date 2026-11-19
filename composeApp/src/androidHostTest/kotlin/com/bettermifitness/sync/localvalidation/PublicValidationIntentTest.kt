package com.bettermifitness.sync.localvalidation

import android.content.Intent
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PublicValidationIntentTest {
    @Test fun chromeIntentRetainsTheRecipientKeyFragmentAndPackage() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val pin = MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        val uri = "healthlab-pair://session?transport=sealed&endpoint=" +
            URLEncoder.encode("https://synthetic-recipient.trycloudflare.com/pair/session", "UTF-8") +
            "&pin=$pin&code=${"b".repeat(43)}&expires=1800000600#key=" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(key.encoded)
        val chromeLink = uri.replaceFirst("healthlab-pair://", "intent://") +
            "#Intent;scheme=healthlab-pair;package=com.bettermifitness.sync.healthlab;end"
        val intent = Intent.parseUri(chromeLink, Intent.URI_INTENT_SCHEME)
        assertEquals("com.bettermifitness.sync.healthlab", intent.`package`)
        assertEquals(uri, intent.dataString)
        assertIs<PublicValidationPairing>(parseValidationPairRequest(checkNotNull(intent.dataString), 1_800_000_000))
    }
}
