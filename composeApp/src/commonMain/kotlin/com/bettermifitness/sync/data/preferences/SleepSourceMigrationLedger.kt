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

/** Private app storage. The original summary is retained for recovery, never included in logs. */
@Serializable
data class SleepSourceMigrationEntry(val originalValue: String, val zoneOffsetSeconds: Int,
    val attempted: Boolean = false, val verified: Boolean = false)

interface SleepSourceMigrationJournal {
    suspend fun load(account: String, identity: String): SleepSourceMigrationEntry?
    suspend fun change(account: String, identity: String, expected: SleepSourceMigrationEntry?, updated: SleepSourceMigrationEntry): Boolean
}

class SleepSourceMigrationLedger(private val store: DataStore<Preferences>) : SleepSourceMigrationJournal {
    private fun key(account: String, identity: String) = stringPreferencesKey(
        "sleep_source_migration_v1_" + HealthRecordIds.version(account, identity))
    override suspend fun load(account: String, identity: String): SleepSourceMigrationEntry? =
        store.data.first()[key(account, identity)]?.let { Json.decodeFromString<SleepSourceMigrationEntry>(it) }
    override suspend fun change(account: String, identity: String, expected: SleepSourceMigrationEntry?,
        updated: SleepSourceMigrationEntry): Boolean {
        require(account.isNotBlank() && identity.isNotBlank())
        if (expected != null) require(updated.originalValue == expected.originalValue &&
            updated.zoneOffsetSeconds == expected.zoneOffsetSeconds && (!expected.attempted || updated.attempted) &&
            (!expected.verified || updated.verified))
        var changed = false
        store.edit { prefs ->
            val target = key(account, identity)
            val current = prefs[target]?.let { Json.decodeFromString<SleepSourceMigrationEntry>(it) }
            if (current == expected) { prefs[target] = Json.encodeToString(updated); changed = true }
        }
        return changed
    }
}
