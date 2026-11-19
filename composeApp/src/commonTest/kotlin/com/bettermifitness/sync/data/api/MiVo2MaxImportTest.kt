package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.health.HealthRecordOrigin
import com.bettermifitness.sync.health.InboundVo2Max
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class MiVo2MaxImportTest {
    private fun source(value: Double = 47.4) = InboundVo2Max(
        HealthRecordOrigin("id", "com.huami.watch.hmwatchmanager", null, 0, 1), 1_800_000_000, 28_800, value, 0)
    @Test fun usesNativeIntegerSchemaWithSourceIdentityAndZone() {
        val row = prepareVo2MaxImport(listOf(source())).rows.single()
        assertEquals("vo2_max", row.identity.key)
        assertEquals(JsonPrimitive(47), row.value["vo2_max"])
        assertEquals(28_800, row.zoneOffsetSeconds)
        assertEquals(setOf("com.huami.watch.hmwatchmanager:id"), row.sourceRecordKeys)
    }
    @Test fun conflictingSourcesAtOneTimeAreRejected() {
        val plan = prepareVo2MaxImport(listOf(source(), source(49.0)))
        assertTrue(plan.rows.isEmpty())
        assertEquals("VO2MAX_SOURCE_COLLISION", plan.issues.single().code)
    }
    @Test fun missingTimezoneAndNonfiniteValuesAreRejected() {
        assertTrue(prepareVo2MaxImport(listOf(source().copy(zoneOffsetSeconds = null))).rows.isEmpty())
        assertTrue(prepareVo2MaxImport(listOf(source(Double.NaN))).rows.isEmpty())
    }
}
