package com.bettermifitness.sync

import com.bettermifitness.sync.di.provideAndroidContext
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses the packaged English resources; no fake strings or production fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
actual abstract class LocalizedTest actual constructor() {
    @Before
    fun prepareAndroidResources() {
        provideAndroidContext(RuntimeEnvironment.getApplication())
    }
}
