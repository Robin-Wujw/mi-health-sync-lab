package com.mifitness.miclient

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

actual fun createPlatformHttpClient(allowAutomaticReplay: Boolean): HttpClient = HttpClient(Darwin) {
    // Disables Ktor-level redirects. This is not a verified guarantee against
    // NSURLSession's own transport recovery; iOS needs separate live validation.
    if (!allowAutomaticReplay) followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 30_000
    }
    engine {
        configureRequest {
            setAllowsCellularAccess(true)
            setTimeoutInterval(30.0)
        }
    }
}
