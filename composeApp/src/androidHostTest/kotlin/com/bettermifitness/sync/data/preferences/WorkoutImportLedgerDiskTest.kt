package com.bettermifitness.sync.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bettermifitness.sync.data.api.MiWorkoutImportTarget
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutImportLedgerDiskTest {
    @Test
    fun legacyMigrationAccountIsolationCasAndFileStagesSurviveReopening(): Unit = runBlocking {
        val directory = Files.createTempDirectory("workout-ledger-").toFile()
        val file = directory.resolve("journal.preferences_pb")
        val jobs = mutableListOf<Job>()
        fun open() = SupervisorJob().let { job ->
            jobs += job
            PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { file.absolutePath.toPath() }) to job
        }
        val target = MiWorkoutImportTarget("source", "revision", "indoor_running", "running", 100, 200, 28_800, JsonObject(emptyMap()))
        try {
            val (store, writerJob) = open()
            val writer = WorkoutImportLedger(store)
            val old = WorkoutImportEntry(target, true)
            val legacy = stringPreferencesKey("workout_import_v1_" + HealthRecordIds.version("account-a", target.sourceKey))
            store.edit { it[legacy] = Json.encodeToString(old) }
            assertEquals(old, writer.load("account-a", target.sourceKey))
            assertEquals(listOf(target), writer.verifiedTargets("account-a"))
            assertNull(writer.load("account-b", target.sourceKey))
            assertFalse(writer.reserve("account-a", target, null))
            writer.verified("account-a", target)
            val preservedValue = JsonObject(mapOf("server_extra" to JsonPrimitive(17),
                "start_time" to JsonPrimitive(100), "end_time" to JsonPrimitive(200)))
            writer.rememberSummary("account-a", target, preservedValue)
            assertEquals(listOf(target), writer.verifiedTargets("account-a"))
            assertTrue(writer.verifiedTargets("account-b").isEmpty())
            val files = target.copy(sourceKey = "FILES:source")
            assertTrue(writer.reserveFiles("account-a", files, null, mapOf("0" to WorkoutFileStage.NEW)))
            val initial = writer.load("account-a", files.sourceKey)!!
            val attempted = initial.copy(fileStates = mapOf("0" to WorkoutFileStage.ATTEMPTED))
            assertTrue(writer.updateFiles("account-a", initial, attempted))
            assertFalse(writer.updateFiles("account-a", initial, attempted))
            assertFailsWith<IllegalArgumentException> { writer.updateFiles("account-a", attempted, initial) }
            assertFailsWith<IllegalStateException> { writer.verified("account-a", files) }
            writerJob.cancelAndJoin()

            val (reopened, readerJob) = open()
            val reader = WorkoutImportLedger(reopened)
            assertEquals(preservedValue, reader.load("account-a", target.sourceKey)!!.lastVerifiedValue)
            assertEquals(attempted, reader.load("account-a", files.sourceKey))
            assertEquals(listOf(target), reader.verifiedTargets("account-a"))
            val checked = attempted.copy(fileStates = mapOf("0" to WorkoutFileStage.VERIFIED), promotionAttempted = true)
            assertTrue(reader.updateFiles("account-a", attempted, checked))
            reader.verified("account-a", files)
            assertTrue(reader.load("account-a", files.sourceKey)!!.verified)
            assertEquals(listOf(target), reader.verifiedTargets("account-a"))
            val owned = reader.load("account-a", target.sourceKey)!!
            val nativeTarget = target.copy(sid = "xiaomiwear_app_manually")
            assertTrue(reader.reserveMigration("account-a", nativeTarget, owned))
            val migration = reader.load("account-a", target.sourceKey)!!
            assertEquals(listOf(target), reader.targetsForLoopPrevention("account-a"))
            val copied = migration.copy(migrationCopyAttempted = true)
            assertTrue(reader.updateMigration("account-a", migration, copied))
            readerJob.cancelAndJoin()
            val (third, _) = open()
            val retained = WorkoutImportLedger(third)
            assertEquals(copied, retained.load("account-a", target.sourceKey))
            assertEquals(setOf(target, nativeTarget), retained.targetsForLoopPrevention("account-a").toSet())
            assertTrue(retained.targetsForLoopPrevention("account-b").isEmpty())
            retained.verified("account-a", nativeTarget)
            assertEquals(listOf(nativeTarget), retained.verifiedTargets("account-a"))
        } finally {
            jobs.forEach { it.cancelAndJoin() }
            directory.deleteRecursively()
        }
    }
}
