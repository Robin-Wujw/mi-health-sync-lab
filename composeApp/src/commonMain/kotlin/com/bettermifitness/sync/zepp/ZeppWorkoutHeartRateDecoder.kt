package com.bettermifitness.sync.zepp

import com.bettermifitness.sync.data.api.WorkoutTimedSample

/** Zepp's duration/delta run-length stream, independently matched against Vitalis.
 * Expands declared runs only; an undeclared tail remains absent. */
object ZeppWorkoutHeartRateDecoder {
    fun decode(encoded: String, start: Long, end: Long): List<WorkoutTimedSample> {
        require(end > start && end - start <= 86_400 && encoded.length <= 2_000_000)
        var clock = start
        var bpm = 0
        var first = true
        val result = mutableListOf<WorkoutTimedSample>()
        for (atom in encoded.split(';')) {
            if (atom.isEmpty()) continue
            val pair = atom.split(',')
            if (pair.size != 2) throw ZeppException(ZeppFailure.RESPONSE)
            val span = if (pair[0].isEmpty()) 1 else pair[0].toIntOrNull() ?: throw ZeppException(ZeppFailure.RESPONSE)
            val delta = pair[1].toIntOrNull() ?: throw ZeppException(ZeppFailure.RESPONSE)
            if (span !in 0..86_400 || delta !in -255..255) throw ZeppException(ZeppFailure.RESPONSE)
            bpm += delta
            if (bpm !in 0..255) throw ZeppException(ZeppFailure.RESPONSE)
            val count = span + if (first) 1 else 0
            first = false
            if (clock + count > end + 1) throw ZeppException(ZeppFailure.RESPONSE)
            repeat(count) {
                if (clock < end && bpm > 0) result += WorkoutTimedSample(clock, bpm.toDouble())
                clock++
            }
        }
        return result
    }
}
