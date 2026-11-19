package com.mifitness.miclient.gps

/** Strict version-1 files from the native IndoorRun/OutdoorSport/FreeTraining parsers. */
object NativeSportFileWriter {
    data class HeartPoint(val time: Long, val bpm: Int)
    data class RoutePoint(val time: Long, val latitude: Double, val longitude: Double)

    fun heartRate(time: Long, timezone: Int, proto: Int, samples: List<HeartPoint>, initialAltitude: Double? = null,
        sessionEndTime: Long? = null): ByteArray {
        require(proto in setOf(1, 2, 3, 8, 12, 15, 16))
        val outdoor = proto in setOf(1, 2, 15)
        if (outdoor) require(initialAltitude == null || initialAltitude.isFinite() && initialAltitude.toFloat().isFinite())
        // The v1 outdoor layout always contains this structural float. With the
        // altitude validity bit unset, NaN represents unknown, not a measured 0 m.
        // The actual Mi Fitness parser accepts it without losing HR/timestamps.
        val altitudeBits = (initialAltitude?.toFloat() ?: Float.NaN).toBits().toLong()
        require(samples.isNotEmpty() && samples.size <= 100_000)
        require(samples.zipWithNext().all { (a, b) -> a.time < b.time })
        require(samples.all { it.time in time..(time + 86_400) && it.bpm in 1..255 })
        val free = proto in setOf(8, 12, 16)
        val out = Bytes()
        header(out, time, timezone, proto)
        // Four-dimension nibble: exist + high for the HR field at index 1.
        if (free) out.u8(0x80) else { out.u8(0x0c); out.u8(0) }
        if (sessionEndTime != null) {
            require(sessionEndTime in (time + 1)..(time + 86_400) && samples.last().time < sessionEndTime)
            if (outdoor) out.u32(altitudeBits)
            out.u32(sessionEndTime - time); out.u32(time)
            val byTime = samples.associate { it.time to it.bpm }
            for (second in time until sessionEndTime) {
                // Mi's chart treats Hr=0 as invalid, retaining its elapsed-second slot.
                // Missing observations remain absent; no measured value is repeated/interpolated.
                out.u8(byTime[second] ?: 0)
                if (free) out.u8(0)
            }
            return out.result()
        }
        // A separate count=1 segment preserves sparse source timestamps; gaps are never filled.
        for (sample in samples) {
            if (outdoor) out.u32(altitudeBits)
            out.u32(1); out.u32(sample.time)
            // OutdoorSport (types 1/2/15) introduces its IT marker in v2, not v1.
            out.u8(sample.bpm)
            // FreeTraining v1 reads both bytes even when the calorie validity bit is unset.
            // This byte is structurally required and remains invalid, not an exported calorie value.
            if (free) out.u8(0)
        }
        return out.result()
    }

    fun gps(time: Long, timezone: Int, proto: Int, points: List<RoutePoint>): ByteArray {
        require(proto in 1..31 && points.size in 1..200_000)
        require(points.zipWithNext().all { (a, b) -> a.time < b.time })
        require(points.all { it.time in time..(time + 86_400) && it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
        val out = Bytes()
        header(out, time, timezone, proto)
        out.u8(0xe0) // Native MSB-first time / longitude / latitude; no invented altitude or accuracy.
        for (point in points) {
            out.u32(point.time); out.u32(point.longitude.toFloat().toBits().toLong())
            out.u32(point.latitude.toFloat().toBits().toLong())
        }
        return out.result()
    }

    private fun header(out: Bytes, time: Long, timezone: Int, proto: Int) {
        require(time in 1..0xffff_ffffL && timezone in -48..56)
        out.u32(time); out.u8(timezone); out.u8(1); out.u8(proto); out.u8(0)
    }

    /** Strict decoder for this writer's v1 subset; native-app compatibility needs separate verification. */
    fun decodeHeartRate(data: ByteArray): List<HeartPoint> {
        require(data.size >= 9 && data[5].toInt() == 1)
        val proto = data[6].toInt() and 0xff
        val free = proto in setOf(8, 12, 16)
        val outdoor = proto in setOf(1, 2, 15)
        var at = if (free) 9 else 10
        require((data[8].toInt() and 0xff) == if (free) 0x80 else 0x0c)
        val rows = mutableListOf<HeartPoint>()
        while (at < data.size) {
            val countAt = if (outdoor) at + 4 else at
            require(countAt + 8 <= data.size)
            val count = u32(data, countAt)
            val start = u32(data, countAt + 4)
            val width = if (free) 2 else 1
            require(count in 1..86_400 && countAt + 8 + count * width <= data.size)
            at = countAt + 8
            repeat(count.toInt()) { index ->
                val bpm = data[at].toInt() and 0xff
                if (bpm > 0) rows += HeartPoint(start + index, bpm)
                at += width
            }
        }
        return rows
    }

    private fun u32(data: ByteArray, at: Int): Long = (0..3).fold(0L) { value, i ->
        value or ((data[at + i].toLong() and 0xff) shl (i * 8))
    }

    private class Bytes {
        private val data = mutableListOf<Byte>()
        fun u8(value: Int) { data += value.toByte() }
        fun u32(value: Long) { repeat(4) { data += (value ushr (it * 8)).toByte() } }
        fun result() = data.toByteArray()
    }
}
