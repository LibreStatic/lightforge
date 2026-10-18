package com.librestatic.lightforge.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFormatsTest {
    @Test fun `formats beyond the common eight are raw`() {
        listOf("IMG_1.PEF", "a.srw", "b.nrw", "c.x3f", "d.3fr", "e.iiq", "f.rwl", "g.erf", "h.kdc", "i.dcr", "j.mrw")
            .forEach { assertTrue(it, RawFormats.isRaw(null, it)) }
    }

    @Test fun `raw mime types are recognised without an extension`() {
        assertTrue(RawFormats.isRaw("image/x-pentax-pef", "noext"))
        assertTrue(RawFormats.isRaw("image/x-something-raw", null))
    }

    @Test fun `everyday formats are not raw`() {
        listOf("a.jpg", "b.heic", "c.png", "d.dngx", "dng", "e.tif").forEach {
            assertFalse(it, RawFormats.isRaw("image/jpeg", it))
        }
    }

    @Test fun `extensions are safe to inline in SQL`() {
        RawFormats.extensions.forEach { assertTrue(it, it.matches(Regex("[a-z0-9]+"))) }
        RawFormats.mimeTypes.forEach { assertTrue(it, it.matches(Regex("[a-z0-9/.+-]+"))) }
    }
}
