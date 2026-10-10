package com.librestatic.lightforge.feature.details

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsFormattingTest {
    private val us = Locale.US
    private val spanish = Locale.forLanguageTag("es-ES")

    @Test fun `aperture reads doubles and rationals and drops trailing zeros`() {
        assertEquals("f/1.8", DetailsFormatting.aperture("1.8", us))
        assertEquals("f/2", DetailsFormatting.aperture("2.0", us))
        assertEquals("f/2.2", DetailsFormatting.aperture("22/10", us))
        assertEquals("f/1.68", DetailsFormatting.aperture("1.68", us))
        assertEquals("f/1,8", DetailsFormatting.aperture("1.8", spanish))
        assertNull(DetailsFormatting.aperture("", us))
        assertNull(DetailsFormatting.aperture("0", us))
        assertNull(DetailsFormatting.aperture("abc", us))
    }

    @Test fun `short exposures read as reciprocals`() {
        assertEquals("1/120 s", DetailsFormatting.exposure("0.008333", us))
        assertEquals("1/60 s", DetailsFormatting.exposure("0.0166", us))
        assertEquals("1/2000 s", DetailsFormatting.exposure("1/2000", us))
        assertEquals("1/2 s", DetailsFormatting.exposure("0.5", us))
    }

    @Test fun `long or uneven exposures read as decimals`() {
        assertEquals("0.4 s", DetailsFormatting.exposure("0.4", us))
        assertEquals("2 s", DetailsFormatting.exposure("2", us))
        assertEquals("2.5 s", DetailsFormatting.exposure("2.5", us))
        assertEquals("2,5 s", DetailsFormatting.exposure("2.5", spanish))
        assertNull(DetailsFormatting.exposure("1/0", us))
    }

    @Test fun `focal length reads rationals in millimetres`() {
        assertEquals("4.3 mm", DetailsFormatting.focalLength("4300/1000", us))
        assertEquals("26 mm", DetailsFormatting.focalLength("26", us))
        assertNull(DetailsFormatting.focalLength(null, us))
    }

    @Test fun `iso hides missing values`() {
        assertEquals("ISO 100", DetailsFormatting.iso(100))
        assertNull(DetailsFormatting.iso(0))
        assertNull(DetailsFormatting.iso(null))
    }

    @Test fun `camera name does not repeat the maker`() {
        assertEquals("Google Pixel 8", DetailsFormatting.cameraName("Google", "Pixel 8"))
        assertEquals("Apple iPhone 15", DetailsFormatting.cameraName("Apple", "Apple iPhone 15"))
        assertEquals("NIKON D750", DetailsFormatting.cameraName("NIKON CORPORATION", "NIKON D750"))
        assertEquals("OLYMPUS E-M10", DetailsFormatting.cameraName("OLYMPUS IMAGING CORP.", "E-M10"))
        assertEquals("Pixel 8", DetailsFormatting.cameraName(" ", "Pixel 8"))
        assertEquals("Canon", DetailsFormatting.cameraName("Canon", null))
        assertNull(DetailsFormatting.cameraName(null, ""))
    }

    @Test fun `format name is readable`() {
        assertEquals("JPEG", DetailsFormatting.formatName("image/jpeg", "a.jpg"))
        assertEquals("HEIC", DetailsFormatting.formatName("image/heic", null))
        assertEquals("WebP", DetailsFormatting.formatName("image/webp", null))
        assertEquals("MOV", DetailsFormatting.formatName("video/quicktime", null))
        assertEquals("JXL", DetailsFormatting.formatName("image/jxl", null))
        assertEquals("FLV", DetailsFormatting.formatName("video/x-flv", null))
        assertEquals("ORF", DetailsFormatting.formatName(null, "IMG_1.orf"))
        assertEquals("PNG", DetailsFormatting.formatName("application/octet-stream", "x.png"))
        assertNull(DetailsFormatting.formatName(null, "README"))
    }

    @Test fun `dimensions include megapixels`() {
        assertEquals("4000 × 3000 · 12 MP", DetailsFormatting.dimensions(4000, 3000, us))
        assertEquals("4032 × 3024 · 12.2 MP", DetailsFormatting.dimensions(4032, 3024, us))
        assertEquals("100 × 100", DetailsFormatting.dimensions(100, 100, us))
        assertNull(DetailsFormatting.dimensions(0, 100, us))
    }

    @Test fun `duration uses clock notation`() {
        assertEquals("0:13", DetailsFormatting.duration(13_400))
        assertEquals("1:05", DetailsFormatting.duration(65_000))
        assertEquals("1:02:03", DetailsFormatting.duration(3_723_000))
        assertNull(DetailsFormatting.duration(0))
    }

    @Test fun `coordinates stay locale neutral`() {
        assertEquals("-34.60372, -58.38159", DetailsFormatting.coordinates(-34.603722, -58.381592))
    }

    @Test fun `exif moment keeps wall clock time and offset`() {
        val moment = DetailsFormatting.exifMoment("2026:09:03 15:56:00", "-03:00")!!
        assertEquals(LocalDateTime.of(2026, 9, 3, 15, 56, 0), moment.local)
        assertEquals(ZoneOffset.ofHours(-3), moment.offset)
        assertNull(DetailsFormatting.exifMoment("2026:09:03 15:56:00", "bogus")!!.offset)
        assertNull(DetailsFormatting.exifMoment("0000:00:00 00:00:00", null))
        assertNull(DetailsFormatting.exifMoment("", null))
    }

    @Test fun `time shows the recorded offset`() {
        val moment = DetailsFormatting.exifMoment("2026:09:03 15:56:00", "-03:00")!!
        val time = DetailsFormatting.time(moment, Locale.GERMANY)
        assertEquals("15:56 · UTC−03:00", time)
        val date = DetailsFormatting.date(moment, Locale.GERMANY)
        assertTrue(date, date.contains("2026") && date.contains("September"))
    }

    @Test fun `bitrate switches between Mb and kb`() {
        assertEquals("12.4 Mb/s", DetailsFormatting.bitrate(12_400_000, Locale.US))
        assertEquals("12,4 Mb/s", DetailsFormatting.bitrate(12_400_000, Locale.GERMANY))
        assertEquals("128 kb/s", DetailsFormatting.bitrate(128_000, Locale.US))
        assertEquals("800 b/s", DetailsFormatting.bitrate(800, Locale.US))
        assertNull(DetailsFormatting.bitrate(0, Locale.US))
        assertNull(DetailsFormatting.bitrate(null, Locale.US))
    }

    @Test fun `frame and sample rates drop trailing zeros`() {
        assertEquals("29.97 fps", DetailsFormatting.frameRate(29.97f, Locale.US))
        assertEquals("30 fps", DetailsFormatting.frameRate(30f, Locale.US))
        assertNull(DetailsFormatting.frameRate(0f, Locale.US))
        assertEquals("48 kHz", DetailsFormatting.sampleRate(48_000, Locale.US))
        assertEquals("44.1 kHz", DetailsFormatting.sampleRate(44_100, Locale.US))
        assertNull(DetailsFormatting.sampleRate(null, Locale.US))
    }

    @Test fun `codec names are friendly with a subtype fallback`() {
        assertEquals("H.264 / AVC", DetailsFormatting.codecName("video/avc"))
        assertEquals("H.265 / HEVC", DetailsFormatting.codecName("video/hevc"))
        assertEquals("AV1", DetailsFormatting.codecName("video/av01"))
        assertEquals("VP9", DetailsFormatting.codecName("video/x-vnd.on2.vp9"))
        assertEquals("AAC", DetailsFormatting.codecName("audio/mp4a-latm"))
        assertEquals("OPUS-FOO", DetailsFormatting.codecName("audio/x-opus-foo"))
        assertNull(DetailsFormatting.codecName(" "))
    }

    @Test fun `channel layouts`() {
        assertEquals(DetailsFormatting.ChannelLayout.Mono, DetailsFormatting.channelLayout(1))
        assertEquals(DetailsFormatting.ChannelLayout.Stereo, DetailsFormatting.channelLayout(2))
        assertEquals(DetailsFormatting.ChannelLayout.Surround51, DetailsFormatting.channelLayout(6))
        assertEquals(DetailsFormatting.ChannelLayout.Other, DetailsFormatting.channelLayout(4))
    }

    @Test fun `exposure bias keeps its sign`() {
        assertEquals("+0.7 EV", DetailsFormatting.exposureBias(0.67, Locale.US))
        assertEquals("−1.3 EV", DetailsFormatting.exposureBias(-1.33, Locale.US))
        assertEquals("0 EV", DetailsFormatting.exposureBias(-0.01, Locale.US))
        assertNull(DetailsFormatting.exposureBias(null, Locale.US))
    }

    @Test fun `digital zoom hides the no-zoom ratio`() {
        assertEquals("1.5×", DetailsFormatting.digitalZoom(1.5, Locale.US))
        assertNull(DetailsFormatting.digitalZoom(1.0, Locale.US))
        assertNull(DetailsFormatting.digitalZoom(0.0, Locale.US))
    }

    @Test fun `print resolution and altitude`() {
        assertEquals("72 dpi", DetailsFormatting.printResolution(72.0, 72.0, 2, Locale.US))
        assertEquals("300 × 150 dpi", DetailsFormatting.printResolution(300.0, 150.0, null, Locale.US))
        assertEquals("118 dpcm", DetailsFormatting.printResolution(118.0, null, 3, Locale.US))
        assertNull(DetailsFormatting.printResolution(0.0, 72.0, 2, Locale.US))
        assertEquals("25 m", DetailsFormatting.altitude(25.0, Locale.US))
        assertEquals("−12 m", DetailsFormatting.altitude(-12.0, Locale.US))
        assertNull(DetailsFormatting.altitude(Double.NaN, Locale.US))
    }

    @Test fun `precise time pads subseconds`() {
        val moment = DetailsFormatting.exifMoment("2026:09:03 15:56:07", null)
        assertEquals("15:56:07.120", DetailsFormatting.preciseTime(moment, "12"))
        assertEquals("15:56:07.123", DetailsFormatting.preciseTime(moment, "123456"))
        assertNull(DetailsFormatting.preciseTime(moment, "x"))
        assertNull(DetailsFormatting.preciseTime(null, "123"))
    }

    @Test fun `flash value is decoded from its bit fields`() {
        assertEquals(R.string.details_flash_off, DetailsFormatting.flashLabel(0))
        assertEquals(R.string.details_flash_fired, DetailsFormatting.flashLabel(1))
        assertEquals(R.string.details_flash_fired_auto, DetailsFormatting.flashLabel(0x19))
        assertEquals(R.string.details_flash_off_auto, DetailsFormatting.flashLabel(0x18))
        assertEquals(R.string.details_flash_fired_forced, DetailsFormatting.flashLabel(0x09))
        assertEquals(R.string.details_flash_off_forced, DetailsFormatting.flashLabel(0x10))
        assertEquals(R.string.details_flash_none, DetailsFormatting.flashLabel(0x20))
        assertNull(DetailsFormatting.flashLabel(null))
    }

    @Test fun `exif enums map to labels or null`() {
        assertEquals(R.string.details_wb_auto, DetailsFormatting.whiteBalanceLabel(0))
        assertNull(DetailsFormatting.whiteBalanceLabel(7))
        assertEquals(R.string.details_metering_pattern, DetailsFormatting.meteringLabel(5))
        assertNull(DetailsFormatting.meteringLabel(255))
        assertEquals(R.string.details_program_aperture, DetailsFormatting.exposureProgramLabel(3))
        assertNull(DetailsFormatting.exposureProgramLabel(0))
        assertEquals(R.string.details_scene_night, DetailsFormatting.sceneCaptureLabel(3))
        assertEquals("JPEG", DetailsFormatting.compressionName(6))
        assertNull(DetailsFormatting.compressionName(99))
    }

    @Test fun `orientation reports rotation and mirroring`() {
        assertEquals(DetailsFormatting.OrientationInfo(90, false), DetailsFormatting.orientation(6))
        assertEquals(DetailsFormatting.OrientationInfo(0, true), DetailsFormatting.orientation(2))
        assertNull(DetailsFormatting.orientation(0))
    }

    @Test fun `language falls back to the code`() {
        assertNull(DetailsFormatting.languageName(" ", Locale.US))
        assertEquals("zzz", DetailsFormatting.languageName("zzz", Locale.US))
    }
}
