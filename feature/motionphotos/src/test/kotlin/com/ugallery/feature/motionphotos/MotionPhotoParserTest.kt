package com.ugallery.feature.motionphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoParserTest {

    @Test
    fun parseXmp_samsungMotionPhoto_extractsOffset() {
        val xmp = """
            <x:xmpmeta>
              <rdf:RDF>
                <rdf:Description
                  xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                  GCamera:MotionPhotoVersion="1"
                  GCamera:MicroVideoOffset="1234567"
                  GCamera:MicroVideoPresentationTimestamp="100"/>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val info = MotionPhotoParser.parseXmp(xmp, fileLength = 5_000_000)
        assertTrue(info.isMotionPhoto)
        assertEquals(MotionPhotoSource.SAMSUNG, info.source)
        assertEquals(1, info.motionPhotoVersion)
        // videoStart = fileLength - microVideoOffset = 5000000 - 1234567 = 3765433
        assertEquals(3_765_433L, info.motionVideoOffset)
        assertEquals(1_234_567L, info.motionVideoLength)
    }

    @Test
    fun parseXmp_googleMotionPhoto_extractsFromDirectory() {
        val xmp = """
            <x:xmpmeta>
              <rdf:RDF>
                <rdf:Description
                  xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                  GCamera:MotionPhoto="1">
                  <GCamera:Container>
                    <rdf:Seq>
                      <rdf:li Item:Semantic="Primary" Item:Length="4000000" Item:Mime="image/jpeg"/>
                      <rdf:li Item:Semantic="MotionPhoto" Item:Length="1000000" Item:Mime="video/mp4"/>
                    </rdf:Seq>
                  </GCamera:Container>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val info = MotionPhotoParser.parseXmp(xmp, fileLength = 5_000_000)
        assertTrue(info.isMotionPhoto)
        assertEquals(MotionPhotoSource.GOOGLE, info.source)
        assertEquals(4_000_000L, info.motionVideoOffset)
        assertEquals(1_000_000L, info.motionVideoLength)
    }

    @Test
    fun parseXmp_noMotionPhoto_returnsFalse() {
        val xmp = """
            <x:xmpmeta>
              <rdf:RDF>
                <rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title>Regular photo</dc:title>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val info = MotionPhotoParser.parseXmp(xmp, fileLength = 1_000_000)
        assertFalse(info.isMotionPhoto)
    }

    @Test
    fun parseXmp_emptyXmp_returnsFalse() {
        val info = MotionPhotoParser.parseXmp("", fileLength = 1000)
        assertFalse(info.isMotionPhoto)
    }

    @Test
    fun parseXmp_samsungWithMissingOffset_returnsFalse() {
        val xmp = """
            <rdf:Description GCamera:MotionPhotoVersion="1"/>
        """.trimIndent()

        val info = MotionPhotoParser.parseXmp(xmp, fileLength = 1000)
        assertFalse(info.isMotionPhoto)
    }
}
