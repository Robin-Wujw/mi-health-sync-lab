package com.bettermifitness.sync.data.api

import com.mifitness.miclient.api.MiApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Resolves the current session for each call. The caller owns auth refresh,
 * durable pending-write state and readback after any uncertain POST outcome.
 */
class MiDirectHealthImportCloud(
    private val apiProvider: () -> MiDirectApi,
    private val maxPages: Int = 80,
) : MiHealthImportCloud {
    constructor(api: MiDirectApi) : this({ api })

    override suspend fun readRows(
        channel: MiHealthImportChannel,
        key: String,
        fromEpochSeconds: Long,
        untilEpochSeconds: Long,
    ): List<MiHealthImportCloudRow> {
        require(fromEpochSeconds >= 0 && untilEpochSeconds > fromEpochSeconds)
        require(key in SUPPORTED_IMPORT_KEYS)
        val client = apiProvider().dataClient()
        val cursor = MiPageCursorGuard(maxPages)
        var next: String? = null
        val all = mutableListOf<MiHealthImportCloudRow>()
        do {
            val payload = buildMiHealthImportReadPayload(channel, key, fromEpochSeconds, untilEpochSeconds, next)
            val response = requestWithKnownHttpStatus {
                when (channel) {
                    MiHealthImportChannel.RAW -> client.post("/app/v1/data/get_fitness_data_by_time", payload = payload)
                    MiHealthImportChannel.DAILY_AGGREGATE ->
                        client.get("/app/v1/data/get_aggregated_fitness_data_by_time", payload = payload)
                }
            }
            val page = parseMiHealthImportReadPage(response, channel, key)
            all += page.rows.filter { it.time >= fromEpochSeconds && it.time < untilEpochSeconds }
            next = cursor.nextCursor(page.hasMore, page.nextKey)
        } while (next != null)
        return all.distinct()
    }

    override suspend fun readRawRowsWithoutStart(
        key: String,
        untilEpochSeconds: Long,
        maxPages: Int,
    ): MiHealthImportHistoryRead = readMiRawHistoryPages(key, untilEpochSeconds, maxPages) { payload ->
        requestWithKnownHttpStatus {
            apiProvider().dataClient().post("/app/v1/data/get_fitness_data_by_time", payload = payload)
        }
    }

    override suspend fun uploadRows(
        rows: List<MiHealthImportRow>,
        experimentalEnabled: Boolean,
    ): MiHealthImportUploadResult {
        check(experimentalEnabled) { "Experimental Health Connect to Mi import is disabled." }
        require(rows.isNotEmpty()) { "An empty batch is not a health-data import." }
        require(rows.map { it.identity.channel }.distinct().size == 1) { "Raw and aggregate writes require separate batches." }
        val channel = rows.first().identity.channel
        require(rows.size <= if (channel == MiHealthImportChannel.RAW) MI_HEALTH_IMPORT_RAW_BATCH_SIZE
            else MI_HEALTH_IMPORT_AGGREGATE_BATCH_SIZE) { "Import batch exceeds the bounded upload size." }
        require(rows.all { it.identity.key in SUPPORTED_IMPORT_KEYS && it.identity.sid == MI_HEALTH_CONNECT_IMPORT_SID })
        require(rows.map { it.identity }.distinct().size == rows.size) {
            "Duplicate target identities require conflict resolution before upload."
        }
        val client = apiProvider().dataClient()
        val payload = buildMiHealthImportUploadPayload(rows, client.phoneId)
        val path = if (channel == MiHealthImportChannel.RAW) "/app/v1/data/up_fitness_data"
            else "/app/v1/data/up_aggregated_fitness_data"
        // Exactly one attempt. A timeout is not evidence that this write failed.
        val response = requestWithKnownHttpStatus { client.encryptedPost(path = path, payload = payload.toString()) }
        return parseMiHealthImportUploadResponse(response)
    }
}

private val SUPPORTED_IMPORT_KEYS = setOf("heart_rate", "resting_heart_rate", "sleep", "spo2")

internal fun buildMiHealthImportReadPayload(channel: MiHealthImportChannel, key: String,
    from: Long, until: Long, next: String?): Map<String, Any?> = buildMap {
    put("key", key)
    put("start_time", from)
    put("end_time", until)
    put("reverse", true)
    // RAW follows the native by-time schema, which has no limit field.
    // Let the endpoint return its normal page instead of requesting 100 rows.
    if (channel == MiHealthImportChannel.DAILY_AGGREGATE) {
        put("limit", 100)
        put("tag", "daily_report")
    }
    if (next != null) put("next_key", next)
}

/**
 * Mi Fitness 3.59.2 CloudSyncLineRouter.pullFullPullFitnessData constructs
 * GetFitnessDataByTime with a null startTime and the default Gson omits it.
 * That model has no limit field. Keep this a separate, bounded control read:
 * do not silently change normal import preflight semantics.
 */
