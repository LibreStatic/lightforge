package com.librestatic.lightforge.feature.motionphotos

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class MotionPhotoParserTest {
    private fun xml(
        flag: String = "1",
        items: String = item("Primary", "image/jpeg", "0") + item("MotionPhoto", "video/mp4", "32"),
        extra: String = "",
    ) =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:C="http://ns.google.com/photos/1.0/camera/" xmlns:G="http://ns.google.com/photos/1.0/container/" xmlns:I="http://ns.google.com/photos/1.0/container/item/"><rdf:RDF><rdf:Description C:MotionPhoto="$flag" C:MotionPhotoVersion="1" $extra><G:Directory><rdf:Seq>$items</rdf:Seq></G:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    private fun item(semantic: String, mime: String, length: String, extra: String = "") =
        """<rdf:li><G:Item I:Semantic="$semantic" I:Mime="$mime" I:Length="$length" $extra/></rdf:li>"""

    private fun legacy(extra: String = "") =
        """<x xmlns:C="http://ns.google.com/photos/1.0/camera/" C:MicroVideo="1" C:MicroVideoVersion="1" C:MicroVideoOffset="32" $extra/>"""

    private val mp4
        get() =
            box("ftyp", "isom0000".toByteArray()) +
                box("moov", byteArrayOf()) +
                box("mdat", byteArrayOf())

    private fun box(type: String, bytes: ByteArray) =
        ByteBuffer.allocate(8 + bytes.size)
            .putInt(8 + bytes.size)
            .put(type.toByteArray())
            .put(bytes)
            .array()

    private fun jpeg(xmp: String): ByteArray {
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xmp.toByteArray()
        return byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte()) +
            ByteBuffer.allocate(2).putShort((payload.size + 2).toShort()).array() +
            payload +
            byteArrayOf(0xff.toByte(), 0xd9.toByte())
    }

    private fun parse(bytes: ByteArray): MotionPhotoInfo {
        val file = File.createTempFile("motion-parser", ".jpg")
        return try {
            file.writeBytes(bytes)
            RandomAccessFile(file, "r").use(MotionPhotoParser::parse)
        } finally {
            file.delete()
        }
    }

    @Test
    fun modernDirectoryFindsClipWithoutBrandGuess() {
        val bytes = jpeg(xml()) + mp4
        val info = parse(bytes)
        assertTrue(info.isMotionPhoto)
        assertEquals(bytes.size - 32L, info.motionVideoOffset)
        assertEquals(32L, info.motionVideoLength)
        assertEquals(MotionPhotoSource.UNKNOWN, info.source)
    }

    @Test
    fun legacyFlagOffsetIsSupportedSeparately() {
        val bytes = jpeg(legacy()) + mp4
        assertTrue(parse(bytes).isMotionPhoto)
        assertEquals(MotionPhotoSource.UNKNOWN, parse(bytes).source)
    }

    @Test
    fun disabledAndUnknownFlagsAlwaysReject() {
        listOf("0", "-1", "2", "true").forEach {
            assertFalse(
                parse(jpeg(xml(it, extra = "C:MicroVideo=\"1\" C:MicroVideoOffset=\"32\"")) + mp4)
                    .isMotionPhoto
            )
        }
    }

    @Test
    fun gainMapAndPrimaryPaddingPreserveVideoRange() {
        val xmp =
            xml(
                items =
                    item("Primary", "image/jpeg", "0", "I:Padding=\"7\"") +
                        item("GainMap", "image/jpeg", "5") +
                        item("MotionPhoto", "video/mp4", "32")
            )
        val still = jpeg(xmp)
        val info = parse(still + ByteArray(12) + mp4)
        assertTrue(info.isMotionPhoto)
        assertEquals(still.size.toLong(), info.primaryEnd)
    }

    @Test
    fun perItemAttributesAreNotGloballyZipped() {
        val xmp =
            xml(
                items =
                    """<rdf:li><G:Item I:Mime="image/jpeg" I:Semantic="Primary"/></rdf:li>""" +
                        item("MotionPhoto", "video/mp4", "32")
            )
        assertTrue(parse(jpeg(xmp) + mp4).isMotionPhoto)
    }

    @Test
    fun videoMustBeLastAndUnique() {
        val primary = item("Primary", "image/jpeg", "0")
        val video = item("MotionPhoto", "video/mp4", "32")
        assertFalse(
            MotionPhotoParser.parseXmp(
                    xml(items = primary + video + item("GainMap", "image/jpeg", "10")),
                    1000,
                )
                .isMotionPhoto
        )
        assertFalse(
            MotionPhotoParser.parseXmp(xml(items = primary + video + video), 1000).isMotionPhoto
        )
    }

    @Test
    fun onlyFirstPrimaryMayHavePadding() {
        assertFalse(
            MotionPhotoParser.parseXmp(
                    xml(
                        items =
                            item("Primary", "image/jpeg", "0") +
                                item("MotionPhoto", "video/mp4", "32", "I:Padding=\"1\"")
                    ),
                    1000,
                )
                .isMotionPhoto
        )
        assertFalse(
            MotionPhotoParser.parseXmp(
                    xml(
                        items =
                            item("GainMap", "image/jpeg", "1") +
                                item("MotionPhoto", "video/mp4", "32")
                    ),
                    1000,
                )
                .isMotionPhoto
        )
    }

    @Test
    fun invalidRangesAndOverflowReject() {
        listOf("-1", "0", "1", "1000", Long.MAX_VALUE.toString(), "9223372036854775808").forEach {
            value ->
            assertFalse(
                MotionPhotoParser.parseXmp(
                        xml(
                            items =
                                item("Primary", "image/jpeg", "0") +
                                    item("MotionPhoto", "video/mp4", value)
                        ),
                        1000,
                    )
                    .isMotionPhoto
            )
            assertFalse(
                MotionPhotoParser.parseXmp(
                        legacy().replace("Offset=\"32\"", "Offset=\"$value\""),
                        1000,
                    )
                    .isMotionPhoto
            )
        }
    }

    @Test
    fun namespaceAliasesWorkButWrongNamespacesDoNot() {
        assertTrue(
            MotionPhotoParser.parseXmp(
                    xml().replace("C:", "Alias:").replace("xmlns:C=", "xmlns:Alias="),
                    1000,
                )
                .isMotionPhoto
        )
        assertFalse(
            MotionPhotoParser.parseXmp(xml().replace("photos/1.0/camera/", "wrong/"), 1000)
                .isMotionPhoto
        )
    }

    @Test
    fun versionTwoAndMissingMotionFlagReject() {
        assertFalse(
            MotionPhotoParser.parseXmp(xml().replace("Version=\"1\"", "Version=\"2\""), 1000)
                .isMotionPhoto
        )
        assertFalse(
            MotionPhotoParser.parseXmp(xml().replace("C:MotionPhoto=\"1\"", ""), 1000).isMotionPhoto
        )
    }

    @Test
    fun timestampsAreParsedOrUnset() {
        assertEquals(
            123L,
            MotionPhotoParser.parseXmp(
                    xml(extra = "C:MotionPhotoPresentationTimestampUs=\"123\""),
                    1000,
                )
                .presentationTimeUs,
        )
        assertNull(
            MotionPhotoParser.parseXmp(
                    xml(extra = "C:MotionPhotoPresentationTimestampUs=\"-1\""),
                    1000,
                )
                .presentationTimeUs
        )
        assertFalse(
            MotionPhotoParser.parseXmp(
                    xml(extra = "C:MotionPhotoPresentationTimestampUs=\"oops\""),
                    1000,
                )
                .isMotionPhoto
        )
    }

    @Test
    fun malformedXmlAndEntityDeclarationsReject() {
        listOf(
                "",
                "<x>",
                "<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]>" + xml(),
                "a".repeat(300_000),
            )
            .forEach { assertFalse(MotionPhotoParser.parseXmp(it, 1000).isMotionPhoto) }
    }

    @Test
    fun missingMp4AndResidualMetadataReject() {
        assertFalse(parse(jpeg(xml())).isMotionPhoto)
        assertFalse(parse(jpeg(xml()) + ByteArray(32)).isMotionPhoto)
        assertFalse(parse(jpeg(xml()) + mp4.copyOfRange(0, 20)).isMotionPhoto)
    }

    @Test
    fun primaryEndMustPointToJpegEoi() {
        val bytes = jpeg(xml()) + mp4
        bytes[bytes.size - 33] = 0
        assertFalse(parse(bytes).isMotionPhoto)
    }

    @Test
    fun corruptBoxSizesDoNotEscapeTheirRange() {
        val invalid = mp4.also { ByteBuffer.wrap(it).putInt(Int.MAX_VALUE) }
        assertFalse(parse(jpeg(xml()) + invalid).isMotionPhoto)
    }

    @Test(timeout = 2000)
    fun truncatedJpegsAndMalformedMarkersTerminate() {
        val bytes = jpeg(xml()) + mp4
        listOf(0, 1, 2, 3, 4, 5, 10, 20).forEach {
            assertFalse(parse(bytes.copyOf(it)).isMotionPhoto)
        }
        assertFalse(parse(byteArrayOf(-1, -40, -1, -31, 0, 1)).isMotionPhoto)
    }
}
