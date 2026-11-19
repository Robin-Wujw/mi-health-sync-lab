package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bettermifitness.sync.data.api.MiWorkoutImportTarget
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.bettermifitness.sync.data.api.parseMiWorkoutExperimentRow

@Serializable
data class WorkoutImportEntry(
    val target: MiWorkoutImportTarget,
    val verified: Boolean = false,
    val fileProtocolVersion: Int = 0,
    val fileStates: Map<String, WorkoutFileStage> = emptyMap(),
    val promotionAttempted: Boolean = false,
    val fileWriteResults: Map<String, String> = emptyMap(),
    val fileRecoveryAttempted: Set<String> = emptySet(),
    val migrationFrom: MiWorkoutImportTarget? = null,
    val migrationCopyAttempted: Boolean = false,
    val migrationDeleteAttempted: Boolean = false,
    val migrationDeleteProtocol: Int = 0,
    val lastVerifiedValue: JsonObject? = null,
)

@Serializable
enum class WorkoutFileStage { NEW, ATTEMPTED, VERIFIED }

interface WorkoutImportJournal {
    suspend fun load(account: String, sourceKey: String): WorkoutImportEntry?
    suspend fun reserve(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry?): Boolean
    suspend fun verified(account: String, target: MiWorkoutImportTarget)
    suspend fun rememberSummary(account: String, target: MiWorkoutImportTarget, value: JsonObject) = Unit
    suspend fun hasUnconfirmedFileWrite(account: String, target: MiWorkoutImportTarget, exceptSourceKey: String): Boolean = false
    suspend fun reserveFiles(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry?,
        states: Map<String, WorkoutFileStage>): Boolean = false
    suspend fun updateFiles(account: String, expected: WorkoutImportEntry, updated: WorkoutImportEntry): Boolean = false
    suspend fun reserveMigration(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry): Boolean = false
    suspend fun updateMigration(account: String, expected: WorkoutImportEntry, updated: WorkoutImportEntry): Boolean = false
}

class WorkoutImportLedger(private val dataStore: DataStore<Preferences>) : WorkoutImportJournal {
    private fun accountPrefix(account: String) = "workout_import_v1_${HealthRecordIds.version(account)}_"
    private fun key(account: String, source: String) = stringPreferencesKey(accountPrefix(account) + HealthRecordIds.version(source))
    private fun legacyKey(account: String, source: String) =
        stringPreferencesKey("workout_import_v1_" + HealthRecordIds.version(account, source))
    override suspend fun load(account: String, sourceKey: String): WorkoutImportEntry? {
        val snapshot = dataStore.data.first()
        return (snapshot[key(account, sourceKey)] ?: snapshot[legacyKey(account, sourceKey)])
            ?.let { Json.decodeFromString<WorkoutImportEntry>(it) }
    }

    private suspend fun accountEntries(account: String, includeFiles: Boolean = false): List<WorkoutImportEntry> =
        dataStore.data.first().asMap().entries
        .filter { it.key.name.startsWith("workout_import_v1_") }.mapNotNull {
            val entry = Json.decodeFromString<WorkoutImportEntry>(it.value.toString())
            val belongs = it.key.name.startsWith(accountPrefix(account)) ||
                it.key == legacyKey(account, entry.target.sourceKey)
            entry.takeIf { belongs && (includeFiles || !it.target.sourceKey.startsWith("FILES:")) }
        }

    override suspend fun hasUnconfirmedFileWrite(account: String, target: MiWorkoutImportTarget,
        exceptSourceKey: String): Boolean = accountEntries(account, includeFiles = true).any {
        it.target.sourceKey.startsWith("FILES:") && it.target.sourceKey != exceptSourceKey &&
            it.target.identity == target.identity && !it.verified &&
            (it.promotionAttempted || WorkoutFileStage.ATTEMPTED in it.fileStates.values)
    }

    suspend fun verifiedTargets(account: String): List<MiWorkoutImportTarget> = accountEntries(account)
        .filter { it.verified }.map { it.target }.distinct()

    /** A partially migrated copy must not echo the already owned source into Health Connect. */
    suspend fun targetsForLoopPrevention(account: String): List<MiWorkoutImportTarget> = accountEntries(account).flatMap { entry ->
        when {
            entry.verified -> listOf(entry.target)
            entry.migrationFrom != null -> listOfNotNull(entry.migrationFrom, entry.target.takeIf { entry.migrationCopyAttempted })
            else -> emptyList()
        }
    }.distinct()

