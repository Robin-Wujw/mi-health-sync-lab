package com.mifitness.miclient.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

@OptIn(ExperimentalEncodingApi::class)
class MiCloudHttpMethodTest {
    private val path = "/app/v1/data/get_aggregated_fitness_data_by_time"
    private val security = Base64.encode("test-ssecurity-16b".encodeToByteArray())
    private val body = """{"key":"sleep","tag":"daily_report"}"""

    @Test
    fun getUsesGetForBothSignaturesAndRetainsDecryptablePayload() {
        val request = MiCloudSigner.buildEncryptedRequest(path, security, body, method = "GET")
        val hashInput = "GET&$path&data=$body&${request.signedNonce}"
        val expectedPlainHash = Base64.encode(Hash.sha1(hashInput.encodeToByteArray()))
        assertEquals(expectedPlainHash,
            MiCloudSigner.decryptResponse(request.signedNonce, request.rc4Hash).decodeToString())
        assertEquals(signature("GET", request), request.signature)
        assertNotEquals(signature("POST", request), request.signature)
        assertEquals(body, MiCloudSigner.decryptResponse(request.signedNonce, request.data).decodeToString())
    }

    @Test
    fun existingCallersStillSignAsPostAndUnsupportedMethodsAreRejected() {
        val request = MiCloudSigner.buildEncryptedRequest(path, security, body)
        assertEquals(signature("POST", request), request.signature)
        assertFailsWith<IllegalArgumentException> { MiCloudSigner.buildEncryptedRequest(path, security, body, "DELETE") }
    }

    private fun signature(method: String, request: EncryptedRequest): String = Base64.encode(Hash.sha1(
        "$method&$path&data=${request.data}&rc4_hash__=${request.rc4Hash}&${request.signedNonce}".encodeToByteArray(),
    ))
}
