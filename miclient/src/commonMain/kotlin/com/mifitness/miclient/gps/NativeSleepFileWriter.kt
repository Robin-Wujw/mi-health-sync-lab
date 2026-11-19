package com.mifitness.miclient.gps

object NativeSleepFileWriter {
    data class Point(val time: Long, val value: Int)

    fun encode(referenceTime: Long, timezone: Int, start: Long, end: Long,
        heartRate: List<Point>, oxygen: List<Point>): ByteArray {
        require(referenceTime in 1..Int.MAX_VALUE.toLong() && timezone in -48..56)
        require(start in 1 until end && end <= Int.MAX_VALUE && end - start <= 86_400)
        require(heartRate.isNotEmpty() || oxygen.isNotEmpty())
        val output = mutableListOf<Byte>()
        fun byte(value: Int) { output += value.toByte() }
        fun short(value: Int) { repeat(2) { byte(value ushr (it * 8)) } }
        fun integer(value: Long) { repeat(4) { byte((value ushr (it * 8)).toInt()) } }
        fun series(points: List<Point>, maximum: Int) {
            if (points.isEmpty()) return
            require(points.all { it.time in start until end && it.value in 1..maximum })
            require(points.zipWithNext().all { (before, after) -> before.time < after.time })
            fun divisor(first: Long, second: Long): Long {
                var left = first
                var right = second
                while (right != 0L) { val remainder = left % right; left = right; right = remainder }
                return left
            }
            val interval = points.zipWithNext().map { (before, after) -> after.time - before.time }
                .fold(0L, ::divisor).takeIf { it > 0 } ?: 1L
            val count = (points.last().time - points.first().time) / interval + 1
            require(interval <= Short.MAX_VALUE && count <= Short.MAX_VALUE)
            short(interval.toInt()); short(count.toInt()); integer(points.first().time)
            val byTime = points.associate { it.time to it.value }
            repeat(count.toInt()) { index -> byte(byTime[points.first().time + index * interval] ?: 0) }
        }
        integer(referenceTime); byte(timezone); byte(2); byte(0)
        byte(0xc0 or (if (heartRate.isEmpty()) 0 else 0x20) or (if (oxygen.isEmpty()) 0 else 0x10))
        byte(1); integer(start); integer(end)
        series(heartRate, 127); series(oxygen, 100)
        return output.toByteArray()
    }
}
