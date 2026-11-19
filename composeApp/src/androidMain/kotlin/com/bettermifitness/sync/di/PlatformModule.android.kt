package com.bettermifitness.sync.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.health.connect.client.HealthConnectClient
import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.bettermifitness.sync.data.repository.FitnessPageCache
import com.bettermifitness.sync.health.HealthWriter
import com.bettermifitness.sync.sync.UserSyncJournal
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module

private lateinit var appContext: Context

/** Must be called before [initKoin] on Android. */
fun provideAndroidContext(context: Context) {
    appContext = context.applicationContext
}

/** Application context set by [provideAndroidContext]. */
fun androidAppContext(): Context = appContext

actual fun platformModule(): Module = module {
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.createWithPath(
            produceFile = {
                appContext.filesDir
                    .resolve("mi_fitness_prefs.preferences_pb")
                    .absolutePath
                    .toPath()
            },
        )
    }
    single {
        val credentials = get<CredentialsStore>()
        HealthWriter(appContext, { HealthConnectClient.getOrCreate(appContext) }, credentials::accountGeneration)
    }
    single {
        UserSyncJournal.create(appContext.noBackupFilesDir.resolve("user_sync_v1.json").absolutePath.toPath())
    }
    single { FitnessPageCache(appContext.noBackupFilesDir.resolve("fitness_read_pages_v1").absolutePath.toPath()) }
}