    override suspend fun reserve(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry?): Boolean {
        require(!target.sourceKey.startsWith("FILES:"))
        var reserved = false
        dataStore.edit { prefs ->
            val k = key(account, target.sourceKey)
            val current = (prefs[k] ?: prefs[legacyKey(account, target.sourceKey)])
                ?.let { Json.decodeFromString<WorkoutImportEntry>(it) }
            if (current == previous) {
                prefs[k] = Json.encodeToString(WorkoutImportEntry(target, lastVerifiedValue = previous?.lastVerifiedValue))
                prefs.remove(legacyKey(account, target.sourceKey))
                reserved = true
            }
        }
        return reserved
    }

    override suspend fun verified(account: String, target: MiWorkoutImportTarget) {
        dataStore.edit { prefs ->
            val k = key(account, target.sourceKey)
            val current = (prefs[k] ?: prefs[legacyKey(account, target.sourceKey)])
                ?.let { Json.decodeFromString<WorkoutImportEntry>(it) }
            check(current?.target == target)
            if (current.fileProtocolVersion == 2) {
                check(current.promotionAttempted && current.fileStates.values.all { it == WorkoutFileStage.VERIFIED })
            }
            prefs[k] = Json.encodeToString(current.copy(verified = true))
            prefs.remove(legacyKey(account, target.sourceKey))
        }
    }

    override suspend fun rememberSummary(account: String, target: MiWorkoutImportTarget, value: JsonObject) {
        require(!target.sourceKey.startsWith("FILES:") && target.matches(parseMiWorkoutExperimentRow(target.row(value))))
        dataStore.edit { prefs ->
            val current = (prefs[key(account, target.sourceKey)] ?: prefs[legacyKey(account, target.sourceKey)])
                ?.let { Json.decodeFromString<WorkoutImportEntry>(it) }
            if (current?.verified == true && current.target == target) {
                prefs[key(account, target.sourceKey)] = Json.encodeToString(current.copy(lastVerifiedValue = value))
                prefs.remove(legacyKey(account, target.sourceKey))
            }
        }
    }

    override suspend fun reserveFiles(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry?,
        states: Map<String, WorkoutFileStage>): Boolean {
        require(target.sourceKey.startsWith("FILES:") && states.isNotEmpty())
        require(states.values.all { it == WorkoutFileStage.NEW || it == WorkoutFileStage.VERIFIED })
        require(previous == null || previous.verified || previous.fileProtocolVersion == 0 && previous.target == target)
        if (previous != null && !previous.verified) require(states.values.all { it == WorkoutFileStage.VERIFIED })
        return replaceFiles(account, previous, WorkoutImportEntry(target, fileProtocolVersion = 2, fileStates = states))
    }

    override suspend fun updateFiles(account: String, expected: WorkoutImportEntry, updated: WorkoutImportEntry): Boolean {
        require(expected.fileProtocolVersion == 2 && updated.fileProtocolVersion == 2)
        require(expected.target == updated.target && !expected.verified && !updated.verified)
        require(expected.fileStates.keys == updated.fileStates.keys)
        require(expected.fileStates.all { (key, state) -> updated.fileStates.getValue(key).ordinal >= state.ordinal })
        require(!expected.promotionAttempted || updated.promotionAttempted)
        require(updated.fileRecoveryAttempted.containsAll(expected.fileRecoveryAttempted))
        return replaceFiles(account, expected, updated)
    }

    private suspend fun replaceFiles(account: String, previous: WorkoutImportEntry?, updated: WorkoutImportEntry): Boolean {
        var changed = false
        dataStore.edit { prefs ->
            val target = updated.target
            val current = (prefs[key(account, target.sourceKey)] ?: prefs[legacyKey(account, target.sourceKey)])
                ?.let { Json.decodeFromString<WorkoutImportEntry>(it) }
            if (current == previous) {
                prefs[key(account, target.sourceKey)] = Json.encodeToString(updated)
                prefs.remove(legacyKey(account, target.sourceKey))
                changed = true
            }
        }
        return changed
    }

    override suspend fun reserveMigration(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry): Boolean {
        require(previous.verified && previous.target.sid == "default" && target.sid == "xiaomiwear_app_manually")
        require(previous.target.copy(sid = target.sid).identity == target.identity)
        return replaceFiles(account, previous, WorkoutImportEntry(target, migrationFrom = previous.target))
    }

    override suspend fun updateMigration(account: String, expected: WorkoutImportEntry, updated: WorkoutImportEntry): Boolean {
        require(expected.migrationFrom != null && expected.migrationFrom == updated.migrationFrom && expected.target == updated.target)
        require(!expected.migrationCopyAttempted || updated.migrationCopyAttempted)
        require(!expected.migrationDeleteAttempted || updated.migrationDeleteAttempted)
        require(updated.migrationDeleteProtocol >= expected.migrationDeleteProtocol)
        return replaceFiles(account, expected, updated)
    }
}
