package com.bettermifitness.sync.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppleHealthRevisionTest {
    @Test fun aLowerHashStillReplacesTheHistoricalHighHash() {
        val legacy = AppleHealthRevision(8_000_000_000_000_000_123L)
        assertNull(nextAppleHealthRevision(legacy, legacy.version.toString()))
        val revised = nextAppleHealthRevision(legacy, "17")
        assertEquals(8_000_000_000_000_000_124L, revised)
        val afterReinstall = AppleHealthRevision(checkNotNull(revised), "17")
        assertNull(nextAppleHealthRevision(afterReinstall, "17"))
        assertEquals(8_000_000_000_000_000_125L, nextAppleHealthRevision(afterReinstall, "3"))
    }

    @Test fun versionAndFingerprintAreIndependentAndOverflowFailsClosed() {
        assertEquals(1L, nextAppleHealthRevision(null, "finite-content"))
        val stored = AppleHealthRevision(17L, "previous")
        assertEquals(18L, nextAppleHealthRevision(stored, "17"))
        assertFailsWith<IllegalStateException> {
            nextAppleHealthRevision(AppleHealthRevision(Long.MAX_VALUE, "old"), "new")
        }
    }
}
