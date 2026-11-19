package com.mifitness.miclient

import io.ktor.client.HttpClient

/**
 * Platform HTTP client with the correct engine (OkHttp / Darwin).
 *
 * Health-cloud data clients opt out of [allowAutomaticReplay]: Ktor redirects
 * are disabled and Android also disables connection recovery and makes request
 * bodies one-shot. Authentication clients retain their existing defaults.
 * The Darwin configuration does not establish equivalent URLSession retry
 * guarantees; those require separate platform validation.
 */
expect fun createPlatformHttpClient(allowAutomaticReplay: Boolean = true): HttpClient
