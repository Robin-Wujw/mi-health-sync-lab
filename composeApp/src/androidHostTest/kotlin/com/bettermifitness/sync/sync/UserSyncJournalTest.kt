package com.bettermifitness.sync.sync

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserSyncJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun processRedeliveryLoadsOriginalRequestAndNeverReplaysTerminalRun() = runBlocking {
        val path = path()
        val original = request("original", 30).copy(sources = UserSyncSources(14L, "Asia/Singapore"))
        withJournal(path) {
            assertNull(claim("missing", "first"))
            assertTrue(accept(original))
            assertEquals(original, claim(original.runId, "first"))
            assertNull(claim(original.runId, "first"))
            assertFalse(accept(request("original", 1)))
        }
        withJournal(path) {
            assertEquals(UserSyncJournal.Claimed(original, SyncTaskSource.RECOVERED), claimTask(original.runId, "second"))
            finish(original.runId, SyncOutcome.Success)
        }
        withJournal(path) {
            assertNull(claim(original.runId, "third"))
            assertFalse(accept(original))
            assertEquals(listOf(SyncOutcome.STATUS_SUCCESS), history().map { it.status })
            assertEquals(SyncTaskSource.RECOVERED, history().single().source)
            assertEquals(original.selection.from.toString(), history().single().from)
        }
    }

    @Test fun automaticInterruptionIsArchivedOnlyWhenAnotherProcessOpensTheJournal() = runBlocking {
        val file = path()
        val automatic = request("auto-interrupted", 1)
        val task = ActiveSyncTask(automatic.runId, SyncTaskSource.AUTOMATIC, automatic.accountGeneration,
            automatic.selection, automatic.selection.to)
        withJournal(file) {
            beginAutomatic(task, "original-process")
            reconcileAutomatic("original-process")
            assertTrue(history().isEmpty())
        }
        withJournal(file) {
            reconcileAutomatic("new-process")
            reconcileAutomatic("new-process")
            assertNull(claim(automatic.runId, "new-process"))
            assertEquals(task.id, history().single().id)
            assertEquals("interrupted", history().single().status)
            assertEquals(task.selection.from.toString(), history().single().from)
        }
    }

    @Test fun automaticHistoryCannotReplaceTheManualTerminalOutcomeOrDuplicateARun() = runBlocking {
        val file = path()
        val manual = request("manual", 30)
        val automatic = SyncTaskSummary("scheduled", SyncOutcome.STATUS_NO_DATA, 1,
            "2026-11-19T12:05:00Z", manual.accountGeneration, SyncTaskSource.AUTOMATIC, noData = 2)
        withJournal(file) {
            assertTrue(accept(manual))
            assertEquals(manual, claim(manual.runId, "process"))
            val unrelated = automatic.copy(succeeded = 99)
            finish(manual.runId, SyncOutcome.Failed("private details"), "process", execution = unrelated)
            beginAutomatic(ActiveSyncTask(automatic.id, SyncTaskSource.AUTOMATIC, manual.accountGeneration,
                manual.selection, manual.selection.to), "process")
            recordAutomatic(automatic)
            recordAutomatic(automatic.copy(noData = 99))
        }
        withJournal(file) {
            reconcileAutomatic("next-process")
            val history = history()
            assertEquals(listOf(automatic.id, manual.runId), history.map { it.id })
            assertEquals(2, history.first().noData)
            assertEquals(SyncOutcome.STATUS_FAILED, history.last().status)
            assertNull(history.last().succeeded)
            assertFalse(history.toString().contains("private details"))
        }
    }

    @Test fun stoppingIsDurableAndCannotResurrectAfterProcessDeath() = runBlocking {
        val path = path()
        val original = request("original", 30)
        withJournal(path) {
            assertTrue(accept(original))
            assertEquals(original, claim(original.runId, "first"))
            stopping(original.runId)
        }
        withJournal(path) {
            assertNull(claim(original.runId, "second"))
            assertEquals(listOf(SyncOutcome.STATUS_CANCELLED), history().map { it.status })
            val replacement = request("replacement", 1)
            assertTrue(accept(replacement))
            finish(original.runId, SyncOutcome.Success)
            assertEquals(replacement, claim(replacement.runId, "second"))
        }
    }

    @Test fun newerRequestSupersedesOrphanAndHistoryIsBoundedAndSanitized() = runBlocking {
        withJournal(path()) {
            assertTrue(accept(request("orphan", 30)))
            assertTrue(accept(request("next", 1)))
            assertNull(claim("orphan", "first"))
            assertEquals("interrupted", history().single().status)
            finish("next", SyncOutcome.Failed("private details"))
            assertFalse(history().toString().contains("private details"))
            repeat(24) { index ->
                val id = "run-$index"
                assertTrue(accept(request(id, 1)))
                finish(id, SyncOutcome.Success)
            }
            assertEquals((23 downTo 4).map { "run-$it" }, history().map { it.id })
        }
    }

    @Test fun processLossDuringReplacementKeepsExactlyNewestUnmodifiedCandidate() = runBlocking {
        val path = path()
        val original = request("running", 30)
        val superseded = request("pending-seven", 7)
        val latest = request("pending-one", 1)
        withJournal(path) {
            assertTrue(accept(original))
            assertEquals(original, claim(original.runId, "old-process"))
            assertTrue(stageReplacement(superseded))
            assertTrue(stageReplacement(latest))
            assertTrue(stageReplacement(latest))
            assertFalse(stageReplacement(request(latest.runId, 14)))
            assertFalse(accept(superseded, fromQueue = true))
        }
        withJournal(path) {
            assertNull(claim(original.runId, "reborn"))
            assertNull(claim(latest.runId, "reborn"))
            assertEquals(latest, recoverable.first())
            assertEquals(mapOf(original.runId to "replaced", superseded.runId to "replaced"),
                history().associate { it.id to it.status })
            assertFalse(accept(request("unrelated", 7)))
            assertTrue(accept(latest, fromQueue = true))
        }
        withJournal(path) {
            assertEquals(latest, recoverable.first())
            assertTrue(stageReplacement(latest))
            assertTrue(accept(latest, fromQueue = true))
            assertEquals(latest, claim(latest.runId, "resumed"))
            assertNull(recoverable.first())
            assertNull(claim(original.runId, "resumed"))
        }
    }

    @Test fun explicitStopCancelsPersistedCandidateBeforeEitherCanRedeliver() = runBlocking {
        val path = path()
        val old = request("old", 30)
        val queued = request("queued", 1)
        withJournal(path) {
            assertTrue(accept(old))
            assertEquals(old, claim(old.runId, "old-process"))
            assertTrue(stageReplacement(queued))
            stopEverything()
        }
        withJournal(path) {
            assertNull(recoverable.first())
            assertFalse(stageReplacement(queued))
            assertNull(claim(old.runId, "reborn"))
            assertNull(claim(queued.runId, "reborn"))
            assertEquals(mapOf(old.runId to SyncOutcome.STATUS_CANCELLED, queued.runId to SyncOutcome.STATUS_CANCELLED),
                history().associate { it.id to it.status })
        }
    }

    private fun path(): Path = temporary.root.resolve("user_sync_v1.json").absolutePath.toPath()

    private suspend fun withJournal(path: Path, action: suspend UserSyncJournal.() -> Unit) {
        val job = Job()
        try {
            UserSyncJournal.create(path, CoroutineScope(job + Dispatchers.IO)).action()
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun request(id: String, days: Int): UserSyncRequest {
        val end = Instant.parse("2026-11-19T12:00:00Z")
        return UserSyncRequest(id, 21L, ActiveSyncSelection(days, setOf("sleep"), end - days.days, end))
    }
}
