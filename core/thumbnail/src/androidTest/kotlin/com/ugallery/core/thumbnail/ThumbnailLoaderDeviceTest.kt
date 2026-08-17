package com.ugallery.core.thumbnail

import android.graphics.Bitmap
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ThumbnailLoaderDeviceTest {
    @Test
    fun generationIsPartOfCacheIdentity() = runBlocking {
        val decodes = AtomicInteger()
        val loader = ThumbnailLoader(
            source = ThumbnailSource { _, _ ->
                decodes.incrementAndGet()
                Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            },
            maxCacheBytes = 8_192,
            threadCount = 1,
        )
        try {
            val first = request(generation = 1)
            loader.load(first)
            loader.load(first)
            loader.load(request(generation = 2))
            assertEquals(2, decodes.get())
        } finally {
            loader.close()
        }
    }

    @Test
    fun coroutineCancellationReachesContentResolverSignal() = runBlocking {
        val decodeStarted = CompletableDeferred<Unit>()
        val signalCancelled = CompletableDeferred<Unit>()
        val loader = ThumbnailLoader(
            source = ThumbnailSource { _, signal ->
                decodeStarted.complete(Unit)
                while (!signal.isCanceled) Thread.sleep(5)
                signalCancelled.complete(Unit)
                throw OperationCanceledException()
            },
            maxCacheBytes = 8_192,
            threadCount = 1,
        )
        try {
            val job = launch { loader.load(request(generation = 1)) }
            decodeStarted.await()
            job.cancelAndJoin()
            withTimeout(2_000) { signalCancelled.await() }
            assertTrue(signalCancelled.isCompleted)
        } finally {
            loader.close()
        }
    }

    private fun request(generation: Long) = ThumbnailRequest(
        mediaKey = MediaKey("external_primary", 1),
        generationModified = generation,
        widthPx = 128,
        heightPx = 128,
    )
}
