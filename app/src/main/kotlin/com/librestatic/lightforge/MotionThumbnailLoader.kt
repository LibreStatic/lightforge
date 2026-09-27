package com.librestatic.lightforge

import android.content.Context
import android.util.Size
import com.librestatic.lightforge.core.data.MotionKeyFrameRepository
import com.librestatic.lightforge.core.mediastore.MediaStoreUriFactory
import com.librestatic.lightforge.core.thumbnail.NativeImageDecoder
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailPrefetchPolicy
import com.librestatic.lightforge.core.thumbnail.ThumbnailSource

/** A new loader identity invalidates visible Compose cells after a manual cover change. */
internal fun motionThumbnailLoader(
    context: Context,
    decoder: NativeImageDecoder,
    maxCacheBytes: Long,
    covers: MotionKeyFrameRepository,
): ThumbnailLoader = ThumbnailLoader(
    source = ThumbnailSource { request, signal ->
        signal.throwIfCanceled()
        val cover = runCatching { covers.displayUri(request.mediaKey, request.generationModified) }.getOrNull()
        val bitmap = cover?.let { runCatching { decoder.screenPreview(it, request.widthPx, request.heightPx) }.getOrNull() }
            ?: decoder.thumbnail(MediaStoreUriFactory.uriFor(request.mediaKey), Size(request.widthPx, request.heightPx), signal)
        if (signal.isCanceled) { bitmap.recycle(); signal.throwIfCanceled() }
        bitmap
    },
    maxCacheBytes = maxCacheBytes,
    prefetchPolicy = ThumbnailPrefetchPolicy.detect(context, maxCacheBytes),
    callbackContext = context.applicationContext,
)
