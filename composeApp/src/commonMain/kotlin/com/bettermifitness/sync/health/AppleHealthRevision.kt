package com.bettermifitness.sync.health

internal data class AppleHealthRevision(val version: Long, val fingerprint: String? = null) {
    init { require(version > 0) }

    fun matches(content: String): Boolean = (fingerprint ?: version.toString()) == content
}

internal fun nextAppleHealthRevision(current: AppleHealthRevision?, fingerprint: String): Long? {
    require(fingerprint.isNotBlank())
    if (current?.matches(fingerprint) == true) return null
    val previous = current?.version ?: 0L
    check(previous < Long.MAX_VALUE) { "Apple Health sync version is exhausted" }
    return previous + 1L
}
