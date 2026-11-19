package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import com.bettermifitness.sync.health.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiWorkoutIdentityMigrationTest {
    private val source = InboundWorkout(HealthRecordOrigin("source", "com.huami.watch.hmwatchmanager", null, 1, 1000),
        WorkoutSession(1_800_000_000, 1_800_001_800, "outdoor_running", distanceMeters = 3000.0, tzIn15Min = 32), 1800)
    private val target = MiWorkoutImportMapper.map(source)!!
    private val old = target.copy(sid = "default")
    private val journal = Journal(WorkoutImportEntry(old, true))
    private val cloud = Cloud(old)
    private fun runner() = MiWorkoutImportRunner(cloud, journal)

    @Test
    fun nativeIdentityIsCopiedAndReadBeforeTheOwnedLegacyRowIsRetired() = runTest {
        assertEquals(1, runner().run("account", listOf(source)).revised)
        assertEquals(listOf("copy", "retire"), cloud.events)
        assertEquals(1, cloud.rows.size)
        assertTrue(target.matches(cloud.rows.single()))
        assertTrue(journal.entry.verified)
        assertEquals(1, runner().run("account", listOf(source)).existing)
        assertEquals(listOf("copy", "retire"), cloud.events)
    }

    @Test
    fun cancelledCopyIsReconciledWithoutBeingSentAgain() = runTest {
        cloud.copyError = CancellationException()
        assertFailsWith<CancellationException> { runner().run("account", listOf(source)) }
        assertFalse(journal.entry.verified)
        cloud.copyError = null
        assertEquals(1, runner().run("account", listOf(source)).revised)
        assertEquals(listOf("copy", "retire"), cloud.events)
    }

    @Test
    fun uncertainMissingCopyNeverRetiresTheOriginalAndIsNotReplayed() = runTest {
        cloud.persistCopy = false
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertEquals(listOf("copy"), cloud.events)
        assertTrue(old.matches(cloud.rows.single()))
    }

    @Test
    fun unconfirmedRetirementKeepsTheVerifiedCopyAndCannotReplayRetirement() = runTest {
        cloud.persistRetire = false
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertEquals(listOf("copy", "retire"), cloud.events)
        assertEquals(2, cloud.rows.size)
    }

    @Test
    fun externallyEditedLegacyRecordAndZeroBudgetAreReadOnly() = runTest {
        assertEquals(1, runner().run("account", listOf(source), maxWrites = 0).pending)
        assertTrue(journal.entry.verified)
        cloud.rows[0] = cloud.rows[0].copy(value = JsonObject(old.value + ("distance" to JsonPrimitive(9000))))
        assertEquals(1, runner().run("account", listOf(source)).pending)
        assertTrue(cloud.events.isEmpty())
    }

    private class Journal(var entry: WorkoutImportEntry) : WorkoutImportJournal {
        override suspend fun load(account: String, sourceKey: String) = entry
        override suspend fun reserve(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry?): Boolean {
            if (entry != previous) return false
            entry = WorkoutImportEntry(target)
            return true
        }
        override suspend fun reserveMigration(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry): Boolean {
            if (entry != previous) return false
            entry = WorkoutImportEntry(target, migrationFrom = previous.target)
            return true
        }
        override suspend fun updateMigration(account: String, expected: WorkoutImportEntry, updated: WorkoutImportEntry): Boolean {
            if (entry != expected) return false
            entry = updated
            return true
        }
        override suspend fun verified(account: String, target: MiWorkoutImportTarget) {
            check(entry.target == target)
            entry = entry.copy(verified = true)
        }
    }

    private class Cloud(val old: MiWorkoutImportTarget) : MiWorkoutImportCloud {
        val rows = mutableListOf(parseMiWorkoutExperimentRow(old.row()))
        val events = mutableListOf<String>()
        var copyError: Exception? = null
        var persistCopy = true
        var persistRetire = true
        override suspend fun read(from: Long, until: Long) = rows.toList()
        override suspend fun upload(target: MiWorkoutImportTarget, value: JsonObject) {
            events += "copy"
            if (persistCopy) rows += parseMiWorkoutExperimentRow(target.row(value))
            copyError?.let { throw it }
        }
        override suspend fun retire(target: MiWorkoutImportTarget, value: JsonObject) {
            assertTrue(rows.any { it.sid == "xiaomiwear_app_manually" && !it.deleted })
            events += "retire"
            if (persistRetire) rows.removeAll { old.matches(it) }
        }
    }
}
