package com.ugallery.feature.semanticsearch

import java.net.HttpURLConnection
import kotlinx.coroutines.CancellationException

internal class SemanticDownloadCancellation {
    private var cancelled = false
    private var connection: HttpURLConnection? = null
    @Synchronized fun checkCurrent() { if (cancelled) throw CancellationException("Semantic download cancelled") }
    @Synchronized fun register(value: HttpURLConnection) {
        if (cancelled) { value.disconnect(); throw CancellationException("Semantic download cancelled") }
        connection = value
    }
    @Synchronized fun unregister(value: HttpURLConnection) { if (connection === value) connection = null }
    fun cancel() {
        val active = synchronized(this) { cancelled = true; connection.also { connection = null } }
        active?.disconnect()
    }
}
