package com.bettermifitness.sync.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.bettermifitness.sync.health.HealthWriter
import com.bettermifitness.sync.data.repository.FitnessPageCache
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
actual fun platformModule(): Module = module {
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.createWithPath(
            produceFile = {
                val directory = NSFileManager.defaultManager.URLForDirectory(
                    directory = NSDocumentDirectory,
                    inDomain = NSUserDomainMask,
                    appropriateForURL = null,
                    create = false,
                    error = null,
                )!!
                "${directory.path}/mi_fitness_prefs.preferences_pb".toPath()
            },
        )
    }
    single { HealthWriter() }
    single {
        val directory = NSFileManager.defaultManager.URLForDirectory(
            directory = NSCachesDirectory, inDomain = NSUserDomainMask, appropriateForURL = null, create = false, error = null,
        )?.path ?: NSTemporaryDirectory()
        FitnessPageCache("$directory/fitness_read_pages_v1".toPath())
    }
}
