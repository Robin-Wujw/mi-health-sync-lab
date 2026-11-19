package com.bettermifitness.sync.data.api

import kotlinx.serialization.json.JsonElement

/**
 * Native RAW reads exclude start_time. Use the same expanded boundary as import
 * verification, including every continuation page, then retain [from, until).
 * Some cloud pages omit additional early samples with the unexpanded boundary.
 * Filtering must preserve has_more/next_key even if a page has no retained rows.
 */
internal suspend fun readMiFitnessTimePage(
    key: String,
    from: Long,
    until: Long,
    nextKey: String?,
    request: suspend (Map<String, Any?>) -> JsonElement,
): FitnessResponse<HeartRateEntry> {
    require(from >= 0 && until > from)
    val payload = buildMiHealthImportReadPayload(MiHealthImportChannel.RAW, key, from, until, nextKey)
    val response = parseMiDataListResponse(request(payload))
    val page = checkNotNull(response.result)
    return response.copy(result = page.copy(dataList = page.dataList.filter { it.time >= from && it.time < until }))
}
