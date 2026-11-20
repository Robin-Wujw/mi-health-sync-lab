package com.bettermifitness.sync.zepp

import com.mifitness.miclient.createPlatformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.appendPathSegments
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val EVENT_REQUEST_LIMIT = 48
private const val STRESS_SPLIT_PROBE_LIMIT = 8
private const val MIN_STRESS_LOGICAL_SPLIT_SECONDS = 3 * 3600L

/** Dedicated client without logging, redirects, automatic replay, or arbitrary authenticated URLs. */
class ZeppCloudClient(
    private val http: HttpClient = createPlatformHttpClient(allowAutomaticReplay = false).config {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
    },
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    /** Counts only, replaced on each bounded sleep source read. */
    var sleepBreathDiagnostic: Map<String, Int> = emptyMap()
        private set

    fun close() = http.close()
    suspend fun login(account: String, password: String, country: String = "CN"): ZeppSession {
        require(account.length in 3..254 && account.none { it.isWhitespace() || it.isISOControl() })
        require(account.contains('@') || account.matches(Regex("\\+?[0-9]{6,20}")))
        require(password.length in 1..1024 && country.matches(Regex("[A-Z]{2}")))
        val first = request {
            method = HttpMethod.Post
            url("https://api-user.huami.com")
            url { appendPathSegments("registrations", account, "tokens") }
            header("app_name", "com.huami.webapp")
            header("Accept-Language", "zh")
            setBody(FormDataContent(Parameters.build {
                append("country_code", country); append("name", account); append("password", password)
                append("client_id", "HuaMi")
                append("redirect_uri", "https://s3-us-west-2.amazonaws.com/hm-registration/successsignin.html")
                append("state", "REDIRECTION"); append("token", "access"); append("json_response", "true")
            }))
        }.jsonObject
        val access = first.text("access") ?: throw ZeppException(ZeppFailure.AUTH)
        val second = request {
            method = HttpMethod.Post
            url("https://account.huami.com/v2/client/login")
            setBody(FormDataContent(Parameters.build {
                append("country_code", first.text("country_code")?.substringBefore('-') ?: country)
                append("app_name", "com.huami.webapp")
                append("third_name", if ('@' in account) "huami" else "huami_phone")
                append("grant_type", "access_token"); append("code", access)
                append("device_id", "02:00:00:00:00:00"); append("device_model", "web")
                append("app_version", "4.3.0"); append("allow_registration", "false")
                append("dn", "account.huami.com,api-user.huami.com,auth.huami.com,api-mifit.huami.com,api-open.huami.com")
            }))
        }.jsonObject
        if (second.text("result") != "ok") throw ZeppException(ZeppFailure.AUTH)
        val info = second["token_info"] as? JsonObject ?: throw ZeppException(ZeppFailure.RESPONSE)
        if (info.text("login_token").isNullOrBlank()) throw ZeppException(ZeppFailure.RESPONSE)
        val host = (second["domains"] as? JsonArray).orEmpty().flatMap {
            ((it as? JsonObject)?.get("cnames") as? JsonArray).orEmpty()
        }.firstNotNullOfOrNull { raw ->
            (raw as? JsonPrimitive)?.contentOrNull?.let { runCatching { zeppHost(it) }.getOrNull() }
        } ?: throw ZeppException(ZeppFailure.RESPONSE)
        return try {
            ZeppSession(info.text("user_id") ?: "", info.text("app_token") ?: "",
                info.text("login_token") ?: "", host)
        } catch (_: IllegalArgumentException) { throw ZeppException(ZeppFailure.RESPONSE) }
    }

    suspend fun fetch(session: ZeppSession, from: Long, until: Long,
        metrics: Set<ZeppSyncMetric> = ZeppSyncMetric.entries.toSet(),
        deferEventSplit: Boolean = false, requiredEvents: Set<ZeppStream> = emptySet()): ZeppSourceSnapshot {
        require(from > 0 && until > from && until - from <= 31 * 86_400)
        val observations = mutableListOf<ZeppObservation>()
        val scores = mutableListOf<ZeppWorkoutScores>()
        val unavailable = mutableSetOf<String>()
        val incomplete = mutableSetOf<String>()
        val presentEvents = mutableSetOf<ZeppStream>()
        var invalid = 0
        for (stream in metrics.flatMap { it.streams }.distinct()) {
            try {
                val events = readEventStream(session, stream, from, until, deferEventSplit)
                presentEvents += stream
                if (stream != ZeppStream.STRESS || events.complete && events.invalidItems == 0) {
                    observations += events.observations
                }
                invalid += events.invalidItems
                if (!events.complete) incomplete += stream.name
            } catch (split: ZeppEventWindowSplitRequired) {
                throw ZeppEventWindowSplitRequired(presentEvents + split.requiredEvents)
            } catch (e: ZeppException) {
                if (e.failure != ZeppFailure.UNAVAILABLE) throw e
                if (stream in requiredEvents) incomplete += stream.name else unavailable += stream.name
            }
        }
        if (ZeppSyncMetric.TRAINING_LOAD in metrics) try {
            val payload = get(session, "/v2/watch/users/${session.userId}/WatchSportStatistics/SPORT_LOAD", mapOf(
                "startDay" to utcDay(from), "endDay" to utcDay(until), "limit" to "100", "isReverse" to "true"))
            val p = page(payload, 100)
            if (p.full) incomplete += "SPORT_LOAD"
            val decoded = ZeppTrainingLoadDecoder.decode(p.items, now())
            observations += decoded.observations.filter { it.observedSeconds in (from / 86_400 * 86_400) until until }
            invalid += decoded.invalidItems
        } catch (e: ZeppException) {
            if (e.failure != ZeppFailure.UNAVAILABLE) throw e
            unavailable += "SPORT_LOAD"
        }
        // The run feed is the account-wide workout index. Use its explicit cursor, never a guessed per-sport feed.
        var cursor = until
        var finished = ZeppSyncMetric.WORKOUT_SCORES !in metrics
        try {
            repeat(30) {
                if (finished) return@repeat
                val payload = get(session, "/v1/sport/run/history.json", mapOf("userid" to session.userId,
                    "startTrackId" to from.toString(), "stopTrackId" to cursor.toString(), "need_sub_data" to "1", "type" to ""))
                val p = page(payload, 1000)
                val decoded = ZeppDecoder.workouts(p.items, now())
                scores += decoded.workouts.filter { it.startSeconds in from until until }
                invalid += decoded.invalidItems
                val next = (payload as? JsonObject)?.get("data") as? JsonObject
                val nextRaw = next?.get("next") ?: (payload as? JsonObject)?.get("next")
                val nextId = (nextRaw as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                if (nextRaw == JsonNull || nextId != null && nextId <= 0L || nextRaw == null && p.items.isEmpty()) finished = true
                else if (nextId == null || nextId >= cursor || nextId < from) {
                    if (nextId != null && nextId < from) finished = true
                    else { incomplete += "WORKOUTS"; finished = true }
                } else cursor = nextId
            }
            if (!finished) incomplete += "WORKOUTS"
        } catch (e: ZeppException) {
            if (e.failure != ZeppFailure.UNAVAILABLE) throw e
            unavailable += "WORKOUTS"
        }
        return ZeppSourceSnapshot(observations.distinct(), scores.distinct(), unavailable, incomplete, invalid)
    }

    private suspend fun readEventStream(session: ZeppSession, stream: ZeppStream, from: Long, until: Long,
        deferEventSplit: Boolean): EventStreamRead {
        val pad = if (stream == ZeppStream.STRESS) 86_400L else 0L
        val probeStressSplit = pad > 0 && deferEventSplit && until - from > MIN_STRESS_LOGICAL_SPLIT_SECONDS
        val requestLimit = if (probeStressSplit) STRESS_SPLIT_PROBE_LIMIT else EVENT_REQUEST_LIMIT
        val windows = ArrayDeque<EventWindow>()
        var start = (from - pad).coerceAtLeast(1L)
        while (start < until + pad) {
            val end = minOf(start + 7 * 86_400, until + pad)
            windows.addLast(EventWindow(start, end))
            start = end
        }
        val observations = mutableListOf<ZeppObservation>()
        var invalid = 0
        var complete = true
        var splitBudgetExhausted = false
        var successfulPages = 0
        var unavailablePages = 0
        val fetchedAt = now()
        var remaining = requestLimit
        while (windows.isNotEmpty() && remaining > 0) {
            val window = windows.removeFirst()
            remaining--
            val result = try {
                val payload = getEventPage(session, stream, window)
                val parsed = page(payload, 1000)
                successfulPages++
                parsed.copy(full = parsed.full || eventPageSignalsMore(payload))
            } catch (error: ZeppException) {
                when (error.failure) {
                    ZeppFailure.UNAVAILABLE -> { unavailablePages++; complete = false; continue }
                    ZeppFailure.INCOMPLETE -> ZeppPage(emptyList(), full = true)
                    else -> throw error
                }
            }
            val width = window.until - window.from
            if (result.full && pad == 0L && deferEventSplit && width > 60) {
                throw ZeppEventWindowSplitRequired(setOf(stream))
            }
            if (result.full && width > 60 && remaining >= windows.size + 2) {
                val mid = window.from + width / 2
                windows.addLast(EventWindow(window.from, mid))
                windows.addLast(EventWindow(mid, window.until))
            } else {
                if (result.full) {
                    complete = false
                    splitBudgetExhausted = splitBudgetExhausted || width > 60
                } else {
                    val decoded = ZeppDecoder.decode(stream, result.items, fetchedAt)
                    observations += decoded.observations.filter {
                        it.observedSeconds in from until until &&
                            (stream == ZeppStream.STRESS || it.observedSeconds in window.from until window.until)
                    }
                    invalid += decoded.invalidItems
                }
            }
        }
        if (successfulPages == 0 && unavailablePages > 0 && unavailablePages == requestLimit - remaining && windows.isEmpty()) {
            throw ZeppException(ZeppFailure.UNAVAILABLE)
        }
        if (probeStressSplit && (splitBudgetExhausted || windows.isNotEmpty())) {
            throw ZeppEventWindowSplitRequired(setOf(stream))
        }
        return EventStreamRead(observations, invalid, complete && windows.isEmpty())
    }

    private suspend fun getEventPage(session: ZeppSession, stream: ZeppStream, window: EventWindow): JsonElement =
        get(session, if (stream.userSurface) "/users/${session.userId}/events" else "/v2/users/me/events", buildMap {
            put("eventType", stream.eventType); stream.subType?.let { put("subType", it) }
            put("from", (window.from * 1_000).toString()); put("to", (window.until * 1_000).toString())
            put("limit", "1000"); put("reverse", "1")
            if (stream.userSurface) put("userId", session.userId)
        })

    private fun eventPageSignalsMore(payload: JsonElement): Boolean {
        val outer = payload as? JsonObject ?: return false
        return listOfNotNull(outer, outer["data"] as? JsonObject).any { envelope ->
            listOf("has_more", "hasMore").any { key ->
                val value = envelope[key]
                value != null && value != JsonNull && (value as? JsonPrimitive)?.contentOrNull !in setOf("false", "0")
            } || envelope["next"].let { next ->
                next != null && next != JsonNull && (next as? JsonPrimitive)?.contentOrNull !in setOf("", "0", "-1", "false")
            }
        }
    }

    private data class EventWindow(val from: Long, val until: Long)
    private data class EventStreamRead(val observations: List<ZeppObservation>, val invalidItems: Int, val complete: Boolean)

    /** Token rotation is a separate read-only auth exchange; it never replays a Xiaomi write. */
    suspend fun workoutHeartRate(session: ZeppSession, start: Long, end: Long,
        sourceIndex: ZeppSourceSnapshot? = null): List<com.bettermifitness.sync.data.api.WorkoutTimedSample> {
        require(end > start && end - start <= 86_400)
        val history = sourceIndex ?: fetch(session, start - 1, end + 1, setOf(ZeppSyncMetric.WORKOUT_SCORES))
        if (history.incomplete.isNotEmpty() || history.invalidItems > 0) throw ZeppException(ZeppFailure.INCOMPLETE)
        val workout = history.workouts.filter { it.startSeconds == start && it.endSeconds == end }.singleOrNull()
            ?: throw ZeppException(ZeppFailure.RESPONSE)
        val source = workout.source ?: throw ZeppException(ZeppFailure.RESPONSE)
        val response = get(session, "/v1/sport/run/detail.json", mapOf("trackid" to workout.trackId, "source" to source)) as? JsonObject
            ?: throw ZeppException(ZeppFailure.RESPONSE)
        val data = response["data"] as? JsonObject ?: throw ZeppException(ZeppFailure.RESPONSE)
        if ((data["trackid"] as? JsonPrimitive)?.contentOrNull != workout.trackId) throw ZeppException(ZeppFailure.RESPONSE)
        val detailSource = (data["source"] as? JsonPrimitive)?.contentOrNull
        if (detailSource != null && detailSource != source) throw ZeppException(ZeppFailure.RESPONSE)
        val encoded = (data["heart_rate"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ZeppException(ZeppFailure.RESPONSE)
        return ZeppWorkoutHeartRateDecoder.decode(encoded, start, end)
    }

    suspend fun sleepBreath(session: ZeppSession, sleeps: List<com.bettermifitness.sync.health.HealthInboundSleepRecord>):
        List<com.bettermifitness.sync.data.api.SleepVitalPoint> {
        val recent = sleeps.filter { it.startEpochMillis / 1_000 >= now() - 31 * 86_400L }
        if (recent.isEmpty()) return emptyList()
        var start = recent.minOf { it.startEpochMillis / 1_000 }
        val until = recent.maxOf { it.endEpochMillis / 1_000 }
        val bands = mutableListOf<JsonObject>()
        val events = mutableListOf<JsonObject>()
        while (start < until) {
            val end = minOf(start + 7 * 86_400, until)
            val bandPage = page(get(session, "/v1/data/band_data.json", mapOf("userid" to session.userId,
                "from_date" to utcDay(start - 86_400), "to_date" to utcDay(end + 86_400),
                "query_type" to "detail", "byteLength" to "8", "device_type" to "0")), 1_000)
            val eventPage = page(get(session, "/v2/users/me/events", mapOf("eventType" to "RespiratoryRate",
                "subType" to "real_data", "from" to ((start - 2 * 86_400) * 1_000).toString(),
                "to" to ((end + 86_400) * 1_000).toString(), "limit" to "1000", "reverse" to "1")), 1_000)
            if (bandPage.full || eventPage.full) throw ZeppException(ZeppFailure.INCOMPLETE)
            bands += bandPage.items
            events += eventPage.items
            start = end
        }
        return ZeppSleepBreathDecoder.decode(bands.distinct(), events.distinct(), recent) { counts ->
            sleepBreathDiagnostic = counts
        }
    }

    /** Token rotation is a separate read-only auth exchange; it never replays a Xiaomi write. */
    suspend fun refresh(session: ZeppSession): ZeppSession {
        if (session.loginToken.isBlank()) throw ZeppException(ZeppFailure.AUTH)
        val payload = request {
            url("https://account.huami.com/v1/client/app_tokens")
            url {
                parameters.append("app_name", "com.huami.webapp")
                parameters.append("dn", "account.huami.com,api-user.huami.com,auth.huami.com,api-mifit.huami.com,api-open.huami.com")
                parameters.append("login_token", session.loginToken)
            }
        } as? JsonObject ?: throw ZeppException(ZeppFailure.RESPONSE)
        val info = payload["token_info"] as? JsonObject ?: throw ZeppException(ZeppFailure.AUTH)
        val user = info.text("user_id") ?: session.userId
        if (user != session.userId) throw ZeppException(ZeppFailure.AUTH)
        return ZeppSession(user, info.text("app_token") ?: throw ZeppException(ZeppFailure.AUTH),
            info.text("login_token") ?: session.loginToken, session.host)
    }

    private suspend fun get(session: ZeppSession, path: String, params: Map<String, String>): JsonElement = request {
        url("https://${zeppHost(session.host)}$path")
        url { params.forEach { (key, value) -> parameters.append(key, value) } }
        header("apptoken", session.appToken)
        header("appname", "com.huami.midong"); header("appplatform", "ios_phone")
        header("v", "2.0"); header("vn", "10.2.5"); header("cv", "1722_10.2.5")
        header("vb", "202604132257"); header("lang", "en"); header("country", ""); header("timezone", "UTC")
    }

    private suspend fun request(build: HttpRequestBuilder.() -> Unit): JsonElement = try {
        val response = http.request(build)
        when (response.status.value) {
            401, 403 -> throw ZeppException(ZeppFailure.AUTH)
            404 -> throw ZeppException(ZeppFailure.UNAVAILABLE)
            429 -> throw ZeppException(ZeppFailure.RATE_LIMIT)
        }
        if (response.status.value != 200) throw ZeppException(ZeppFailure.RESPONSE)
        val bytes = response.bodyAsChannel().readBuffer(4_194_305L).readByteArray()
        if (bytes.size > 4_194_304) throw ZeppException(ZeppFailure.INCOMPLETE)
        val payload = try { Json.parseToJsonElement(bytes.decodeToString()) }
        catch (_: Exception) { throw ZeppException(ZeppFailure.RESPONSE) }
        validateBusinessStatus(payload)
        payload
    } catch (e: CancellationException) { throw e }
    catch (e: ZeppException) { throw e }
    catch (_: Exception) { throw ZeppException(ZeppFailure.NETWORK) }

    internal fun page(payload: JsonElement, limit: Int): ZeppPage {
        fun array(obj: JsonObject): JsonArray? = listOf("items", "records", "results", "list", "summary")
            .firstNotNullOfOrNull { obj[it] as? JsonArray }
        val list = when (payload) {
            is JsonArray -> payload
            is JsonObject -> array(payload) ?: payload["data"].let {
                if (it is JsonArray) it else (it as? JsonObject)?.let(::array)
            }
            else -> null
        } ?: throw ZeppException(ZeppFailure.RESPONSE)
        if (list.any { it !is JsonObject }) throw ZeppException(ZeppFailure.RESPONSE)
        return ZeppPage(list.map { it as JsonObject }, list.size >= limit)
    }

    private fun validateBusinessStatus(payload: JsonElement) {
        val obj = payload as? JsonObject ?: return
        if (listOf("captcha", "captcha_url", "captchaUrl").any { obj[it] != null && obj[it] != JsonNull }) {
            throw ZeppException(ZeppFailure.CAPTCHA)
        }
        val code = obj["code"]
        val successCodes = setOf("0", "1", "200", "0000", "ok", "success")
        val badCode = code != null && (code !is JsonPrimitive || code.content !in successCodes)
        val emptyError = setOf(JsonNull, JsonPrimitive(0), JsonPrimitive("0"), JsonPrimitive(false), JsonPrimitive(""),
            JsonObject(emptyMap()))
        val hasError = listOf("error", "errorCode", "error_code", "errCode", "err_code")
            .any { obj[it] != null && obj[it] !in emptyError }
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
        val numericFailure = status?.toIntOrNull()?.let { it >= 400 } == true
        if (badCode || obj["success"] == JsonPrimitive(false) || hasError ||
            status?.lowercase() in setOf("error", "failed", "failure") || numericFailure) {
            throw ZeppException(ZeppFailure.RESPONSE)
        }
    }

    private fun utcDay(seconds: Long) = Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.UTC).date.toString()
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() && it.length <= 16_384 }
}
