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
}
