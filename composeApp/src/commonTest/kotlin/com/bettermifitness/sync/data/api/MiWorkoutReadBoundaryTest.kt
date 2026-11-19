package com.bettermifitness.sync.data.api

import kotlin.test.Test
import kotlin.test.assertEquals

class MiWorkoutReadBoundaryTest {
    @Test fun trainingScoresCanReadTheirExactWorkoutStartWithoutChangingPagination() {
        val request = buildMiWorkoutReadPayload(100, 200, "synthetic-cursor")
        val serverTimes = listOf(99L, 100L, 199L, 200L).filter {
            it > request.getValue("start_time") as Long && it < request.getValue("end_time") as Long
        }
        assertEquals(listOf(100L, 199L), serverTimes)
        assertEquals(50, request["limit"])
        assertEquals("synthetic-cursor", request["next_key"])
        assertEquals(true, request["reverse"])
        assertEquals(0L, buildMiWorkoutReadPayload(0, 1, null)["start_time"])
    }
}
