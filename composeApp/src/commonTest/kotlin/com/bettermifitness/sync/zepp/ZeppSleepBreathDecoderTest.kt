package com.bettermifitness.sync.zepp

import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthRecordOrigin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZeppSleepBreathDecoderTest {
    private val start = 1_800_000_000L
    private val sleep = HealthInboundSleepRecord(HealthRecordOrigin("night", "com.huami.watch.hmwatchmanager", null, 1, 1),
        start * 1_000, (start + 600) * 1_000, 0, 0, emptyList())
    private fun band(from: Long = start) = buildJsonObject {
        put("summary", Base64.Default.encode("""{"slp":{"st":$from,"ed":${start + 600}}}""".encodeToByteArray()))
    }
    private fun event(bytes: ByteArray, revision: Long = start) = buildJsonObject {
        put("timestamp", start * 1_000)
        put("uploadTimestamp", revision * 1_000)
        put("value", buildJsonObject { put("measurements", Base64.Default.encode(bytes)); put("deviceId", "synthetic-device") })
    }

    @Test fun invalidMeasurementsKeepTheirMinuteSlots() {
        val points = ZeppSleepBreathDecoder.decode(listOf(band()), listOf(event(byteArrayOf(16, 0, 18))), listOf(sleep))
        assertEquals(listOf(start, start + 120), points.map { it.time })
        assertEquals(listOf(16, 18), points.map { it.value })
        assertEquals(setOf("synthetic-device"), points.map { it.deviceKey }.toSet())
    }
    @Test fun cloudAccountMustProvideTheExactSourceSleepInterval() {
        assertTrue(ZeppSleepBreathDecoder.decode(listOf(band(start - 60)),
            listOf(event(byteArrayOf(16))), listOf(sleep)).isEmpty())
    }
    @Test fun newInvalidRevisionDoesNotResurrectTheOldReading() {
        val invalid = event(byteArrayOf(18), start + 60).let { item ->
            JsonObject(item + ("value" to buildJsonObject {
                put("measurements", "invalid-base64!"); put("deviceId", "synthetic-device")
            }))
        }
        assertTrue(ZeppSleepBreathDecoder.decode(listOf(band()), listOf(event(byteArrayOf(16)), invalid), listOf(sleep)).isEmpty())
    }
    @Test fun longPayloadIsRejectedRatherThanSilentlyTruncated() {
        assertTrue(ZeppSleepBreathDecoder.decode(listOf(band()), listOf(event(ByteArray(1_441) { 16 })), listOf(sleep)).isEmpty())
    }
}
