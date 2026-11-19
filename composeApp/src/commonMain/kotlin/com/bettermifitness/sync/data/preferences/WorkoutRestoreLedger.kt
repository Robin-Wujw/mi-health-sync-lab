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

/** One explicitly selected recovery per account, separate from normal import ownership. */
@Serializable
data class WorkoutRestoreEntry(
    val target: MiWorkoutImportTarget,
    val value: JsonObject,
    val files: Map<String, String>,
    val protocol: Int,
    val fullCloudBackup: Boolean,
    val fileAttempts: Set<String> = emptySet(),
    val summaryAttempted: Boolean = false,
    val verified: Boolean = false,
    val curveRefreshVerified: Boolean = false,
) {
    override fun toString(): String = "WorkoutRestoreEntry(redacted)"
}

interface WorkoutRestoreJournal {
    suspend fun load(account: String): WorkoutRestoreEntry?
    suspend fun replace(account: String, expected: WorkoutRestoreEntry?, updated: WorkoutRestoreEntry?): Boolean
}

class WorkoutRestoreLedger(private val store: DataStore<Preferences>) : WorkoutRestoreJournal {
    private fun key(account: String) = stringPreferencesKey("workout_restore_v1_${HealthRecordIds.version(account)}")

    override suspend fun load(account: String): WorkoutRestoreEntry? = store.data.first()[key(account)]
        ?.let { Json.decodeFromString<WorkoutRestoreEntry>(it) }

    override suspend fun replace(account: String, expected: WorkoutRestoreEntry?, updated: WorkoutRestoreEntry?): Boolean {
        require(account.isNotBlank())
        if (expected != null && !expected.verified && (expected.summaryAttempted || expected.fileAttempts.isNotEmpty())) {
            require(updated != null && updated.target == expected.target && updated.value == expected.value &&
                updated.files == expected.files && updated.protocol == expected.protocol &&
                updated.fullCloudBackup == expected.fullCloudBackup)
            require(updated.fileAttempts.containsAll(expected.fileAttempts) &&
                (!expected.summaryAttempted || updated.summaryAttempted))
        }
        var changed = false
        store.edit { prefs ->
            val current = prefs[key(account)]?.let { Json.decodeFromString<WorkoutRestoreEntry>(it) }
            if (current == expected) {
                if (updated == null) prefs.remove(key(account)) else prefs[key(account)] = Json.encodeToString(updated)
                changed = true
            }
        }
        return changed
    }
}
