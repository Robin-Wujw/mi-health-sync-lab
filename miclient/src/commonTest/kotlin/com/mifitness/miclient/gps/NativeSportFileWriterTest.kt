package com.mifitness.miclient.gps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NativeSportFileWriterTest {
    @Test fun sparseHeartRateRetainsTimesAndNativeHeader() {
        val samples = listOf(NativeSportFileWriter.HeartPoint(1_800_000_010, 73),
            NativeSportFileWriter.HeartPoint(1_800_000_071, 148))
        for (proto in listOf(3, 8, 12)) {
            val bytes = NativeSportFileWriter.heartRate(1_800_000_000, 32, proto, samples)
            assertEquals(samples, NativeSportFileWriter.decodeHeartRate(bytes))
            assertEquals(1, bytes[5].toInt())
            assertEquals(proto, bytes[6].toInt())
        }
    }
    @Test fun gpsUsesIndependentLegacyDecoder() {
        val bytes = NativeSportFileWriter.gps(1_800_000_000, 32, 1,
            listOf(NativeSportFileWriter.RoutePoint(1_800_000_010, 1.25, 103.75)))
        val point = SportGpsBinary.parsePoints(bytes).single()
        assertEquals(1_800_000_010L, point.timeSec)
        assertEquals(1.25, point.latitude)
        assertEquals(103.75, point.longitude)
    }
    @Test fun rejectsDuplicateTimesAndInvalidCoordinates() {
        val point = NativeSportFileWriter.HeartPoint(1_800_000_010, 73)
        assertFailsWith<IllegalArgumentException> { NativeSportFileWriter.heartRate(1_800_000_000, 32, 3, listOf(point, point)) }
        assertFailsWith<IllegalArgumentException> { NativeSportFileWriter.gps(1_800_000_000, 32, 1,
            listOf(NativeSportFileWriter.RoutePoint(1_800_000_010, 91.0, 103.0))) }
    }

    @Test fun genericV1MatchesNativeTwoFieldLayoutIncludingInvalidCalorieBytes() {
        val samples = listOf(NativeSportFileWriter.HeartPoint(110, 73), NativeSportFileWriter.HeartPoint(171, 148))
        val bytes = NativeSportFileWriter.heartRate(100, 0, 8, samples)
        val fixture = "640000000001080080010000006e000000490001000000ab0000009400"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(fixture.toList(), bytes.toList())
        val parsed = parseNativeGenericV1(bytes)
        assertEquals(samples, parsed.first)
        assertFalse(parsed.second)
        val truncated = "640000000001080080010000006e0000004901000000ab00000094"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertFailsWith<IllegalArgumentException> { parseNativeGenericV1(truncated) }
    }

    @Test fun outdoorV1IncludesRealInitialAltitudeWithoutTheV2ItMarker() {
        val samples = listOf(NativeSportFileWriter.HeartPoint(110, 73))
        val bytes = NativeSportFileWriter.heartRate(100, 0, 1, samples, initialAltitude = 42.0)
        val fixture = "64000000000101000c0000002842010000006e00000049"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(fixture.toList(), bytes.toList())
        assertEquals(samples, NativeSportFileWriter.decodeHeartRate(bytes))
        val unknown = NativeSportFileWriter.heartRate(100, 0, 1, samples)
        assertEquals(listOf(0, 0, 192, 127), unknown.slice(10..13).map { it.toInt() and 255 })
        assertEquals(samples, NativeSportFileWriter.decodeHeartRate(unknown))
        assertEquals(12, unknown[8].toInt()) // Only HR is valid; no altitude measurement is advertised.
    }

    @Test fun timelinePreservesOnlyObservedValuesAndUsesInvalidZeroForMissingSeconds() {
        val samples = listOf(NativeSportFileWriter.HeartPoint(102, 73), NativeSportFileWriter.HeartPoint(105, 148))
        for (proto in listOf(1, 2, 3, 8, 15)) {
            val bytes = NativeSportFileWriter.heartRate(100, 0, proto, samples, 42.0, sessionEndTime = 107)
            assertEquals(samples, NativeSportFileWriter.decodeHeartRate(bytes))
            val offset = when (proto) { 8 -> 17; 3 -> 18; else -> 22 }
            val width = if (proto == 8) 2 else 1
            assertEquals(listOf(0, 0, 73, 0, 0, 148, 0), (0..6).map { bytes[offset + it * width].toInt() and 0xff })
            assertFailsWith<IllegalArgumentException> {
                NativeSportFileWriter.heartRate(100, 0, proto, samples, 42.0, sessionEndTime = 105)
            }
        }
    }

    /** Independent subset of the archived FreeTraining/OneDimen/SportRecord native read rules.
     * Both one-byte fields consume bytes regardless of the bitmap, then only valid fields are exposed.
     */
    private fun parseNativeGenericV1(bytes: ByteArray): Pair<List<NativeSportFileWriter.HeartPoint>, Boolean> {
        var offset = 8
        val validity = bytes[offset++].toInt() and 0xff
        val points = mutableListOf<NativeSportFileWriter.HeartPoint>()
        fun uint(): Long {
            require(offset + 4 <= bytes.size)
            var value = 0L
            repeat(4) { value = value or ((bytes[offset++].toLong() and 0xff) shl (it * 8)) }
            return value
        }
        while (offset < bytes.size) {
            val count = uint()
            val start = uint()
            require(count in 1..100_000 && bytes.size - offset >= count * 2)
            repeat(count.toInt()) { second ->
                val hr = bytes[offset++].toInt() and 0xff
                offset++ // Native type 2 consumes its byte even when its validity bit is unset.
                if (validity and 0x80 != 0) points += NativeSportFileWriter.HeartPoint(start + second, hr)
            }
        }
        return points to (validity and 0x40 != 0)
    }
}
