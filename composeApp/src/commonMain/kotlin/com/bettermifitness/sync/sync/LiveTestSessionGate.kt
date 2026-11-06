package com.bettermifitness.sync.sync

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-local isolation for the separately installed instrumentation APK.
 *
 * It is set before the Android Application starts. Normal UI/background syncs
 * remain disabled for the lifetime of that process, without changing saved user
 * preferences. Only the explicitly bounded repository test methods may run.
 * No production Intent, preference, or exported component can activate this gate.
 */
object LiveTestSessionGate {
    private val active = MutableStateFlow(false)

    val isActive: Boolean get() = active.value

    fun activateForInstrumentation() {
        active.value = true
    }

    /** Unit tests share a JVM; live instrumentation deliberately never resets it. */
    internal fun resetForUnitTests() {
        active.value = false
    }
}
