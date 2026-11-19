package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class SleepFileEntry(val content: String, val body: String, val referenceTime: Long,
    val generation: Int, val attempted: Boolean = false, val verified: Boolean = false)

interface SleepFileJournal {
    suspend fun load(account: String, identity: String): SleepFileEntry?
    suspend fun change(account: String, identity: String, expected: SleepFileEntry?, updated: SleepFileEntry): Boolean
}

class SleepFileLedger(private val store: DataStore<Preferences>) : SleepFileJournal {
    private fun key(account: String, identity: String) = stringPreferencesKey(
        "sleep_files_v1_" + HealthRecordIds.version(account, identity))

    override suspend fun load(account: String, identity: String): SleepFileEntry? = store.data.first()[key(account, identity)]
        ?.let { Json.decodeFromString<SleepFileEntry>(it) }

    override suspend fun change(account: String, identity: String, expected: SleepFileEntry?, updated: SleepFileEntry): Boolean {
        require(account.isNotBlank() && identity.isNotBlank() && updated.generation in 1..10_000)
        if (expected != null) {
            if (updated.generation == expected.generation) {
                require(updated.content == expected.content && updated.body == expected.body &&
                    updated.referenceTime == expected.referenceTime)
                require(!expected.attempted || updated.attempted)
                require(!expected.verified || updated.verified)
            } else require(updated.generation == expected.generation + 1 && (!expected.attempted || expected.verified))
        }
        var changed = false
        store.edit { preferences ->
            val target = key(account, identity)
            val current = preferences[target]?.let { Json.decodeFromString<SleepFileEntry>(it) }
            if (current == expected) {
                preferences[target] = Json.encodeToString(updated)
                changed = true
            }
        }
        return changed
    }
}
