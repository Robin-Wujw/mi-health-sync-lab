package com.bettermifitness.sync.health

import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridges MainActivity's Health Connect permission launcher into [HealthWriter],
 * which lives outside the Activity lifecycle.
 */
object HealthConnectPermissionBridge {
    private val visibleActivities = AtomicInteger()
    internal val hasVisibleActivity: Boolean get() = visibleActivities.get() > 0

    fun activityStarted() { visibleActivities.incrementAndGet() }
    fun activityStopped() { visibleActivities.updateAndGet { (it - 1).coerceAtLeast(0) } }

    var requestRoute: (suspend (String) -> androidx.health.connect.client.records.ExerciseRoute?)? = null
    /**
     * Launches the system Health Connect permission UI and returns the granted set.
     * Set from [com.bettermifitness.sync.MainActivity] in onCreate.
     */
    var requestPermissions: (suspend (Set<String>) -> Set<String>)? = null

    /** Opens Health Connect settings or Play Store install page. */
    var openHealthConnect: (() -> Unit)? = null
}
