package com.mifitness.miclient.fds

import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.api.MiDataClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Downloads and decrypts Mi Fitness FDS objects (sport GPS, records, …).
 *
 * Endpoint: `GET /healthapp/service/gen_download_url` (signed path `/service/gen_download_url`).
 */
class FdsClient(
    private val dataClient: MiDataClient,
    private val plainHttp: HttpClient = com.mifitness.miclient.createPlatformHttpClient(allowAutomaticReplay = false),
) : SportFileTransport {

    data class UploadMeta(val url: String, val method: String, val objectKey: String)

    data class DownloadMeta(
        val url: String,
        val objectName: String,
        val objectKey: String,
        val method: String,
        val expireTime: Long?,
        val serverKey: String,
    )

    data class SportFileRequest(
        /** Device / app source id (`did` in sport JSON, column `sid` in local DB). */
        val sid: String,
        val timeSec: Long,
        val tzIn15Min: Int,
        /** `proto_type` from sport report (not always equal to `sport_type`). */
        val protoType: Int,
        /** [FdsKeys.FILE_TYPE_RECORD], [FdsKeys.FILE_TYPE_GPS], [FdsKeys.FILE_TYPE_RECOVER_RATE]. */
        val fileType: Int = FdsKeys.FILE_TYPE_GPS,
        val daily: Boolean = false,
    )

    suspend fun genDownloadUrl(
        sid: String,
        timeSec: Long,
        suffix: String,
    ): DownloadMeta {
        val payload = mapOf(
            "did" to sid,
            "items" to listOf(
                mapOf(
                    "timestamp" to timeSec,
                    "suffix" to suffix,
                ),
            ),
        )
        val element = dataClient.get(
            path = "/healthapp/service/gen_download_url",
            signPath = "/service/gen_download_url",
            payload = payload,
        )
        val root = element.jsonObject
        val code = root["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: throw MiApiException.Unexpected("gen_download_url missing status")
        if (code != 0) {
            val msg = root["message"]?.jsonPrimitive?.contentOrNull
                ?: root["msg"]?.jsonPrimitive?.contentOrNull
                ?: "gen_download_url failed"
            throw MiApiException.Server(code, msg)
        }
        val result = root["result"]?.jsonObject
            ?: throw MiApiException.Unexpected("gen_download_url missing result")
        val key = FdsKeys.serverKey(suffix, timeSec)
        val entry = result[key]?.jsonObject
            ?: throw MiApiException.Unexpected("gen_download_url: missing requested entry")
        val url = entry["url"]?.jsonPrimitive?.contentOrNull
            ?: throw MiApiException.Unexpected("gen_download_url: missing url")
        val objectKey = entry["obj_key"]?.jsonPrimitive?.contentOrNull
            ?: missingObjectKey(url)
        return DownloadMeta(
            url = url,
            objectName = entry["obj_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            objectKey = objectKey,
            method = entry["method"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            expireTime = entry["expires_time"]?.jsonPrimitive?.longOrNull,
            serverKey = key,
        )
    }

    /** Missing encryption metadata alone does not prove absence. Ask the supplied
     * HTTPS object URL; only an actual HTTP rejection establishes a missing file. */
    private suspend fun missingObjectKey(url: String): Nothing {
        if (!url.startsWith("https://")) throw MiApiException.Unexpected("FDS download requires HTTPS")
        plainHttp.prepareGet(url).execute { response ->
            if (response.status.value !in 200..299) throw MiApiException.Server(response.status.value, "FDS download rejected")
        }
        throw MiApiException.Unexpected("gen_download_url: missing obj_key")
    }

    suspend fun downloadDecrypted(meta: DownloadMeta): ByteArray {
        require(meta.url.startsWith("https://"))
        val response = plainHttp.get(meta.url)
        if (response.status.value !in 200..299) throw MiApiException.Server(response.status.value, "FDS download rejected")
        val raw = response.bodyAsText().trim()
        val body = if (raw.startsWith("\"")) (Json.parseToJsonElement(raw) as JsonPrimitive).content else raw
        if (body.isBlank()) {
            throw MiApiException.Unexpected("FDS download empty body")
        }
        return try {
            FdsAes.decryptBase64Ciphertext(meta.objectKey, body)
        } catch (e: Exception) {
            throw MiApiException.Unexpected("FDS AES decrypt failed: ${e.message}", e)
        }
    }

    /** Sport FDS file (record=0, GPS=2, recover=3). */
    override suspend fun downloadSportFile(request: SportFileRequest): ByteArray {
        val suffix = fileSuffix(request)
        val meta = genDownloadUrl(request.sid, request.timeSec, suffix)
        return downloadDecrypted(meta)
    }

    /** Exactly one native AES upload, followed by independent download/decrypt/byte comparison. */
    suspend fun uploadSportFileVerified(request: SportFileRequest, bytes: ByteArray) {
        val meta = prepareSportFile(request)
        uploadPreparedSportFile(meta, bytes)
        check(downloadSportFile(request).contentEquals(bytes)) { "FDS object readback mismatch" }
    }

    /** Address preparation does not write a file body and is safe to repeat. */
    override suspend fun prepareSportFile(request: SportFileRequest): UploadMeta {
        require(request.sid.isNotBlank() && request.protoType in 1..31)
        val suffix = fileSuffix(request)
        val response = dataClient.get(path = "/healthapp/service/gen_upload_url", signPath = "/service/gen_upload_url",
            payload = mapOf("did" to request.sid, "items" to listOf(mapOf("timestamp" to request.timeSec, "suffix" to suffix))))
            .jsonObject
        val uploadCode = response["code"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: error("Missing FDS upload URL status")
        if (uploadCode != 0) throw MiApiException.Server(uploadCode, "FDS upload URL rejected")
        val entry = response["result"]?.jsonObject?.get(FdsKeys.serverKey(suffix, request.timeSec))?.jsonObject
            ?: error("Missing FDS upload target")
        val url = entry["url"]?.jsonPrimitive?.content ?: error("Missing FDS upload URL")
        val method = entry["method"]?.jsonPrimitive?.content ?: error("Missing FDS upload method")
        val objectKey = entry["obj_key"]?.jsonPrimitive?.content ?: error("Missing FDS upload key")
        require(url.startsWith("https://") && method in setOf("PUT", "POST"))
        return UploadMeta(url, method, objectKey)
    }

    /** Caller must durably reserve this body attempt before invoking this method. */
    override suspend fun uploadPreparedSportFile(meta: UploadMeta, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= 8_000_000)
        require(meta.url.startsWith("https://") && meta.method in setOf("PUT", "POST"))
        val encrypted = FdsKeys.encodeUrlSafeNoPad(FdsAes.encrypt(FdsAes.decodeObjectKey(meta.objectKey), bytes))
        val body = JsonPrimitive(encrypted).toString().encodeToByteArray()
        val uploaded = plainHttp.request(meta.url) {
            this.method = HttpMethod.parse(meta.method)
            // Native RequestBody has a null MIME type. Presigned FDS requests reject the
            // text/plain Content-Type that Ktor automatically adds for a String body.
            setBody(object : OutgoingContent.ByteArrayContent() {
                override fun bytes(): ByteArray = body
            })
        }
        if (uploaded.status.value !in 200..299) throw MiApiException.Server(uploaded.status.value, "FDS object upload rejected")
    }

    /** Sport GPS file (`fileType = 2`). */
    suspend fun downloadSportGps(request: SportFileRequest): ByteArray =
        downloadSportFile(request.copy(fileType = FdsKeys.FILE_TYPE_GPS))

    fun close() {
        plainHttp.close()
    }

    private fun fileSuffix(request: SportFileRequest): String {
        require(request.timeSec in 1..Int.MAX_VALUE.toLong() && request.tzIn15Min in -48..56)
        return if (request.daily) {
            require(request.protoType == 8 && request.fileType == FdsKeys.FILE_TYPE_RECORD)
            FdsKeys.suffix(request.sid, FdsKeys.dataIdKeyBytes(request.timeSec, request.tzIn15Min,
                FdsKeys.genDataTypeByte(dataType = 0, dailyType = request.protoType, fileType = request.fileType)))
        } else FdsKeys.suffixForSportFile(request.sid, request.timeSec, request.tzIn15Min,
            request.protoType, request.fileType)
    }
}
