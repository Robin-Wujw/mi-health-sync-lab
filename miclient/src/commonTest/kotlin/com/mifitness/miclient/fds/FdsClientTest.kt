package com.mifitness.miclient.fds

import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.api.MiDataClient
import com.mifitness.miclient.auth.MiCredentials
import com.mifitness.miclient.crypto.MiCloudSigner
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull

class FdsClientTest {
    private val secret = "MDEyMzQ1Njc4OWFiY2RlZg=="
    private val objectKey = FdsKeys.encodeUrlSafeNoPad(ByteArray(16) { it.toByte() })
    private val credentials = MiCredentials("synthetic-user", secret, "synthetic-token", "", "synthetic-phone", "cn", "synthetic-cuser")
    private var request = FdsClient.SportFileRequest("default", 1_800_000_000, 32, 8, 0)
    private var code = 0
    private var wrongEntry = false
    private var objectWrites = 0
    private var addresses = 0
    private var stored = ""
    private var missingKey = false
    private var objectReadStatus = HttpStatusCode.OK

    private fun client(): FdsClient {
        val data = MiDataClient(credentials, HttpClient(MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method)
            assertTrue(req.url.encodedPath.startsWith("/healthapp/service/gen_"))
            assertEquals("hlth.io.mi.com", req.url.host)
            val cookie = req.headers["Cookie"].orEmpty()
            assertTrue("cUserId=synthetic-cuser" in cookie)
            val nonce = checkNotNull(req.url.parameters["_nonce"])
            val signedNonce = MiCloudSigner.computeSignedNonce(secret, nonce)
            val plaintext = MiCloudSigner.decryptResponse(signedNonce, checkNotNull(req.url.parameters["data"])).decodeToString()
            val signingPath = req.url.encodedPath.removePrefix("/healthapp")
            val expectedHash = encode(com.mifitness.miclient.crypto.Hash.sha1(
                "GET&$signingPath&data=$plaintext&$signedNonce".encodeToByteArray()))
            val actualHash = MiCloudSigner.decryptResponse(signedNonce, checkNotNull(req.url.parameters["rc4_hash__"]))
                .decodeToString()
            assertEquals(expectedHash, actualHash)
            val payload = Json.parseToJsonElement(plaintext).jsonObject
            assertEquals(JsonPrimitive("default"), payload["did"])
            addresses++
            val suffix = if (request.daily) FdsKeys.suffix("default", FdsKeys.dataIdKeyBytes(request.timeSec, 32, 32))
                else FdsKeys.suffixForSportFile("default", request.timeSec, 32, 8, 0)
            val item = (payload["items"] as kotlinx.serialization.json.JsonArray).single().jsonObject
            assertEquals(JsonPrimitive(suffix), item["suffix"])
            val response = buildJsonObject {
                put("code", code)
                put("result", buildJsonObject { put(if (wrongEntry) "unrelated-key" else FdsKeys.serverKey(suffix, request.timeSec),
                    buildJsonObject { put("url", "https://synthetic.invalid/object"); put("method", "PUT");
                        if (!missingKey) put("obj_key", objectKey) }) })
            }
            val encrypted = MiCloudSigner.decryptResponse(signedNonce, encode(response.toString().encodeToByteArray()))
            respond(encode(encrypted))
        }))
        val plain = HttpClient(MockEngine { req ->
            assertEquals("synthetic.invalid", req.url.host)
            if (req.method == HttpMethod.Put) {
                assertNull(req.body.contentType)
                assertNull(req.headers["Content-Type"])
                objectWrites++
                stored = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                assertTrue(stored.startsWith("\""))
                respond("")
            } else {
                assertEquals(HttpMethod.Get, req.method)
                respond(stored, objectReadStatus)
            }
        })
        return FdsClient(data, plain)
    }

    private fun encode(bytes: ByteArray) = kotlin.io.encoding.Base64.encode(bytes)

    @Test
    fun sleepFilesUseTheDailySuffixAndTheSameEncryptedTransport() = runTest {
        request = request.copy(daily = true)
        val fds = client()
        try {
            val bytes = byteArrayOf(11, 22, 33)
            fds.uploadSportFileVerified(request, bytes)
            assertTrue(fds.downloadSportFile(request).contentEquals(bytes))
            assertEquals(1, objectWrites)
        } finally { fds.close() }
    }

    @Test
    fun preparationNeverWritesBodyAndIndependentDownloadDecryptsExactUploadedBytes() = runTest {
        val fds = client()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val prepared = fds.prepareSportFile(request)
            assertEquals(0, objectWrites)
            fds.uploadPreparedSportFile(prepared, bytes)
            assertEquals(1, objectWrites)
            assertTrue(fds.downloadSportFile(request).contentEquals(bytes))
            assertEquals(2, addresses)
        } finally { fds.close() }
    }

    @Test
    fun preparationRejectionNeverSendsAnObjectBody() = runTest {
        code = -6
        val fds = client()
        try {
            assertFailsWith<MiApiException.Server> { fds.uploadSportFileVerified(request, byteArrayOf(1)) }
            assertEquals(0, objectWrites)
        } finally { fds.close() }
    }

    @Test
    fun absentEncryptionKeyNeedsActualObject404BeforeBeingReportedMissing() = runTest {
        missingKey = true
        objectReadStatus = HttpStatusCode.NotFound
        val fds = client()
        try {
            assertEquals(404, assertFailsWith<MiApiException.Server> { fds.downloadSportFile(request) }.httpOrBusinessCode)
            assertEquals(0, objectWrites)
        } finally { fds.close() }
    }

    @Test
    fun reachableObjectWithoutEncryptionKeyIsStillAnUnknownReadFailure() = runTest {
        missingKey = true
        val fds = client()
        try {
            assertFailsWith<MiApiException.Unexpected> { fds.downloadSportFile(request) }
            assertEquals(0, objectWrites)
        } finally { fds.close() }
    }

    @Test
    fun downloadNeverAcceptsAnotherObjectsKey() = runTest {
        wrongEntry = true
        val fds = client()
        try {
            assertFailsWith<MiApiException.Unexpected> { fds.downloadSportFile(request) }
            assertEquals(0, objectWrites)
        } finally { fds.close() }
    }
}
