package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.FitnessResponse
import com.bettermifitness.sync.data.api.FitnessResult
import com.bettermifitness.sync.data.api.MiPaginationIssue
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

    @Test fun invalidCursorDiscardsPartialRowsAndRestartsTheSameWindowOnce() = runBlocking {
        val original = SyncWindow(100, 200)
        val calls = mutableListOf<Pair<SyncWindow, String?>>()
        val rows = collectFitnessWindow(original, maxPages = 4, timestamp = { value: Long -> value }) { window, token ->
            calls += window to token
            when (calls.size) {
                1 -> FitnessResponse(FitnessResult(listOf(190L), hasMore = true, nextKey = "bad"), code = 0)
                2 -> FitnessResponse(FitnessResult(listOf(180L), hasMore = true, nextKey = "bad"), code = 0)
                else -> FitnessResponse(FitnessResult(listOf(123L)), code = 0)
            }
        }
        assertEquals(listOf(original to null, original to "bad", original to null), calls)
        assertEquals(listOf(123L), rows)
    }

    @Test fun pageCapBisectsIntoHalfOpenWindowsWithoutReturningAnIncompleteParent() = runBlocking {
        val calls = mutableListOf<SyncWindow>()
        val rows = collectFitnessWindow(SyncWindow(100, 200), maxPages = 1, timestamp = { value: Long -> value }) { window, _ ->
            calls += window
            val page = when (window) {
                SyncWindow(100, 200) -> FitnessResult(listOf(199L), hasMore = true, nextKey = "full")
                SyncWindow(150, 200) -> FitnessResult(listOf(200L, 150L, 149L))
                else -> FitnessResult(listOf(150L, 149L, 100L, 99L))
            }
            FitnessResponse(page, code = 0)
        }
        assertEquals(listOf(SyncWindow(100, 200), SyncWindow(150, 200), SyncWindow(100, 150)), calls)
        assertEquals(listOf(150L, 149L, 100L), rows)
    }

    @Test fun requestBudgetExhaustionAfterACompletedHalfStillFailsTheWholeRead() = runBlocking {
        val error = assertFailsWith<MiApiException.Unexpected> {
            collectFitnessWindow(SyncWindow(100, 200), maxPages = 1, timestamp = { value: Long -> value }, maxRequests = 2) { window, _ ->
                FitnessResponse(FitnessResult(listOf(window.from), hasMore = window.from == 100L, nextKey = "full"), code = 0)
            }
        }
        assertEquals(MiPaginationIssue.REQUEST_BUDGET, error.paginationIssue)
    }

    @Test fun aSingleSecondThatStillSaturatesCannotBeAssumedComplete() = runBlocking {
        val error = assertFailsWith<MiApiException.Unexpected> {
            collectFitnessWindow(SyncWindow(100, 101), maxPages = 1, timestamp = { value: Long -> value }) { _, _ ->
                FitnessResponse(FitnessResult(listOf(100L), hasMore = true, nextKey = "full"), code = 0)
            }
        }
        assertEquals(MiPaginationIssue.PAGE_LIMIT, error.paginationIssue)
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
