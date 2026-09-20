package com.ugallery.feature.motionphotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

internal object MotionPhotoFixtures {
    fun create(context: Context, enabled: Boolean = true, padding: Int = 7): File {
        val clip =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("motion_fixture.mp4")
                .use { it.readBytes() }
        val xmp =
            """<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:C="http://ns.google.com/photos/1.0/camera/" xmlns:G="http://ns.google.com/photos/1.0/container/" xmlns:I="http://ns.google.com/photos/1.0/container/item/"><rdf:RDF><rdf:Description C:MotionPhoto="${if(enabled) 1 else 0}" C:MotionPhotoVersion="1" C:MotionPhotoPresentationTimestampUs="1000000"><G:Directory><rdf:Seq><rdf:li><G:Item I:Semantic="Primary" I:Mime="image/jpeg" I:Length="0" I:Padding="$padding"/></rdf:li><rdf:li><G:Item I:Semantic="MotionPhoto" I:Mime="video/mp4" I:Length="${clip.size}"/></rdf:li></rdf:Seq></G:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val bitmap =
            Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.MAGENTA)
            }
        val jpeg =
            ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                .toByteArray()
        bitmap.recycle()
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xmp.toByteArray()
        val header =
            byteArrayOf(-1, -31) +
                ByteBuffer.allocate(2).putShort((payload.size + 2).toShort()).array() +
                payload
        return File(context.cacheDir, "motion-fixture-${System.nanoTime()}.jpg").also {
            it.writeBytes(
                jpeg.copyOfRange(0, 2) +
                    header +
                    jpeg.copyOfRange(2, jpeg.size) +
                    ByteArray(padding) +
                    clip
            )
        }
    }

    /**
     * Platform video-track extent for the original fixture, independent of JPEG range extraction.
     */
    fun videoDurationUs(context: Context): Long {
        val file = File.createTempFile("motion-duration-", ".mp4", context.cacheDir)
        val extractor = android.media.MediaExtractor()
        try {
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("motion_fixture.mp4")
                .use { source -> file.outputStream().use { source.copyTo(it) } }
            extractor.setDataSource(file.absolutePath)
            val index =
                (0 until extractor.trackCount).first {
                    extractor
                        .getTrackFormat(it)
                        .getString(android.media.MediaFormat.KEY_MIME)!!
                        .startsWith("video/")
                }
            return extractor.getTrackFormat(index).getLong(android.media.MediaFormat.KEY_DURATION)
        } finally {
            extractor.release()
            file.delete()
        }
    }

    fun input(file: File) = MotionPhotoInput(Uri.fromFile(file))
}
