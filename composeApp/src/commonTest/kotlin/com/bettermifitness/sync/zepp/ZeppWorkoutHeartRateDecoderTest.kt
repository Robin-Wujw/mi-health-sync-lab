package com.bettermifitness.sync.zepp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZeppWorkoutHeartRateDecoderTest {
    @Test fun expandsVendorDeclaredRunsWithoutResamplingOrInventingATail() {
        val points=ZeppWorkoutHeartRateDecoder.decode("0,100;2,1;1,-2;",100,106)
        assertEquals(listOf(100L,101L,102L,103L),points.map {it.timeSec})
        assertEquals(listOf(100.0,101.0,101.0,99.0),points.map {it.value})
    }
    @Test fun inclusiveVendorEndIsExcludedFromTheDestinationSession() {
        val points=ZeppWorkoutHeartRateDecoder.decode("0,100;4,0",100,104)
        assertEquals(listOf(100L,101L,102L,103L),points.map {it.timeSec})
    }
    @Test fun zeroSensorRunsRemainGapsAndDoNotCompressTheClock() {
        val points=ZeppWorkoutHeartRateDecoder.decode("0,100;1,-100;2,20",100,104)
        assertEquals(listOf(100L,102L,103L),points.map {it.timeSec})
    }
    @Test fun malformedOrOverflowingRunsNeverProducePartialSuccess() {
        for(raw in listOf("0,100;bad","0,100;86401,0","0,100;1,200","0,100;-1,0")) {
            assertFailsWith<ZeppException> {ZeppWorkoutHeartRateDecoder.decode(raw,100,104)}
        }
    }
}
