package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.FitnessResponse
import com.bettermifitness.sync.data.api.FitnessResult
import com.bettermifitness.sync.data.api.HeartRateEntry
import com.bettermifitness.sync.data.api.MiWorkoutImportTarget
import com.bettermifitness.sync.data.api.SportRecordEntry
import com.mifitness.miclient.api.MiApiException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FitnessPageCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val window = SyncWindow(100, 200)
    private val scope = SyncPageScope("fixed-run", 17)
    private val newer = HeartRateEntry(time = 170, value = "synthetic-newer")
    private val older = HeartRateEntry(time = 130, value = "synthetic-older")

    @Test fun cancelledReadResumesFromDiskOnlyAfterTheServerReturnsTheNextPage() = runBlocking<Unit> {
        val first = cache().reader()
        val middle = HeartRateEntry(time = 150, value = "synthetic-middle")
        assertFailsWith<CancellationException> {
            collectFitnessWindow(window, 4, HeartRateEntry::time, checkpoints = first) { query, cursor ->
                assertEquals(window, query)
                when (cursor) {
                    null -> page(newer, next = "page-two")
                    "page-two" -> page(middle, next = "page-three")
                    else -> throw CancellationException("interrupted read")
                }
            }
        }
        val reopened = cache().reader()
        assertFailsWith<MiApiException.AuthExpired> {
            collectFitnessWindow(window, 4, HeartRateEntry::time, checkpoints = reopened) { _, cursor ->
                assertEquals("page-three", cursor)
                throw MiApiException.AuthExpired()
            }
        }
        val reads = mutableListOf<String?>()
        val rows = collectFitnessWindow(window, 4, HeartRateEntry::time, checkpoints = reopened) { query, cursor ->
            assertEquals(window, query)
            reads += cursor
            page(older)
        }
        assertEquals(listOf<String?>("page-three"), reads)
        assertEquals(listOf(newer, middle, older), rows)
    }

    @Test fun rejectedSavedCursorDiscardsItsRowsAndRestartsTheExactQuery() = runBlocking {
        cache().reader().save(window, prefix())
        val reopened = cache().reader()
        val calls = mutableListOf<String?>()
        val result = collectFitnessWindow(window, 4, HeartRateEntry::time, checkpoints = reopened) { query, cursor ->
            assertEquals(window, query)
            calls += cursor
            if (cursor == null) page(older) else throw MiApiException.Server(400, "Synthetic invalid cursor")
        }
        assertEquals(listOf("page-two", null), calls)
        assertEquals(listOf(older), result)
        assertNull(reopened.load(window))
    }

    @Test fun cacheIsIsolatedByAccountRegionWindowAndGenerationAndRejectsLateWritesAfterClear() = runBlocking {
        val cache = cache()
        val lease = cache.lease()
        val old = cache.reader()
        old.save(window, prefix())
        assertEquals(prefix(), old.load(window))
        assertNull(cache.bind(lease, scope, "other-user", "sg.example", "steps", window).load(window))
        assertNull(cache.bind(lease, scope, "test-user", "cn.example", "steps", window).load(window))
        assertNull(cache.bind(lease, scope, "test-user", "sg.example", "steps", SyncWindow(90, 200)).load(window))
        assertNull(cache.bind(lease, scope.copy(generation = 18), "test-user", "sg.example", "steps", window).load(window))
        assertNull(cache.bind(lease, scope.copy(id = "new-task"), "test-user", "sg.example", "steps", window).load(window))
        assertTrue(temporary.root.listFiles().orEmpty().none { it.name.contains("test-user") || it.name.contains("fixed-run") })
        cache.clear()
        old.save(window, prefix())
        assertNull(cache.reader().load(window))
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun expiredOrFutureFilesAreNotResumedAndCompletedScopeIsRemoved() = runBlocking {
        val cache = cache()
        cache.reader().save(window, prefix())
        val file = temporary.root.listFiles()!!.single()
        val modified = file.lastModified()
        assertNull(cache(now = { modified + 3 * 60 * 60_000L + 1 }).reader().load(window))
        cache.reader().save(window, prefix())
        assertNull(cache(now = { 0 }).reader().load(window))
        cache.reader().save(window, prefix())
        cache.discard(scope)
        assertNull(cache.reader().load(window))
    }

    @Test fun persistedSplitSkipsTheSaturatedParentWithoutCachingACompletedLeafAsAuthorization() = runBlocking {
        val first = cache().reader()
        assertFailsWith<CancellationException> {
            collectFitnessWindow(window, 1, HeartRateEntry::time, checkpoints = first) { query, _ ->
                if (query == window) page(newer, next = "too-many") else throw CancellationException("after split")
            }
        }
        val calls = mutableListOf<SyncWindow>()
        val rows = collectFitnessWindow(window, 1, HeartRateEntry::time, checkpoints = cache().reader()) { query, cursor ->
            calls += query
            assertNull(cursor)
            page(if (query.contains(newer.time)) newer else older)
        }
        assertEquals(listOf(SyncWindow(150, 200), SyncWindow(100, 150)), calls)
        assertEquals(listOf(newer, older), rows)
    }

    @Test fun medicalResumeKeepsEnvelopeRowsForTheMetricParserAndDoesNotGuessSplitBoundaries() = runBlocking<Unit> {
        val reader = cache().reader()
        val anchored = newer.copy(time = 90)
        reader.save(window, FitnessWindowProgress(prefix = FitnessPagePrefix(listOf(anchored), listOf("medical-two"))))
        assertEquals(listOf(anchored, older), collectFitnessWindow(window, 4, timestamp = null, checkpoints = reader) { bounds, key ->
            assertEquals(window, bounds)
            assertEquals("medical-two", key)
            page(older)
        })
        assertFailsWith<MiApiException.Unexpected> {
            collectFitnessWindow(window, 1, timestamp = null, checkpoints = reader) { bounds, key ->
                assertEquals(window, bounds)
                assertNull(key)
                page(older, "too-many")
            }
        }
    }

    @Test fun workoutResumePersistsOnlyProjectedSummariesAndCannotReuseADifferentOwnershipView() = runBlocking<Unit> {
        val target = MiWorkoutImportTarget("inbound", "rev-1", "outdoor_running", "running", 171, 181, 28_800,
            JsonObject(mapOf("duration" to JsonPrimitive(10))))
        val owned = SportRecordEntry(target.key, target.start, target.value.toString(), target.category, target.sid, 28_800)
        val firstNative = SportRecordEntry("outdoor_running", 170,
            """{"start_time":170,"duration":10,"distance":120,"route":["synthetic-route-must-not-persist"]}""", "running")
        val deleted = firstNative.copy(time = 169, deleted = true)
        val lastNative = firstNative.copy(time = 130, value = """{"start_time":130,"duration":8,"distance":75}""")
        val firstPage = FitnessResult(listOf(owned, firstNative, deleted), hasMore = true, nextKey = "sport-next")
        suspend fun reader(targets: List<MiWorkoutImportTarget>): FitnessWindowCheckpoints<WorkoutSourceIndexRow> {
            val cache = cache()
            return cache.bind(cache.lease(), scope, "test-user", "sg.example", workoutSourceStream(targets), window,
                WorkoutSourceIndexRow.serializer())
        }

        val first = reader(listOf(target))
        assertFailsWith<CancellationException> {
            collectFitnessWindow(window, 4, WorkoutSourceIndexRow::time, checkpoints = first) { _, cursor ->
                if (cursor == null) projectWorkoutSourcePage(firstPage, listOf(target)) else throw CancellationException("interrupted")
            }
        }
        val persisted = temporary.root.listFiles()!!.single().readText()
        assertTrue("synthetic-route-must-not-persist" !in persisted)
        assertTrue("\"rows\"" in persisted)
        assertNull(reader(emptyList()).load(window))
        assertNull(reader(listOf(target.copy(category = "other"))).load(window))
        val rows = collectFitnessWindow(window, 4, WorkoutSourceIndexRow::time, checkpoints = reader(listOf(target))) { bounds, cursor ->
            assertEquals(window, bounds)
            assertEquals("sport-next", cursor)
            projectWorkoutSourcePage(FitnessResult(listOf(lastNative)), listOf(target))
        }
        assertEquals(listOf(170L, 130L), rows.map { it.time })
        assertEquals(listOf(120.0, 75.0), rows.map { it.summary.distanceMeters })
        assertTrue(rows.all { it.summary.route.isEmpty() })
    }

    private fun cache(now: () -> Long = System::currentTimeMillis) =
        FitnessPageCache(temporary.root.absolutePath.toPath(), nowMillis = now)

    private suspend fun FitnessPageCache.reader() = bind(lease(), scope, "test-user", "sg.example", "steps", window)

    private fun prefix() = FitnessWindowProgress(FitnessPagePrefix(listOf(newer), listOf("page-two")))

    private fun page(row: HeartRateEntry, next: String? = null) =
        FitnessResponse(FitnessResult(listOf(row), hasMore = next != null, nextKey = next), code = 0)
}
