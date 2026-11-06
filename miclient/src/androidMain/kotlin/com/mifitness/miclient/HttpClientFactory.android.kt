package com.mifitness.miclient

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

actual fun createPlatformHttpClient(allowAutomaticReplay: Boolean): HttpClient = HttpClient(OkHttp) {
    if (!allowAutomaticReplay) followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 30_000
    }
    engine {
        config {
            retryOnConnectionFailure(allowAutomaticReplay)
            if (!allowAutomaticReplay) {
                followRedirects(false)
                followSslRedirects(false)
                // retryOnConnectionFailure(false) alone does not stop every
                // HTTP follow-up (notably 503 + Retry-After: 0). A request body
                // must never be replayed behind the import ledger's back.
                addInterceptor { chain ->
                    val request = chain.request()
                    val body = request.body
                    chain.proceed(if (body == null) request else request.newBuilder()
                        .method(request.method, NonReplayableRequestBody(body))
                        .build())
                }
            }
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
            writeTimeout(30, TimeUnit.SECONDS)
        }
    }
}

private class NonReplayableRequestBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun isDuplex() = delegate.isDuplex()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
}
