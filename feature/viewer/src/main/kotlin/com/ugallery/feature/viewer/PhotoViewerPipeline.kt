package com.ugallery.feature.viewer

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import com.ugallery.core.thumbnail.LargeImageTileSource
import com.ugallery.core.thumbnail.NativeImageDecoder
import com.ugallery.core.thumbnail.DeepZoomAvailability
import com.ugallery.core.thumbnail.DeepZoomUnavailableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface PhotoLoadState {
    data class Thumbnail(val bitmap: Bitmap) : PhotoLoadState
    data class Ready(
        val drawable: Drawable,
        val isAnimated: Boolean,
        val supportsDeepZoom: Boolean,
        val deepZoomUnavailableReason: DeepZoomUnavailableReason?,
    ) : PhotoLoadState
    data class Error(val reason: PhotoFailure) : PhotoLoadState
}

enum class PhotoFailure { PermissionLost, CorruptOrUnsupported }

class PhotoViewerPipeline(
    private val decoder: NativeImageDecoder,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun load(
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        cachedThumbnail: Bitmap? = null,
    ): Flow<PhotoLoadState> = flow {
        if (cachedThumbnail != null) emit(PhotoLoadState.Thumbnail(cachedThumbnail))
        val result = try {
            val drawable = withContext(ioDispatcher) {
                decoder.screenDrawable(uri, targetWidth, targetHeight)
            }
            val deepZoom = if (drawable is AnimatedImageDrawable) {
                DeepZoomAvailability.Unavailable(DeepZoomUnavailableReason.AnimatedFormat)
            } else {
                withContext(ioDispatcher) { decoder.deepZoomAvailability(uri) }
            }
            PhotoLoadState.Ready(
                drawable = drawable,
                isAnimated = drawable is AnimatedImageDrawable,
                supportsDeepZoom = deepZoom is DeepZoomAvailability.Available,
                deepZoomUnavailableReason = (deepZoom as? DeepZoomAvailability.Unavailable)?.reason,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            PhotoLoadState.Error(PhotoFailure.PermissionLost)
        } catch (_: ImageDecoder.DecodeException) {
            PhotoLoadState.Error(PhotoFailure.CorruptOrUnsupported)
        } catch (_: IOException) {
            PhotoLoadState.Error(PhotoFailure.CorruptOrUnsupported)
        } catch (_: RuntimeException) {
            PhotoLoadState.Error(PhotoFailure.CorruptOrUnsupported)
        }
        emit(result)
    }

    suspend fun openDeepZoom(uri: Uri): LargeImageTileSource? = withContext(ioDispatcher) {
        decoder.openTileSource(uri)
    }
}