internal suspend fun readMiRawHistoryPages(
    key: String,
    untilEpochSeconds: Long,
    maxPages: Int,
    request: suspend (Map<String, Any?>) -> JsonElement,
): MiHealthImportHistoryRead {
    require(key in SUPPORTED_IMPORT_KEYS)
    require(untilEpochSeconds > 0)
    require(maxPages in 1..(if (key == "heart_rate") 128 else 32))
    val all = mutableListOf<MiHealthImportCloudRow>()
    val seen = mutableSetOf<String>()
    var next: String? = null
    var pagesRead = 0
    while (pagesRead < maxPages) {
        val payload = buildMap<String, Any?> {
            put("key", key)
            put("end_time", untilEpochSeconds)
            put("reverse", true)
            if (next != null) put("next_key", next)
        }
        val page = parseMiHealthImportReadPage(request(payload), MiHealthImportChannel.RAW, key,
            strictPagination = false)
        pagesRead++
        all += page.rows.filter { it.time >= 0 && it.time < untilEpochSeconds }
        // An omitted, malformed, or nullable pagination field does not establish
        // EOF. Retain valid rows but stop without claiming complete traversal.
        if (!page.paginationReported) return MiHealthImportHistoryRead(
            all.distinct(), pagesRead, false, MiHistoryStopReason.INVALID_PAGINATION)
        if (!page.hasMore) return MiHealthImportHistoryRead(all.distinct(), pagesRead, true)
        val candidate = page.nextKey
        if (candidate.isNullOrBlank()) return MiHealthImportHistoryRead(
            all.distinct(), pagesRead, false, MiHistoryStopReason.MISSING_CURSOR)
        if (!seen.add(candidate)) return MiHealthImportHistoryRead(all.distinct(), pagesRead, false, MiHistoryStopReason.REPEATED_CURSOR)
        next = candidate
    }
    return MiHealthImportHistoryRead(all.distinct(), pagesRead, false, MiHistoryStopReason.PAGE_LIMIT)
}

internal fun parseMiHealthImportUploadResponse(response: JsonElement): MiHealthImportUploadResult {
    val obj = response as? JsonObject ?: throw MiApiException.Unexpected("Mi import returned a non-object response")
    val code = (obj["code"] as? JsonPrimitive)?.intOrNull
        ?: throw MiApiException.Unexpected("Mi import returned no business status")
    val (safeMessage, withheld) = safeMiHealthImportMessage(obj.serverMessage())
    val result = obj["result"]
    val nested = (result as? JsonObject)?.get("result")
    fun JsonElement?.strictBoolean(): Boolean? = (this as? JsonPrimitive)
        ?.takeUnless { it.isString }?.booleanOrNull
    val shape = when {
        result == null -> MiHealthImportUploadResultShape.MISSING
        result == JsonNull -> MiHealthImportUploadResultShape.NULL
        result.strictBoolean() != null -> MiHealthImportUploadResultShape.BOOLEAN
        result !is JsonObject -> MiHealthImportUploadResultShape.INVALID
        nested == null -> MiHealthImportUploadResultShape.OBJECT_WITHOUT_RESULT
        nested == JsonNull -> MiHealthImportUploadResultShape.NESTED_NULL
        nested.strictBoolean() != null -> MiHealthImportUploadResultShape.NESTED_BOOLEAN
        else -> MiHealthImportUploadResultShape.NESTED_INVALID
    }
    // The native upload contract is BaseResult<DataBooleanResult>, whose
    // result.result is a Boolean. Keep the explicit flat Boolean variant too.
    // Code zero alone is not evidence of acceptance. Preserve its metadata as
    // an uncertain response so callers read back without replaying this POST.
    val acknowledged = result.strictBoolean() ?: nested.strictBoolean()
    if (code == 0 && acknowledged == null) throw MiHealthImportTransportException(
        MiHealthImportResponseSummary(businessCode = code, safeMessage = safeMessage,
            messageWithheld = withheld, transportFailure = MiHealthImportTransportFailure.INVALID_RESPONSE,
            uploadResultShape = shape),
    )
    return MiHealthImportUploadResult(
        accepted = code == 0 && acknowledged == true,
        code = code,
        safeMessage = safeMessage,
        messageWithheld = withheld,
        resultShape = shape,
    )
}

/** Server exceptions thrown by the encrypted client at this boundary are HTTP failures. */
private suspend fun requestWithKnownHttpStatus(request: suspend () -> JsonElement): JsonElement = try {
    request()
} catch (e: MiApiException.Server) {
    throw MiHealthImportTransportException(
        MiHealthImportResponseSummary(httpStatus = e.httpOrBusinessCode,
            transportFailure = MiHealthImportTransportFailure.HTTP), e,
    )
}

private fun JsonObject.serverMessage(): String? = sequenceOf(this, this["result"] as? JsonObject)
    .filterNotNull().flatMap { obj -> sequenceOf("message", "msg", "description").map { obj[it] as? JsonPrimitive } }
    .filterNotNull()
    .firstOrNull { it.isString && it.content.isNotBlank() }?.content

