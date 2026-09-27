package com.librestatic.lightforge.core.mediastore

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class MediaStoreActionCoordinatorDeviceTest {
    @Test
    fun platformCreatesAllPublicSystemRequestsAndCanRecreatePendingChunk() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = publishFixture()
        val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri))
        try {
            listOf(
                MediaAction.Write,
                MediaAction.Favorite(true),
                MediaAction.Trash(true),
                MediaAction.Delete,
            ).forEach { action ->
                val coordinator = MediaStoreActionCoordinator(
                    resolver,
                    MediaActionReducer.start(action, totalSelected = 1),
                )
                val first = coordinator.stageChunk(listOf(MediaActionTarget(key, MediaKind.Image)))
                val recreated = MediaStoreActionCoordinator(resolver, coordinator.snapshot.value)
                    .recreateCurrentRequest()
                assertEquals(first.requestId, recreated.requestId)
                assertNotNull(first.intentSender)
                assertNotNull(recreated.intentSender)
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun cancellationPreservesStateAndApprovedFavoriteIsVerified() = runBlocking {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = publishFixture()
        val target = MediaActionTarget(
            MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri)),
            MediaKind.Image,
        )
        try {
            val cancelledCoordinator = MediaStoreActionCoordinator(
                resolver,
                MediaActionReducer.start(MediaAction.Favorite(true), 1),
            )
            val cancelledLaunch = cancelledCoordinator.stageChunk(listOf(target))
            val cancelled = cancelledCoordinator.onSystemResult(cancelledLaunch.requestId, approved = false)
            assertEquals(listOf(target), (cancelled.phase as MediaActionPhase.Cancelled).targets)
            assertEquals(0L, cancelled.progress.accounted)

            val coordinator = MediaStoreActionCoordinator(
                resolver,
                MediaActionReducer.start(MediaAction.Favorite(true), 1),
            )
            val launch = coordinator.stageChunk(listOf(target))
            assertEquals(
                1,
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, 1) },
                    null,
                    null,
                ),
            )
            val verified = coordinator.onSystemResult(launch.requestId, approved = true)
            assertEquals(1L, verified.progress.completed)
            assertEquals(0L, verified.progress.failed)
            assertTrue(verified.phase is MediaActionPhase.Complete)

            val write = MediaStoreActionCoordinator(
                resolver,
                MediaActionReducer.start(MediaAction.Write, 1),
            )
            val writeLaunch = write.stageChunk(listOf(target))
            val authorized = write.onSystemResult(writeLaunch.requestId, approved = true)
            assertEquals(1L, authorized.progress.authorized)
            assertEquals(0L, authorized.progress.completed)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun publishFixture(): Uri {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = checkNotNull(
            resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "lightforge-m2-system-action.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LightforgeActionTest")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        resolver.openOutputStream(uri, "w")!!.use { it.write(MinimalJpeg) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private companion object {
        val MinimalJpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
    }
}
