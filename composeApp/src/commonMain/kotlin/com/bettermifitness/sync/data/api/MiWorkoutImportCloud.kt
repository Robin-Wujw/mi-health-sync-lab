package com.bettermifitness.sync.data.api

import com.mifitness.miclient.api.MiApiException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface MiWorkoutImportCloud {
    suspend fun read(from: Long, until: Long): List<MiWorkoutExperimentCloudRow>
    suspend fun upload(target: MiWorkoutImportTarget, value: JsonObject = target.value)
    suspend fun retire(target: MiWorkoutImportTarget, value: JsonObject) { error("Workout retirement is unavailable") }
}

class MiDirectWorkoutImportCloud(
    private val readWithAuthRetry: suspend (suspend () -> List<MiWorkoutExperimentCloudRow>) ->
        List<MiWorkoutExperimentCloudRow> = { it() },
    private val api: () -> MiDirectApi,
) : MiWorkoutImportCloud {
    override suspend fun read(from: Long, until: Long): List<MiWorkoutExperimentCloudRow> = readWithAuthRetry { readPages(from, until) }

    private suspend fun readPages(from: Long, until: Long): List<MiWorkoutExperimentCloudRow> {
        require(from >= 0 && until > from)
        val rows = mutableListOf<MiWorkoutExperimentCloudRow>()
        val cursor = MiPageCursorGuard(40)
        var next: String? = null
        do {
            val response = api().dataClient().post("/app/v1/data/get_sport_records_by_time",
                payload = buildMiWorkoutReadPayload(from, until, next))
            val result = requireMiResult(response)
            val data = result["sport_records"] as? JsonArray ?: error("Missing workout rows")
            rows += data.map { parseMiWorkoutExperimentRow(it as? JsonObject ?: error("Invalid workout row")) }
            val more = (result["has_more"] as? JsonPrimitive)?.booleanOrNull ?: error("Missing workout pagination")
            next = cursor.nextCursor(more, (result["next_key"] as? JsonPrimitive)?.content)
        } while (next != null)
        return rows.filter { !it.deleted && it.time >= from && it.time < until }
    }

    override suspend fun upload(target: MiWorkoutImportTarget, value: JsonObject) {
        val client = api().dataClient()
        writePayload("/app/v1/data/up_sport_records", MiWorkoutImportMapper.payload(target, client.phoneId, value))
    }

    override suspend fun retire(target: MiWorkoutImportTarget, value: JsonObject) {
        require(target.sid == "default")
        val fresh = read(target.start - 86_400, target.end + 86_400).singleOrNull { target.sameIdentity(it) }
        check(fresh != null && target.matches(fresh) && fresh.value == value)
        val payload = buildJsonObject {
            put("phone_id", api().dataClient().phoneId)
            put("keys", JsonArray(listOf(buildJsonObject {
                put("sid", target.sid); put("key", target.key); put("time", target.start)
            })))
        }
        writePayload("/app/v1/data/delete_sport_records", payload)
    }

    private suspend fun writePayload(path: String, payload: JsonObject) {
        val client = api().dataClient()
        val response = client.encryptedPost(path = path, payload = payload.toString()) as? JsonObject
            ?: error("Invalid workout upload response")
        val code = (response["code"] as? JsonPrimitive)?.intOrNull ?: error("Missing workout upload status")
        if (code != 0) throw MiApiException.Server(code, "Workout upload rejected")
        val result = response["result"]
        val accepted = ((result as? JsonObject)?.get("result") ?: result) as? JsonPrimitive
        check(accepted?.takeUnless { it.isString }?.booleanOrNull == true) { "Workout upload unconfirmed" }
    }
}

/** The native sport endpoint excludes start_time too. Keep the caller's inclusive
 * lower bound and filter the expanded response back to [from, until). */
internal fun buildMiWorkoutReadPayload(from: Long, until: Long, next: String?): Map<String, Any> = buildMap {
    put("start_time", (from - 1).coerceAtLeast(0)); put("end_time", until)
    put("limit", 50); put("reverse", true)
    next?.let { put("next_key", it) }
}
