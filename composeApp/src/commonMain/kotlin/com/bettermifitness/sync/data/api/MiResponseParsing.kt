package com.bettermifitness.sync.data.api

import com.mifitness.miclient.api.MiApiException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Validate business success before interpreting an empty list as "no data". */
internal fun requireMiResult(response: JsonElement): JsonObject {
    val obj = response as? JsonObject
        ?: throw MiApiException.Unexpected("Mi cloud returned a non-object response")
    val code = (obj["code"] as? JsonPrimitive)?.intOrNull
        ?: throw MiApiException.Unexpected("Mi cloud response has no valid business code")
    if (code != 0) {
        throw MiApiException.Server(code, "Mi cloud request failed (code $code)")
    }
    return obj["result"] as? JsonObject
        ?: throw MiApiException.Unexpected("Mi cloud success response has no result object")
}

private fun JsonObject.optionalString(name: String): String? = when (val value = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeIf { it.isString }?.content
        ?: throw MiApiException.Unexpected("Mi cloud field '$name' is not a string")
    else -> throw MiApiException.Unexpected("Mi cloud field '$name' is not a string")
}

private fun JsonObject.optionalRows(name: String): List<JsonObject> = when (val list = this[name]) {
    null, JsonNull -> emptyList()
    is JsonArray -> list.map { it as? JsonObject
        ?: throw MiApiException.Unexpected("Mi cloud '$name' contains a non-object row") }
    else -> throw MiApiException.Unexpected("Mi cloud '$name' is not a list")
}

/** Both encoded JSON strings and inline JSON objects occur in cloud rows. */
private fun JsonObject.rowValue(): String = when (val value = this["value"]) {
    is JsonObject -> value.toString()
    is JsonPrimitive -> value.takeIf { it.isString }?.content
        ?: throw MiApiException.Unexpected("Mi cloud row has no JSON value")
    else -> throw MiApiException.Unexpected("Mi cloud row has no JSON value")
}

private fun JsonObject.rowTime(): Long =
    (this["time"] as? JsonPrimitive)?.longOrNull
        ?: throw MiApiException.Unexpected("Mi cloud row has no valid time")

private fun JsonObject.hasMore(): Boolean = when (val value = this["has_more"]) {
    null, JsonNull -> false
    is JsonPrimitive -> value.booleanOrNull
        ?: throw MiApiException.Unexpected("Mi cloud has_more is not a boolean")
    else -> throw MiApiException.Unexpected("Mi cloud has_more is not a boolean")
}

internal fun parseMiDataListResponse(response: JsonElement): FitnessResponse<HeartRateEntry> {
    val result = requireMiResult(response)
    return FitnessResponse(
        code = 0,
        result = FitnessResult(
            dataList = result.optionalRows("data_list").map { row ->
                HeartRateEntry(row.optionalString("key"), row.rowTime(), row.rowValue(), row.optionalString("sid"))
            },
            hasMore = result.hasMore(),
            nextKey = result.optionalString("next_key"),
            watermark = result.optionalString("watermark"),
        ),
    )
}

internal fun parseMiLatestResponse(response: JsonElement): FitnessResponse<SleepEntry> {
    val result = requireMiResult(response)
    return FitnessResponse(
        code = 0,
        result = FitnessResult(
            dataList = result.optionalRows("data_list").map { row ->
                SleepEntry(row.optionalString("key"), row.rowTime(), row.rowValue(), row.optionalString("sid"))
            },
            hasMore = result.hasMore(),
            nextKey = result.optionalString("next_key"),
            watermark = result.optionalString("watermark"),
        ),
    )
}

internal fun parseMiSportRecordsPage(response: JsonElement): FitnessResult<SportRecordEntry> {
    val result = requireMiResult(response)
    return FitnessResult(
        dataList = result.optionalRows("sport_records").map { row ->
            SportRecordEntry(
                key = row.optionalString("key"),
                time = row.rowTime(),
                value = row.rowValue(),
                category = row.optionalString("category"),
            )
        },
        hasMore = result.hasMore(),
        nextKey = result.optionalString("next_key"),
    )
}

/** A broken cursor or safety limit is an incomplete export, never successful EOF. */
internal class MiPageCursorGuard(private val maxPages: Int) {
    private var pages = 0
    private val seenNextKeys = mutableSetOf<String>()

    init {
        require(maxPages > 0) { "maxPages must be positive" }
    }

    fun nextCursor(hasMore: Boolean, nextKey: String?): String? {
        pages++
        if (!hasMore) return null
        if (nextKey.isNullOrBlank()) {
            throw MiApiException.Unexpected("Mi cloud reports more data without a pagination cursor")
        }
        if (!seenNextKeys.add(nextKey)) {
            throw MiApiException.Unexpected("Mi cloud repeated a pagination cursor; export is incomplete")
        }
        if (pages >= maxPages) {
            throw MiApiException.Unexpected("Mi cloud pagination reached the $maxPages page limit; export is incomplete")
        }
        return nextKey
    }
}
