package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.FitnessResponse
import com.bettermifitness.sync.data.api.FitnessResult
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncDataPolicyTest {
    @Test fun allPagesAreRead() = runBlocking<Unit> {
        val out = collectFitnessPages { token ->
            if (token == null) FitnessResponse(FitnessResult(listOf(1), hasMore = true, nextKey = "two"), code = 0)
            else FitnessResponse(FitnessResult(listOf(2)), code = 0)
        }
        assertEquals(listOf(1, 2), out)
    }
    @Test fun repeatedTokenIsNotReportedAsSuccess() = runBlocking<Unit> {
        assertFailsWith<MiApiException.Unexpected> {
            collectFitnessPages { FitnessResponse(FitnessResult(listOf(1), hasMore = true, nextKey = "same"), code = 0) }
        }
    }
    @Test fun missingTokenAndPageLimitAreVisibleFailures() = runBlocking<Unit> {
        assertFailsWith<MiApiException.Unexpected> {
            collectFitnessPages { FitnessResponse(FitnessResult(listOf(1), hasMore = true, nextKey = ""), code = 0) }
        }
        assertFailsWith<MiApiException.Unexpected> {
            collectFitnessPages(maxPages = 1) { FitnessResponse(FitnessResult(listOf(1), hasMore = true, nextKey = "two"), code = 0) }
        }
    }
    @Test fun businessErrorAndMissingResultAreNotEmptyData() {
        assertFailsWith<MiApiException.Server> { FitnessResponse<Int>(code = 500).checkedResult() }
        assertFailsWith<MiApiException.Unexpected> { FitnessResponse<Int>(code = 0).checkedResult() }
        assertTrue(FitnessResponse(FitnessResult(emptyList<Int>()), code = 0).checkedResult().dataList.isEmpty())
    }
    @Test fun failedReadIsNotCachedAndSuccessfulReadIsShared() = runBlocking<Unit> {
        val cache = SyncReadCache<Int>()
        var calls = 0
        assertFailsWith<MiApiException.AuthExpired> { cache.get("sleep") { calls++; throw MiApiException.AuthExpired() } }
        assertEquals(42, cache.get("sleep") { calls++; 42 })
        assertEquals(42, cache.get("sleep") { calls++; 99 })
        assertEquals(2, calls)
    }
    @Test fun windowRetainsCrossMidnightSessionWithoutClippingOrInventingStages() {
        val window = SyncWindow.parse("2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z")
        assertTrue(window.overlaps(window.from - 7200, window.from + 18000))
        assertFalse(window.overlaps(window.from - 7200, window.from))
        assertFalse(window.contains(window.to))
        assertTrue(window.contains(window.from))
    }
}
