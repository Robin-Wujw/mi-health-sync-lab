package com.bettermifitness.sync.zepp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** App-private session persistence, following the existing Xiaomi credential store. No account password. */
class ZeppSessionStore(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("zepp_session_v1")
    val connected = dataStore.data.map { it[key] != null }
    suspend fun load(): ZeppSession? = dataStore.data.first()[key]?.let {
        try { Json.decodeFromString<ZeppSession>(it) }
        catch (_: Exception) { throw ZeppException(ZeppFailure.AUTH) }
    }
    suspend fun save(session: ZeppSession) { dataStore.edit { it[key] = Json.encodeToString(session) } }
    suspend fun clear() { dataStore.edit { it.remove(key) } }
}
