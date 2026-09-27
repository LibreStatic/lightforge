package com.librestatic.lightforge.feature.motionphotos

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MotionPhotoSessionDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun boundedInspectDoesNotCreateSnapshotsAndRejectsStaticFlag() = runBlocking {
        val file = MotionPhotoFixtures.create(context)
        val disabled = MotionPhotoFixtures.create(context, false)
        try {
            assertTrue(MotionPhotoSession.inspect(context, MotionPhotoFixtures.input(file)))
            assertFalse(MotionPhotoSession.inspect(context, MotionPhotoFixtures.input(disabled)))
            assertTrue(
                context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("motion-photo-") }
            )
        } finally {
            file.delete()
            disabled.delete()
        }
    }

    @Test
    fun sixRealFramesAndExportsPreserveClipAndOriginalBytes() = runBlocking {
        val file = MotionPhotoFixtures.create(context)
        val hash = MotionPhotoSession.hash(file)
        val outputs = mutableListOf<Uri>()
        try {
            MotionPhotoSession.open(context, MotionPhotoFixtures.input(file)).use { session ->
                assertEquals(MotionPhotoFixtures.videoDurationUs(context), session.durationUs)
                assertTrue(session.durationUs in 2_990_000L..3_100_000L)
                assertEquals(1_000_000L, session.defaultTimeUs)
                assertEquals(hash, session.originalSha256)
                val frames =
                    session.frameTimesUs.map { time ->
                        val bitmap = session.frame(time, 320)
                        val bytes =
                            ByteArrayOutputStream()
                                .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                .toByteArray()
                        bitmap.recycle()
                        MessageDigest.getInstance("SHA-256").digest(bytes).toList()
                    }
                assertEquals(6, frames.toSet().size)
                val clipHash = MotionPhotoSession.hash(session.clip)
                val video = session.exportClip().also(outputs::add)
                val jpeg = session.exportFrame(session.frameTimesUs[4]).also(outputs::add)
                val actual =
                    context.contentResolver.openInputStream(video)!!.use {
                        MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString(
                            ""
                        ) { n ->
                            "%02x".format(n)
                        }
                    }
                assertEquals(clipHash, actual)
                context.contentResolver.openInputStream(jpeg)!!.use { source ->
                    val decoded = BitmapFactory.decodeStream(source)!!
                    assertTrue(decoded.width > 0)
                    decoded.recycle()
                }
                outputs.forEach { uri ->
                    context.contentResolver
                        .query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!
                        .use {
                            assertTrue(it.moveToFirst())
                            assertEquals(0, it.getInt(0))
                        }
                }
                assertEquals(hash, MotionPhotoSession.hash(file))
            }
            assertNoStaging()
        } finally {
            outputs.forEach { context.contentResolver.delete(it, null, null) }
            file.delete()
        }
    }

    @Test
    fun keyFrameCallbackOwnsValidTemporaryJpegOnlyDuringCall() = runBlocking {
        val file = MotionPhotoFixtures.create(context)
        var selected: File? = null
        try {
            MotionPhotoSession.open(context, MotionPhotoFixtures.input(file)).use { session ->
                assertTrue(
                    session.withFrameFile(2_000_000) { jpeg ->
                        selected = jpeg
                        val bitmap = BitmapFactory.decodeFile(jpeg.absolutePath)!!
                        assertTrue(bitmap.width > 0)
                        bitmap.recycle()
                        true
                    }
                )
                assertFalse(selected!!.exists())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun cancellingKeyFrameCallbackCleansTemporaryWork() = runBlocking {
        val file = MotionPhotoFixtures.create(context)
        val started = CompletableDeferred<Unit>()
        var temp: File? = null
        try {
            MotionPhotoSession.open(context, MotionPhotoFixtures.input(file)).use { session ->
                val job = launch {
                    session.withFrameFile(0) { jpeg ->
                        temp = jpeg
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        true
                    }
                }
                withTimeout(10_000) { started.await() }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertFalse(temp!!.exists())
            }
            assertNoStaging()
        } finally {
            file.delete()
        }
    }

    @Test
    fun mediaStoreInspectWithoutCopyThenGenerationChangeRejectsKeyframeAndBothExports() = runBlocking {
        val file = MotionPhotoFixtures.create(context)
        val resolver = context.contentResolver
        val uri =
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        "Motion-generation-${System.nanoTime()}.jpg",
                    )
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LightforgeTest")
                },
            )!!
        var removed = false
        try {
            resolver.openOutputStream(uri)!!.use { target ->
                file.inputStream().use { it.copyTo(target) }
            }
            check(
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ) == 1
            )
            fun currentGeneration(): Long =
                resolver
                    .query(
                        uri,
                        arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED),
                        null,
                        null,
                        null,
                    )!!
                    .use {
                        assertTrue(it.moveToFirst())
                        it.getLong(0)
                    }
            // Own fixture publication triggers provider metadata indexing; bind only its settled
            // generation.
            val generation =
                withTimeout(10_000) {
                    var previous = currentGeneration()
                    var stable = 0
                    while (stable < 5) {
                        kotlinx.coroutines.delay(100)
                        val next = currentGeneration()
                        stable = if (next == previous) stable + 1 else 0
                        previous = next
                    }
                    previous
                }
            val input = MotionPhotoInput(uri, generation)
            val beforeHash = MotionPhotoSession.hash(file)
            assertTrue("Granted MediaStore FD must be inspected without reopening /proc", MotionPhotoSession.inspect(context, input))
            assertNoStaging()
            val afterHash = resolver.openInputStream(uri)!!.use {
                MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
            }
            assertEquals(beforeHash, afterHash)
            MotionPhotoSession.open(context, input).use { session ->
                assertEquals(1, resolver.delete(uri, null, null))
                removed = true
                var called = false
                expectFailure {
                    session.withFrameFile(0) {
                        called = true
                        true
                    }
                }
                expectFailure { session.exportFrame(0) }
                expectFailure { session.exportClip() }
                assertFalse(called)
            }
            assertNoStaging()
        } finally {
            if (!removed) resolver.delete(uri, null, null)
            file.delete()
        }
    }

    @Test
    fun unsupportedAndCorruptInputsLeaveNoStaging() = runBlocking {
        val file = MotionPhotoFixtures.create(context, false)
        try {
            expectFailure { MotionPhotoSession.open(context, MotionPhotoFixtures.input(file)) }
            file.writeBytes(byteArrayOf(-1, -40, -1))
            expectFailure { MotionPhotoSession.open(context, MotionPhotoFixtures.input(file)) }
            assertNoStaging()
        } finally {
            file.delete()
        }
    }

    private fun assertNoStaging() {
        assertTrue(
            context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("motion-photo-") }
        )
    }

    private suspend fun expectFailure(action: suspend () -> Unit) {
        var failed = false
        try {
            action()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }
}
