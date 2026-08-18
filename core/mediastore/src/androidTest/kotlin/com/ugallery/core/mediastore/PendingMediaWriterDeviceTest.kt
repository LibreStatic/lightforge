package com.ugallery.core.mediastore

import android.content.ContentUris
import android.content.ContentValues
import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingMediaWriterDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    @Test
    fun verifiedCopyPublishesAndMoveNeverDeletesSourceEarly() = runBlocking {
        val source = publishSource("writer-source.jpg", ByteArray(1_048_576) { (it % 251).toByte() })
        val writer = PendingMediaWriter(resolver)
        val copySpec = spec("writer-copy.jpg")
        var copy: PublishedCopy? = null
        var move: MoveCopyReady? = null
        try {
            copy = writer.copy(source, copySpec)
            assertEquals(1_048_576L, copy.bytes)
            assertEquals(0, pendingFlag(copy.uri))
            assertEquals(64, copy.sha256.length)

            val sourceTarget = MediaActionTarget(
                MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(source)),
                MediaKind.Image,
            )
            move = writer.prepareMove(source, sourceTarget, spec("writer-move.jpg"))
            assertEquals(MediaAction.Delete, move.requiredDeleteAction)
            assertTrue("source was deleted before confirmed delete", exists(source))
            assertEquals(0, pendingFlag(move.copy.uri))
        } finally {
            copy?.let { resolver.delete(it.uri, null, null) }
            move?.let { resolver.delete(it.copy.uri, null, null) }
            resolver.delete(source, null, null)
        }
    }

    @Test
    fun lowSpaceAndCancellationNeverPublishPartialRows() = runBlocking {
        val source = publishSource("writer-cancel-source.jpg", ByteArray(1_048_576) { 7 })
        try {
            val lowSpaceName = "writer-low-space.jpg"
            val lowSpace = PendingMediaWriter(resolver, DestinationSpaceProbe { 0 })
            assertTrue(runCatching { lowSpace.copy(source, spec(lowSpaceName)) }
                .exceptionOrNull() is InsufficientDestinationSpaceException)
            assertEquals(0, rowsNamed(lowSpaceName, includePending = true))

            val cancelName = "writer-cancelled.jpg"
            val pendingCreated = CompletableDeferred<Unit>()
            val writer = PendingMediaWriter(
                resolver,
                persistPending = { if (it != null) pendingCreated.complete(Unit) },
            )
            val job = async {
                writer.copy(source, spec(cancelName)) {
                    pendingCreated.complete(Unit)
                    awaitCancellation()
                }
            }
            pendingCreated.await()
            job.cancel()
            runCatching { job.await() }
            assertEquals(0, rowsNamed(cancelName, includePending = true))
        } finally {
            resolver.delete(source, null, null)
        }
    }

    @Test
    fun renderedFilePublishesThroughPendingVerification() = runBlocking {
        val file = File(context.cacheDir, "rendered-${System.nanoTime()}.jpg").apply {
            writeBytes(ByteArray(64 * 1024) { (it % 173).toByte() })
        }
        var published: PublishedCopy? = null
        try {
            published = PendingMediaWriter(resolver).publishFile(file, spec("writer-rendered.jpg"))
            assertEquals(file.length(), published.bytes)
            assertEquals(0, pendingFlag(published.uri))
            assertTrue(exists(published.uri))
        } finally {
            published?.let { resolver.delete(it.uri, null, null) }
            file.delete()
        }
    }

    @Test
    fun processDeathRecoveryDeletesOnlyOwnedScopedPendingRows() {
        val path = "Pictures/UGalleryWriterTest/Recovery-${System.nanoTime()}/"
        val pending = checkNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "abandoned.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ))
        resolver.openOutputStream(pending, "w")!!.use { it.write(byteArrayOf(1, 2, 3)) }

        val deleted = PendingMediaWriter(resolver).recoverOwnedPending(
            ownerPackageName = context.packageName,
            relativePathPrefix = path,
            volumes = setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            olderThanEpochSeconds = Long.MAX_VALUE,
        )
        assertEquals(1, deleted)
        assertFalse(exists(pending))
    }

    private fun publishSource(name: String, bytes: ByteArray): Uri {
        val uri = checkNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UGalleryWriterTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ))
        resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun spec(name: String) = MediaWriteSpec(
        destinationVolume = MediaStore.VOLUME_EXTERNAL_PRIMARY,
        kind = MediaKind.Image,
        displayName = name,
        mimeType = "image/jpeg",
        relativePath = "Pictures/UGalleryWriterTest",
    )

    private fun pendingFlag(uri: Uri): Int = resolver.query(
        uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null,
    )!!.use { check(it.moveToFirst()); it.getInt(0) }

    private fun exists(uri: Uri): Boolean = resolver.query(
        uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null,
    )?.use { it.moveToFirst() } == true

    private fun rowsNamed(name: String, includePending: Boolean): Int = resolver.query(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        arrayOf(MediaStore.MediaColumns._ID),
        android.os.Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name))
            if (includePending) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        },
        null,
    )!!.use { it.count }
}
