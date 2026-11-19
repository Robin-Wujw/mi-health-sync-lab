package com.bettermifitness.sync.health

/**
 * Reverse body-measurement sync cannot read HC with the current availability,
 * permissions, or foreground state. This must not be interpreted as "no data".
 * The coordinator can still attempt the independently authorized forward sync.
 */
class HealthWeightReadUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
