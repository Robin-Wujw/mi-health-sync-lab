package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.WeightMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotEquals

class BodySyncPolicyTest {
    private val weight = WeightMeasurement(timestamp = 1_800_000_000L, weightKg = 70.0)

    @Test
    fun lateBodyFatIsImportedOnlyAfterOptIn() {
        val hc = weight.copy(bodyFatPercent = 20.0, bodyWaterMassKg = 40.0)
        assertNull(planBodySync(weight, hc, false).toMi)
        assertEquals(20.0, planBodySync(weight, hc, true).toMi?.bodyFatPercent)
        assertEquals(40.0, planBodySync(weight, hc, true).toMi?.bodyWaterMassKg)
    }

    @Test
    fun lateMiCompositionExportsDespiteUnchangedWeight() {
        val mi = weight.copy(bodyFatPercent = 20.0, boneMassKg = 2.5)
        val plan = planBodySync(mi, weight, false)
        assertEquals(20.0, plan.toHealth?.bodyFatPercent)
        assertNull(plan.toMi)
    }

    @Test
    fun newerMeasurementDoesNotBorrowYesterdayComposition() {
        val mi = weight.copy(bodyFatPercent = 22.0)
        val hc = weight.copy(timestamp = weight.timestamp + 86_400)
        assertNull(planBodySync(mi, hc, true).toMi?.bodyFatPercent)
    }

    @Test
    fun closeTimeEchoDoesNotMergeDifferentMeasurements() {
        val hc = weight.copy(timestamp = weight.timestamp + 10, bodyFatPercent = 20.0)
        assertEquals(BodySyncPlan(), planBodySync(weight, hc, true))
    }

    @Test
    fun exactTimeWeightConflictIsVisible() {
        assertFailsWith<BodySyncConflictException> {
            planBodySync(weight, weight.copy(weightKg = 71.0), true)
        }
    }

    @Test
    fun bodyConflictDoesNotSilentlyOverwrite() {
        val error = assertFailsWith<BodySyncConflictException> {
            planBodySync(weight.copy(bodyFatPercent = 20.0),
                weight.copy(bodyFatPercent = 25.0), true)
        }
        assertEquals(setOf("body_fat_rate"), error.fields)
    }

    @Test
    fun roundingIsNotAConflictAndLeanIsNotMuscle() {
        val plan = planBodySync(
            weight.copy(bodyFatPercent = 20.0, muscleMassKg = 45.0),
            weight.copy(bodyFatPercent = 20.04, leanBodyMassKg = 56.0), true,
        )
        assertEquals(45.0, plan.toMi?.muscleMassKg)
        assertEquals(56.0, plan.toMi?.leanBodyMassKg)
    }

    @Test
    fun exportFingerprintIncludesOptionalValues() {
        assertEquals(bodyExportFingerprint(weight), bodyExportFingerprint(weight.copy()))
        assertNotEquals(bodyExportFingerprint(weight),
            bodyExportFingerprint(weight.copy(bodyWaterMassKg = 40.0)))
    }
}
