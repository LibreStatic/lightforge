package com.ugallery.feature.places

import java.util.concurrent.atomic.AtomicLong
import org.maplibre.android.MapLibre
import org.maplibre.android.ModuleProviderImpl
import org.maplibre.android.http.HttpRequest
import org.maplibre.android.http.HttpResponder

/**
 * Native MapLibre HTTP bridge never reaches a socket; explicit package downloads use a separate
 * client.
 */
object OfflineMapNetworkGuard {
    val blockedRequests = AtomicLong()

    @Synchronized
    fun install() {
        MapLibre.setModuleProvider(
            object : ModuleProviderImpl() {
                override fun createHttpRequest(): HttpRequest = DeniedRequest()
            }
        )
    }

    class DeniedRequest : HttpRequest {
        override fun executeRequest(
            responder: HttpResponder,
            nativePtr: Long,
            resourceUrl: String,
            dataRange: String,
            etag: String,
            modified: String,
            offlineUsage: Boolean,
        ) {
            blockedRequests.incrementAndGet()
            responder.handleFailure(
                HttpRequest.PERMANENT_ERROR,
                "Offline map network access disabled",
            )
        }

        override fun cancelRequest() {}
    }
}
