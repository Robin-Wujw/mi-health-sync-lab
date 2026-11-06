package com.bettermifitness.sync.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HealthWritePolicyTest {
    @Test
    fun correctedLowerValuesAndClockRollbackStillGetHigherVersions() {
        val initial = nextHealthRecordVersion(0L, 1_800_000_000_000L)
        val corrected = nextHealthRecordVersion(initial, 1_700_000_000_000L)
        val retry = nextHealthRecordVersion(corrected, 1_700_000_000_000L)
        assertEquals(initial + 1L, corrected)
        assertEquals(corrected + 1L, retry)
        // The persisted revision, rather than a hash of the measurement value,
        // is the only state needed after a restart.
        assertEquals(retry + 1L, nextHealthRecordVersion(retry, 1L))
    }

    @Test
    fun exhaustedOrCorruptRevisionFailsInsteadOfWrappingToAnOlderVersion() {
        assertFailsWith<IllegalStateException> { nextHealthRecordVersion(Long.MAX_VALUE, 1L) }
        assertFailsWith<IllegalStateException> { nextHealthRecordVersion(-1L, 1L) }
    }

    @Test
    fun denyingBodyFatDoesNotBlockWeightOrHideTheSkippedMetric() {
        val plan = planHealthWrites(
            listOf(
                HealthWriteCandidate("weight", "Weight", setOf("WRITE_WEIGHT")),
                HealthWriteCandidate("fat1", "Body fat", setOf("WRITE_BODY_FAT")),
                HealthWriteCandidate("fat2", "Body fat", setOf("WRITE_BODY_FAT")),
            ),
            setOf("WRITE_WEIGHT"),
        )
        assertEquals(listOf("weight"), plan.writableRecords)
        assertEquals(1, plan.skippedReasons.size)
        assertTrue(plan.skippedReasons.single().contains("Body fat"))
        assertTrue(!plan.skippedReasons.single().contains("WRITE_BODY_FAT"))
        assertTrue(plan.skippedReasons.single().contains("2 record(s) skipped"))
        val failure = HealthWriteIncompleteException(1, plan.skippedReasons)
        assertEquals(1, failure.writtenRecordCount)
        assertTrue(failure.message.orEmpty().contains("incomplete"))
    }

    @Test
    fun unrelatedMissingPermissionsDoNotBlockAnAuthorizedMetric() {
        val plan = planHealthWrites(
            listOf(HealthWriteCandidate("heart-rate", "Heart rate", setOf("WRITE_HEART_RATE"))),
            setOf("WRITE_HEART_RATE"),
        )
        assertEquals(listOf("heart-rate"), plan.writableRecords)
        assertTrue(plan.skippedReasons.isEmpty())
    }

    @Test
    fun noGrantedPermissionsProducesAnExplicitSkippedReasonForEveryType() {
        val plan = planHealthWrites(
            listOf(
                HealthWriteCandidate("sleep", "Sleep", setOf("WRITE_SLEEP")),
                HealthWriteCandidate("hrv", "HRV", setOf("WRITE_HEART_RATE_VARIABILITY")),
            ),
            emptySet(),
        )
        assertTrue(plan.writableRecords.isEmpty())
        assertEquals(2, plan.skippedReasons.size)
    }
}