/** Pure encoder: local HC record IDs, package names and journal fields stay local. */
internal fun buildMiHealthImportUploadPayload(rows: List<MiHealthImportRow>, phoneId: String): JsonObject = buildJsonObject {
    require(phoneId.isNotBlank())
    put("phone_id", phoneId)
    put("data_list", JsonArray(rows.map { row -> buildJsonObject {
        put("sid", row.identity.sid)
        put("key", row.identity.key)
        put("time", row.identity.time)
        put("value", row.value.toString())
        put("zone_offset", row.zoneOffsetSeconds)
        // A numerical HC offset cannot identify an IANA timezone name.
        put("zone_name", "")
        if (row.identity.channel == MiHealthImportChannel.RAW) put("deleted", false)
        else put("tag", requireNotNull(row.identity.tag))
    } }))
}

internal data class MiHealthImportReadPage(
    val rows: List<MiHealthImportCloudRow>,
    val hasMore: Boolean,
    val nextKey: String?,
    val paginationReported: Boolean,
)

@Suppress("ThrowsCount") // Each malformed response shape gets its own failure.
internal fun parseMiHealthImportReadPage(
    response: JsonElement,
    channel: MiHealthImportChannel,
    requestedKey: String,
    strictPagination: Boolean = true,
): MiHealthImportReadPage {
    val obj = response as? JsonObject
    val code = (obj?.get("code") as? JsonPrimitive)?.intOrNull
    if (code != null && code != 0) {
        val (message, withheld) = safeMiHealthImportMessage(obj?.serverMessage())
        throw MiHealthImportTransportException(MiHealthImportResponseSummary(
            businessCode = code, safeMessage = message, messageWithheld = withheld, accepted = false,
        ))
    }
    val result = requireMiResult(response)
    val list = result["data_list"] as? JsonArray
        ?: throw MiApiException.Unexpected("Mi import readback has no data_list; absence cannot be established")
    val rows = list.map { element ->
        val row = element as? JsonObject ?: throw MiApiException.Unexpected("Mi import readback contains an invalid row")
        val time = (row["time"] as? JsonPrimitive)?.longOrNull
            ?: throw MiApiException.Unexpected("Mi import readback row has no time")
        val deleted = when (val flag = row["deleted"]) {
            null, JsonNull -> false
            is JsonPrimitive -> flag.booleanOrNull ?: throw MiApiException.Unexpected("Mi import deleted flag is invalid")
            else -> throw MiApiException.Unexpected("Mi import deleted flag is invalid")
        }
        val value = when (val data = row["value"]) {
            is JsonObject -> data
            is JsonPrimitive -> if (data.isString) {
                try { Json.parseToJsonElement(data.content) as? JsonObject }
                catch (_: Exception) { null }
            } else null
            else -> null
        } ?: if (deleted) JsonObject(emptyMap()) else throw MiApiException.Unexpected("Mi import readback value is not a JSON object")
        MiHealthImportCloudRow(
            sid = row.stringOrNull("sid"),
            key = row.stringOrNull("key") ?: requestedKey,
            time = time,
            // The aggregate request is already restricted to this tag. The
            // source SID is never inferred: it must be returned to verify.
            tag = row.stringOrNull("tag") ?: if (channel == MiHealthImportChannel.DAILY_AGGREGATE) "daily_report" else null,
            value = value,
            deleted = deleted,
            zoneOffsetSeconds = when (val offset = row["zone_offset"]) {
                null, JsonNull -> null
                is JsonPrimitive -> offset.intOrNull ?: throw MiApiException.Unexpected("Mi import readback zone_offset is invalid")
                else -> throw MiApiException.Unexpected("Mi import readback zone_offset is invalid")
            },
        )
    }
    val flag = result["has_more"]
    val hasMore = when (flag) {
        null, JsonNull -> false
        is JsonPrimitive -> flag.booleanOrNull ?: if (strictPagination)
            throw MiApiException.Unexpected("Mi import readback has_more is invalid") else false
        else -> if (strictPagination) throw MiApiException.Unexpected("Mi import readback has_more is invalid") else false
    }
    val cursor = result["next_key"]
    val cursorValid = cursor == null || cursor == JsonNull || (cursor is JsonPrimitive && cursor.isString)
    val nextKey = if (strictPagination) result.stringOrNull("next_key")
        else (cursor as? JsonPrimitive)?.takeIf { it.isString }?.content
    return MiHealthImportReadPage(rows, hasMore, nextKey,
        flag is JsonPrimitive && !flag.isString && flag.booleanOrNull != null && cursorValid)
}

private fun JsonObject.stringOrNull(field: String): String? = when (val value = this[field]) {
    null, JsonNull -> null
    is JsonPrimitive -> if (value.isString) value.content else throw MiApiException.Unexpected("Mi import '$field' is not a string")
    else -> throw MiApiException.Unexpected("Mi import '$field' is not a string")
}
