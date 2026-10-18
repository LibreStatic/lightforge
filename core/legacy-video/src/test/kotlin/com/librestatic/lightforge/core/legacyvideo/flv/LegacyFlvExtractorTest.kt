package com.librestatic.lightforge.core.legacyvideo.flv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.flv.FlvExtractor
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import androidx.media3.test.utils.FakeTrackOutput
import com.librestatic.lightforge.core.legacyvideo.LegacyExtractorsFactory
import com.librestatic.lightforge.core.legacyvideo.LegacyVideoMimeTypes
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyFlvExtractorTest {
    private class Parsed(val output: FakeExtractorOutput) {
        fun track(type: Int): FakeTrackOutput? {
            for (i in 0 until output.numberOfTracks) {
                val t = output.trackOutputs.valueAt(i)
                val mime = t.lastFormat?.sampleMimeType
                if (type == C.TRACK_TYPE_VIDEO && MimeTypes.isVideo(mime)) return t
                if (type == C.TRACK_TYPE_AUDIO && MimeTypes.isAudio(mime)) return t
            }
            return null
        }
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("flv/$name")).use { it.readBytes() }

    private fun parse(data: ByteArray): Parsed {
        val extractor = LegacyFlvExtractor()
        val output = FakeExtractorOutput()
        extractor.init(output)
        val input = FakeExtractorInput.Builder().setData(data).build()
        assertTrue(extractor.sniff(input))
        input.resetPeekPosition()
        val holder = PositionHolder()
        var result = Extractor.RESULT_CONTINUE
        while (result != Extractor.RESULT_END_OF_INPUT) result = extractor.read(input, holder)
        assertTrue(output.tracksEnded)
        return Parsed(output)
    }

    @Test
    fun sorensonWithAdpcm_outputsVideoTrackAndSkipsAudio() {
        val parsed = parse(resource("flv1_adpcm.flv"))
        assertEquals(1, parsed.output.numberOfTracks)
        val video = parsed.track(C.TRACK_TYPE_VIDEO)!!
        val format = video.lastFormat!!
        assertEquals(LegacyVideoMimeTypes.VIDEO_FLV1, format.sampleMimeType)
        assertEquals(160, format.width)
        assertEquals(120, format.height)
        assertTrue(video.sampleCount in 14..16)
        assertTrue(video.getSampleFlags(0) and C.BUFFER_FLAG_KEY_FRAME != 0)
        assertTrue((0 until video.sampleCount).any { video.getSampleFlags(it) and C.BUFFER_FLAG_KEY_FRAME == 0 })
        assertTrue(video.getSampleData(0).isNotEmpty())
        assertNull(parsed.track(C.TRACK_TYPE_AUDIO))
    }

    @Test
    fun sorensonStandardSizeCode_isParsed() {
        val format = parse(resource("flv1_qcif.flv")).track(C.TRACK_TYPE_VIDEO)!!.lastFormat!!
        assertEquals(176, format.width)
        assertEquals(144, format.height)
    }

    @Test
    fun sorensonWithPcm_outputsRawAudioTrack() {
        val parsed = parse(resource("flv1_pcm.flv"))
        assertEquals(2, parsed.output.numberOfTracks)
        val audio = parsed.track(C.TRACK_TYPE_AUDIO)!!
        val format = audio.lastFormat!!
        assertEquals(MimeTypes.AUDIO_RAW, format.sampleMimeType)
        assertEquals(C.ENCODING_PCM_16BIT, format.pcmEncoding)
        assertEquals(22050, format.sampleRate)
        assertEquals(1, format.channelCount)
        assertTrue(audio.sampleCount > 0)
        assertNotNull(parsed.track(C.TRACK_TYPE_VIDEO))
    }

    @Test
    fun h264Aac_isUnchanged() {
        val parsed = parse(resource("h264_aac.flv"))
        val video = parsed.track(C.TRACK_TYPE_VIDEO)!!
        assertEquals(MimeTypes.VIDEO_H264, video.lastFormat!!.sampleMimeType)
        assertEquals(160, video.lastFormat!!.width)
        assertTrue(video.sampleCount > 0)
        assertEquals(MimeTypes.AUDIO_AAC, parsed.track(C.TRACK_TYPE_AUDIO)!!.lastFormat!!.sampleMimeType)
    }

    @Test
    fun durationFromMetadata_isOutput() {
        val seekMap = parse(resource("flv1_adpcm.flv")).output.seekMap!!
        assertTrue("duration=${seekMap.durationUs}", seekMap.durationUs in 900_000..1_200_000)
    }

    @Test
    fun vp6f_usesAdjustmentByteAsInitializationData() {
        val frame = vp6Keyframe(cols = 10, rows = 8, extra = 6)
        val inter = byteArrayOf(0x80.toByte(), 1, 2, 3)
        val flv = FlvBuilder().video(4, 1, 0, byteArrayOf(0x23) + frame).video(4, 2, 40, byteArrayOf(0x23) + inter).build()
        val video = parse(flv).track(C.TRACK_TYPE_VIDEO)!!
        val format = video.lastFormat!!
        assertEquals(LegacyVideoMimeTypes.VIDEO_VP6F, format.sampleMimeType)
        assertEquals(1, format.initializationData.size)
        assertArrayEquals(byteArrayOf(0x23), format.initializationData[0])
        assertEquals(160 - 2, format.width)
        assertEquals(128 - 3, format.height)
        assertEquals(2, video.sampleCount)
        assertArrayEquals(frame, video.getSampleData(0))
        assertArrayEquals(inter, video.getSampleData(1))
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, video.getSampleFlags(0))
        assertEquals(0, video.getSampleFlags(1))
        assertEquals(40_000L, video.getSampleTimeUs(1))
    }

    @Test
    fun vp6a_keepsAlphaOffsetInSample() {
        val color = vp6Keyframe(cols = 4, rows = 3, extra = 4)
        val sample = byteArrayOf(0, 0, (color.size + 3).toByte()) + color + byteArrayOf(9, 9)
        val flv = FlvBuilder().video(5, 1, 0, byteArrayOf(0x00) + sample).build()
        val video = parse(flv).track(C.TRACK_TYPE_VIDEO)!!
        val format = video.lastFormat!!
        assertEquals(LegacyVideoMimeTypes.VIDEO_VP6A, format.sampleMimeType)
        assertArrayEquals(byteArrayOf(0x00), format.initializationData[0])
        assertEquals(64, format.width)
        assertEquals(48, format.height)
        assertArrayEquals(sample, video.getSampleData(0))
    }

    @Test
    fun interFrameBeforeFirstKeyframe_isDropped_andVideoInfoFrameSkipped() {
        val flv =
            FlvBuilder()
                .video(2, 2, 0, byteArrayOf(1, 2, 3))
                .video(2, 5, 10, byteArrayOf(1))
                .video(2, 1, 20, sorensonKeyframe())
                .build()
        val video = parse(flv).track(C.TRACK_TYPE_VIDEO)!!
        assertEquals(1, video.sampleCount)
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, video.getSampleFlags(0))
    }

    @Test
    fun sorensonSixteenBitSize_isParsed() {
        val flv = FlvBuilder().video(2, 1, 0, sorensonKeyframe(sizeCode = 1, w = 640, h = 480)).build()
        val format = parse(flv).track(C.TRACK_TYPE_VIDEO)!!.lastFormat!!
        assertEquals(640, format.width)
        assertEquals(480, format.height)
    }

    @Test
    fun unknownVideoCodec_isRejected() {
        val flv = FlvBuilder().video(3, 1, 0, byteArrayOf(1, 2, 3)).build()
        try {
            parse(flv)
            throw AssertionError("expected UnsupportedFormatException")
        } catch (e: TagPayloadReader.UnsupportedFormatException) {
            assertTrue(e.message!!.contains("3"))
        }
    }

    @Test
    fun legacyFactory_replacesMedia3FlvExtractor_keepingOrder() {
        val defaults = DefaultExtractorsFactory().createExtractors()
        val factory = LegacyExtractorsFactory.create()
        val swapped = factory.createExtractors()
        assertTrue(swapped.any { it is LegacyFlvExtractor })
        assertFalse(swapped.any { it is FlvExtractor })
        assertEquals(defaults.size, swapped.size)
        assertEquals(defaults.indexOfFirst { it is FlvExtractor }, swapped.indexOfFirst { it is LegacyFlvExtractor })
    }

    @Test
    fun legacyFactory_uriVariantAlsoReplacesFlvExtractor() {
        val delegate =
            object : ExtractorsFactory {
                override fun createExtractors(): Array<Extractor> = arrayOf(FlvExtractor())

                override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
                    arrayOf(FlvExtractor(), Mp4Extractor())
            }
        val result = LegacyExtractorsFactory(delegate).createExtractors(Uri.EMPTY, emptyMap())
        assertTrue(result[0] is LegacyFlvExtractor)
        assertTrue(result[1] is Mp4Extractor)
    }

    // --- helpers ---

    private fun sorensonKeyframe(sizeCode: Int = 6, w: Int = 0, h: Int = 0): ByteArray {
        // 17-bit start code (1), 5-bit version, 8-bit temporal ref, 3-bit size code, then dimensions.
        var bits = StringBuilder()
        fun put(v: Int, n: Int) = bits.append(Integer.toBinaryString(v or (1 shl n)).substring(1))
        put(1, 17); put(0, 5); put(0, 8); put(sizeCode, 3)
        if (sizeCode == 0) { put(w, 8); put(h, 8) } else if (sizeCode == 1) { put(w, 16); put(h, 16) }
        put(0, 8 * 4)
        while (bits.length % 8 != 0) bits.append('0')
        return ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
    }

    /** VP6 keyframe: separated_coeff=0, filter_header=0 so a 2 byte partition offset follows. */
    private fun vp6Keyframe(cols: Int, rows: Int, extra: Int): ByteArray =
        byteArrayOf(0x00, 0x00, 0, 0, rows.toByte(), cols.toByte(), rows.toByte(), cols.toByte()) +
            ByteArray(extra) { (it + 1).toByte() }

    private class FlvBuilder {
        private val out = ByteArrayOutputStream()
        private var hasVideo = false

        private val tags = ByteArrayOutputStream()

        /** [payload] excludes the 1 byte video header. */
        fun video(codec: Int, frameType: Int, timeMs: Int, payload: ByteArray): FlvBuilder {
            hasVideo = true
            tag(9, timeMs, byteArrayOf(((frameType shl 4) or codec).toByte()) + payload)
            return this
        }

        private fun tag(type: Int, timeMs: Int, data: ByteArray) {
            tags.write(type)
            tags.write(byteArrayOf((data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
            tags.write(byteArrayOf((timeMs shr 16).toByte(), (timeMs shr 8).toByte(), timeMs.toByte(), (timeMs shr 24).toByte()))
            tags.write(byteArrayOf(0, 0, 0))
            tags.write(data)
            val prev = data.size + 11
            tags.write(byteArrayOf((prev shr 24).toByte(), (prev shr 16).toByte(), (prev shr 8).toByte(), prev.toByte()))
        }

        fun build(): ByteArray {
            out.write(byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, if (hasVideo) 1 else 0, 0, 0, 0, 9))
            out.write(byteArrayOf(0, 0, 0, 0))
            out.write(tags.toByteArray())
            return out.toByteArray()
        }
    }
}
