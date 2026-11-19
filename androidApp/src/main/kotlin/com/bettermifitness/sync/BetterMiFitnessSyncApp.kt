package com.bettermifitness.sync

import android.app.Application
import com.bettermifitness.sync.di.initKoin
import com.bettermifitness.sync.di.provideAndroidContext
import com.bettermifitness.sync.sync.LiveTestSessionGate

class BetterMiFitnessSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        provideAndroidContext(this)
        AutoSyncPlatform.init(this)
        initKoin()
        // Instrumentation enables this before Application.onCreate. Starting the
        // foreground Activity for an inspection must not trigger unrelated syncs.
        if (!LiveTestSessionGate.isActive) {
            AutoSyncSchedule.restore()
            AndroidForegroundAutoSync.register()
        }
    }
}
