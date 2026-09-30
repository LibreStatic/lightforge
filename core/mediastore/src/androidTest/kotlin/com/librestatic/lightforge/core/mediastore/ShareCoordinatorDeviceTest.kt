package com.librestatic.lightforge.core.mediastore

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareCoordinatorDeviceTest {
    @Test
    fun receiverCanReadGrantedOriginalAndPrivateCandidateIsExcluded() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val receiverContext = instrumentation.context
        val uri = publishFixture()
        val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri))
        try {
            val plan = ShareCoordinator(targetContext.contentResolver).original(
                listOf(
                    ShareCandidate(MediaActionTarget(key, MediaKind.Image), "image/jpeg"),
                    ShareCandidate(MediaActionTarget(MediaKey("external_primary", key.mediaStoreId + 1), MediaKind.Image), "image/jpeg", isPrivate = true),
                ),
            )
            assertEquals(Intent.ACTION_SEND, plan.intent.action)
            assertEquals(1, plan.sharedCount)
            assertEquals(1, plan.excludedPrivateCount)
            assertTrue(plan.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, plan.intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

            targetContext.grantUriPermission(
                receiverContext.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            receiverContext.contentResolver.openInputStream(uri)!!.use {
                assertEquals(0xff, it.read())
            }
            targetContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } finally {
            targetContext.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun largeMultipleShareIsBoundedAndCarriesEveryGrantInClipData() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val candidates = (1L..500L).map { id ->
            ShareCandidate(
                MediaActionTarget(MediaKey("external_primary", id), MediaKind.Image),
                "image/jpeg",
            )
        }
        val plan = ShareCoordinator(resolver).original(candidates)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, plan.intent.action)
        assertEquals(500, plan.sharedCount)
        assertEquals(500, plan.intent.clipData?.itemCount)
    }

    private fun publishFixture(): android.net.Uri {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = checkNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "lightforge-m2-share.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LightforgeShareTest")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        ))
        resolver.openOutputStream(uri, "w")!!.use {
            it.write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
