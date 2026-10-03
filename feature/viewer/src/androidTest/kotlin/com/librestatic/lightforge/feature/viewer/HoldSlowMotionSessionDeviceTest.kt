package com.librestatic.lightforge.feature.viewer

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.frameinterpolation.VideoFrameReader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

/**
 * Diagnostic: time to first slow-motion frame on a video pushed into the test package's files dir
 * (`adb shell run-as <pkg> sh -c 'cat > files/<name>'`) or MediaStore, `-e slowMotionVideo <name>`.
 */
class HoldSlowMotionSessionDeviceTest {
    @Test
    fun firstFrameArrives() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = InstrumentationRegistry.getArguments().getString("slowMotionVideo") ?: "cctv_like.mp4"
        val uri = findVideo(name)
        assumeNotNull(uri)
        File(context.cacheDir, "hold-slow-motion").deleteRecursively()

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        // t=0 is a keyframe; the platform retriever gives up (WOULD_BLOCK) deep inside long GOPs.
        val reference = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)!!
        retriever.release()
        VideoFrameReader(context, uri!!, maxLongEdge = 256).use { reader ->
            val keyframe = reader.frameAt(0L)!!
            val decodeStarted = SystemClock.elapsedRealtime()
            val frame = reader.frameAt(8_000L)!!
            println("LIGHTFORGE_SLOWMO reader first ${frame.width}x${frame.height} ms=${SystemClock.elapsedRealtime() - decodeStarted}")
            val sequentialStarted = SystemClock.elapsedRealtime()
            repeat(30) { reader.next()!!.bitmap.recycle() }
            println("LIGHTFORGE_SLOWMO reader next30 ms=${SystemClock.elapsedRealtime() - sequentialStarted}")
            assertEquals(reference.width.toFloat() / reference.height, keyframe.width.toFloat() / keyframe.height, 0.05f)
            val difference = meanAbsoluteDifference(reference, keyframe)
            keyframe.recycle()
            println("LIGHTFORGE_SLOWMO reader vs retriever meanAbsDiff=$difference")
            // Wrong matrix (BT.709 on untagged BT.601) measures ~14; the platform scaler smooths grain.
            assertTrue("decoded colours diverge from the platform retriever: $difference", difference < 11f)
            frame.recycle()
        }
        reference.recycle()

        val session = withContext(Dispatchers.Main) { HoldSlowMotionSession(context, uri) }
        val started = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Main) { session.start(8_000L) }

        val reached = withTimeoutOrNull(90_000L) {
            session.state.first { it is HoldSlowMotionState.Playing || it is HoldSlowMotionState.Failure }
        }
        println("LIGHTFORGE_SLOWMO firstState=$reached ms=${SystemClock.elapsedRealtime() - started}")
        var played = 0
        var stalls = 0
        withTimeoutOrNull(8_000L) {
            session.state.collect { state ->
                if (state is HoldSlowMotionState.Playing) played += 1
                if (state is HoldSlowMotionState.Buffering) stalls += 1
            }
        }
        println("LIGHTFORGE_SLOWMO 8s played=$played stalls=$stalls")
        withContext(Dispatchers.Main) { session.close() }
        assertTrue("slow motion never started: $reached", reached is HoldSlowMotionState.Playing)
    }

    private fun meanAbsoluteDifference(first: Bitmap, second: Bitmap): Float {
        val a = Bitmap.createScaledBitmap(first, 64, 36, true)
        val b = Bitmap.createScaledBitmap(second, 64, 36, true)
        var total = 0L
        for (y in 0 until 36) for (x in 0 until 64) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            total += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) +
                abs(Color.blue(p) - Color.blue(q))
        }
        return total / (64f * 36f * 3f)
    }

    private fun findVideo(name: String): Uri? {
        val pushed = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name)
        if (pushed.isFile) return Uri.fromFile(pushed)
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        resolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
        return null
    }
}
