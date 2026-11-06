package com.bettermifitness.sync.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthWritePolicyTest {
    @Test
    fun correctedLowerValuesAndClockRollbackStillGetHigherVersions() {
        val initial = nextHealthRecordRevision(0L, 1_800_000_000_000L)
        val corrected = nextHealthRecordRevision(initial, 1_700_000_000_000L)
        val retry = nextHealthRecordRevision(corrected, 1_700_000_000_000L)
        assertEquals(initial + 1L, corrected)
        assertEquals(corrected + 1L, retry)
        // The persisted revision, rather than a hash of the measurement value,
        // is the only state needed after a restart.
        assertEquals(retry + 1L, nextHealthRecordRevision(retry, 1L))
    }

    @Test
    fun exhaustedOrCorruptRevisionFailsInsteadOfWrappingToAnOlderVersion() {
        assertFailsWith<IllegalStateException> { nextHealthRecordRevision(Long.MAX_VALUE, 1L) }
        assertFailsWith<IllegalStateException> { nextHealthRecordRevision(-1L, 1L) }
        assertFailsWith<IllegalStateException> { stampedHealthRecordVersion(Long.MAX_VALUE, 1L) }
        assertFailsWith<IllegalStateException> { stampedHealthRecordVersion(0L, 1L) }
    }

    @Test
    fun aLaterRevisionAlwaysGetsAHigherVersionWhateverItsFingerprint() {
        val revision = 1_800_000_000_000L
        val highest = stampedHealthRecordVersion(revision, -1L)
        val next = stampedHealthRecordVersion(revision + 1L, 0L)
        assertTrue(next > highest)
    }

    @Test
    fun stampedVersionsBeatEveryVersionWrittenBeforeTheyExisted() {
        val firstStamped = stampedHealthRecordVersion(1_800_000_000_000L, 0L)
        // Bare millisecond revisions of healthlab.18, even if it had run for centuries.
        assertTrue(firstStamped > 99_999_999_999_999L)
        assertEquals(null, stampedContentFingerprint(1_800_000_000_000L))
        assertEquals(null, stampedContentFingerprint(99_999_999_999_999L))
    }

    @Test
    fun aStampedVersionKeepsItsFingerprint() {
        val fingerprint = HealthRecordIds.version("anything")
        val version = stampedHealthRecordVersion(1_800_000_000_000L, fingerprint)
        assertEquals(fingerprint and 0xFFFFFL, stampedContentFingerprint(version))
    }

    @Test
    fun writeDecisions() {
        fun state(fingerprint: Long, detail: Int = 0) =
            HealthRecordState(stampedHealthRecordVersion(1_800_000_000_000L, fingerprint), detail)

        // Nothing stored yet.
        assertTrue(shouldWriteHealthRecord(state(1), null))
        // Same content.
        assertFalse(shouldWriteHealthRecord(state(1), state(1)))
        assertFalse(shouldWriteHealthRecord(state(1, detail = 5), state(1, detail = 5)))
        // Changed content with the same detail, in both fingerprint directions.
        assertTrue(shouldWriteHealthRecord(state(1), state(2)))
        assertTrue(shouldWriteHealthRecord(state(2), state(1)))
        // A fuller copy wins and a less complete one loses, whatever the fingerprints say.
        assertTrue(shouldWriteHealthRecord(state(1, detail = 6), state(2, detail = 5)))
        assertTrue(shouldWriteHealthRecord(state(1, detail = 6), state(1, detail = 5)))
        assertFalse(shouldWriteHealthRecord(state(1, detail = 4), state(2, detail = 5)))
    }

    @Test
    fun writeDecisionsAgainstRecordsWithoutAFingerprint() {
        val candidate = HealthRecordState(stampedHealthRecordVersion(1_800_000_000_000L, 1L), detailCount = 3)
        // The stored content is unknown, so write once to stamp it.
        assertTrue(shouldWriteHealthRecord(candidate, HealthRecordState(1_700_000_000_000L, detailCount = 3)))
        // Its detail count is still known, and it stays protected.
        assertFalse(shouldWriteHealthRecord(candidate, HealthRecordState(1_700_000_000_000L, detailCount = 4)))
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
