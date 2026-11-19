package com.bettermifitness.sync.lab

import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthStore
import com.mifitness.miclient.fds.FdsKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import com.mifitness.miclient.fds.FdsAes

/** Address-only protocol reads. Never sends a workout/file payload or exports signed URLs/keys. */
internal suspend fun inspectFdsProtocol(
    session: MiSessionManager, store: HealthStore, from: Long, until: Long,
): String {
    val source = store.readWorkouts(HealthInboundReadRequest(from, until, setOf("com.huami.watch.hmwatchmanager")))
        .first { it.session.heartRateSeries.isNotEmpty() }
    val start = source.session.startTime
    val lines = mutableListOf("fds_probe.file_body_uploads=0", "fds_probe.summary_uploads=0")
    val client = session.api.dataClient()
    for ((kind, sid) in mapOf("phone" to client.phoneId, "manual" to "xiaomiwear_app_manually")) {
        val actualSuffix = FdsKeys.suffixForSportFile(sid, start, checkNotNull(source.session.tzIn15Min), 8, 0)
        val actualPayload = mapOf("did" to sid, "items" to listOf(mapOf("timestamp" to start, "suffix" to actualSuffix)))
        for (operation in listOf("download", "upload")) {
            val read = FdsRead(client.healthHost, "GET", "/service/gen_${operation}_url", "${kind}_$operation", operation)
            lines += probeFdsRead(client, read, "fds_probe.${read.label}", actualPayload)
        }
    }
    return lines.joinToString("\n")
}

private data class FdsRead(val host: String, val method: String, val sign: String, val label: String,
    val operation: String = "download")

private suspend fun probeFdsRead(client: com.mifitness.miclient.api.MiDataClient, read: FdsRead, label: String,
    payload: Map<String, Any?>): List<String> {
    return try {
        val path = "/healthapp/service/gen_${read.operation}_url"
        val response = withTimeoutOrNull(15_000) {
            if (read.method == "GET") client.get(path, signPath = read.sign, payload = payload, host = read.host)
            else client.post(path, signPath = read.sign, payload = payload, host = read.host)
        } ?: return listOf("$label.error=READ_TIMEOUT")
        val root = response as? JsonObject
        val message = listOf("message", "msg", "description").mapNotNull { (root?.get(it) as? JsonPrimitive)?.content }
            .joinToString(" ").lowercase()
        val keywords = listOf("sign", "nonce", "param", "device", "user", "login", "object", "found", "auth", "invalid",
            "illegal", "wrong", "missing", "denied", "permission", "encrypt", "request", "data", "cookie")
        val result = root?.get("result") as? JsonObject
        val entry = result?.values?.singleOrNull() as? JsonObject
        val names = setOf("url", "method", "obj_key", "obj_name", "expires_time", "result", "items", "data", "urls", "files")
        val item = (payload["items"] as? List<*>)?.singleOrNull() as? Map<*, *>
        val suffix = item?.get("suffix")?.toString()
        val time = item?.get("timestamp")?.toString()
        listOf("$label.code=" + ((root?.get("code") as? JsonPrimitive)?.intOrNull ?: "UNKNOWN"),
            "$label.has_result=" + (root?.get("result") is JsonObject),
            "$label.message_keywords=" + keywords.filter { it in message }.joinToString(","),
            "$label.result_entries=" + result?.size,
            "$label.has_exact_entry=" + (result?.containsKey("${suffix}_$time") == true),
            "$label.has_suffix_entry=" + (suffix != null && result?.containsKey(suffix) == true),
            "$label.result_fields=" + result?.keys?.intersect(names)?.sorted()?.joinToString(","),
            "$label.entry_fields=" + entry?.keys?.intersect(names)?.sorted()?.joinToString(","),
            "$label.secure_url=" + ((entry?.get("url") as? JsonPrimitive)?.content?.startsWith("https://") == true),
            "$label.method=" + ((entry?.get("method") as? JsonPrimitive)?.content?.takeIf { it in setOf("PUT", "POST", "GET") }
                ?: "UNKNOWN"),
            "$label.valid_key=" + runCatching {
                FdsAes.decodeObjectKey((entry?.get("obj_key") as? JsonPrimitive)?.content.orEmpty()).size == 16
            }.getOrDefault(false))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { listOf("$label.error=" + when (error) {
        is com.mifitness.miclient.api.MiApiException.Server -> "SERVER_${error.httpOrBusinessCode}"
        is com.mifitness.miclient.api.MiApiException.AuthExpired -> "AUTH_REJECTED"
        else -> error::class.simpleName
    }) }
}
