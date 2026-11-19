package com.bettermifitness.sync.localvalidation

import com.mifitness.miclient.auth.MiCredentials
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalValidationPairingTest {
    private val now = 1_800_000_000L
    private val uri = "healthlab-pair://session?host=192.168.31.88&port=8769&pin=" + "a".repeat(64) +
        "&code=" + "b".repeat(43) + "&expires=" + (now + 600)

    @Test fun validPrivateComputerHasAnExactPinnedCertificateAndExpiry() {
        val parsed = LocalValidationPairing.parse(uri, now)
        assertEquals("192.168.31.88", parsed.host)
        assertEquals(8769, parsed.port)
        assertEquals(now + 600, parsed.expires)
        assertEquals("LocalValidationPairing(redacted)", parsed.toString())
    }
    @Test fun expiredAndUnboundedPairingLinksAreRejected() {
        assertFailsWith<IllegalArgumentException> { LocalValidationPairing.parse(uri, now + 601) }
        assertFailsWith<IllegalArgumentException> { LocalValidationPairing.parse(uri, now - 1000) }
    }
    @Test fun publicHostsLocalServicesAndDnsRebindingNamesAreRejected() {
        for (host in listOf("8.8.8.8", "127.0.0.1", "169.254.169.254", "192.168.31.88.evil.test", "localhost",
            "192.168.31.0", "192.168.31.255", "192.168.031.88", "192.168.31.88@evil.test")) {
            assertFailsWith<IllegalArgumentException> { LocalValidationPairing.parse(uri.replace("192.168.31.88", host), now) }
        }
    }
    @Test fun missingOrMalformedCertificateAndPairCodeAreRejected() {
        assertFailsWith<IllegalArgumentException> { LocalValidationPairing.parse(uri.replace("a".repeat(64), "abc"), now) }
        assertFailsWith<IllegalArgumentException> { LocalValidationPairing.parse(uri.replace("b".repeat(43), "123"), now) }
    }
    @Test fun duplicateParametersAndUnexpectedDestinationsAreRejected() {
        assertFailsWith<IllegalArgumentException> { LocalValidationPairing.parse("$uri&host=10.0.0.1", now) }
        assertFailsWith<IllegalArgumentException> { LocalValidationPairing.parse("$uri&redirect=https://evil.test", now) }
        assertFailsWith<IllegalArgumentException> { LocalValidationPairing.parse(uri.replace("healthlab-pair", "https"), now) }
    }
    @Test fun payloadContainsOnlyTheShortHealthSessionAndNeverThePassportToken() {
        val c = MiCredentials("123", "synthetic-security", "synthetic-service", "DO-NOT-EXPORT",
            "synthetic-device", "cn", "synthetic-encrypted-user")
        val payload = localValidationSessionPayload(c)
        assertEquals(JsonPrimitive(""), payload["passToken"])
        assertEquals(JsonPrimitive("synthetic-service"), payload["serviceToken"])
        assertFalse(payload.toString().contains("DO-NOT-EXPORT"))
        assertTrue("password" !in payload)
    }
}
