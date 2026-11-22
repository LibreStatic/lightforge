package com.librestatic.lightforge.feature.pdfstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Phase G1a: PdfCodec version 2 adds the text layer and each element's `z` paint order while
 * keeping version-1 decode unchanged. See [PdfModelsTest.textJsonRoundTrip] for the model-level
 * round trip; these tests focus on the codec's version handling itself. */
class PdfCodecTest {
    private val hash = "a".repeat(64)

    private fun project(texts: List<PdfText> = emptyList()) =
        PdfProject(
            name = "Codec",
            assets = listOf(PdfAsset(hash, "image/jpeg", 800, 400)),
            pages =
                listOf(
                    PdfPage(
                        images = listOf(PdfImage(asset = hash, width = 80.0, height = 40.0)),
                        texts = texts,
                    )
                ),
        )

    /** A hand-written version-1 payload (no `z` on the image, no `texts` array at all) — exactly
     * what every archive/project saved before Phase G1a looks like on disk. */
    private fun v1Payload(p: PdfProject): String {
        val encoded = JSONObject(PdfCodec.encode(p))
        encoded.put("version", 1)
        val pages = encoded.getJSONArray("pages")
        for (n in 0 until pages.length()) {
            val page = pages.getJSONObject(n)
            page.remove("texts")
            val images = page.getJSONArray("images")
            for (x in 0 until images.length()) images.getJSONObject(x).remove("z")
        }
        return encoded.toString()
    }

    @Test
    fun version1PayloadDecodesWithNoTextsAndDefaultZ() {
        val p = project()
        val decoded = PdfCodec.decode(v1Payload(p))
        assertEquals(p, decoded)
        assertTrue(decoded.pages.single().texts.isEmpty())
        assertEquals(0, decoded.pages.single().images.single().z)
    }

    @Test
    fun version2RoundTripsTextsAndZ() {
        val p =
            project(
                texts =
                    listOf(
                        PdfText(text = "front", z = 5),
                        PdfText(text = "back", z = -1, font = PdfFontFamily.Serif, weight = PdfFontWeight.Bold),
                    )
            )
        val encoded = PdfCodec.encode(p)
        assertTrue(JSONObject(encoded).getInt("version") == 3)
        assertEquals(p, PdfCodec.decode(encoded))
    }

    /** A hand-written version-2 payload (no `printSize`/`placementMode` keys at all) — exactly
     * what every project saved before print-layout settings existed looks like on disk. */
    private fun v2Payload(p: PdfProject): String {
        val encoded = JSONObject(PdfCodec.encode(p))
        encoded.put("version", 2)
        encoded.remove("printSize")
        encoded.remove("placementMode")
        return encoded.toString()
    }

    @Test
    fun version2PayloadDecodesWithFreeGridAndContainDefault() {
        val p = project()
        val decoded = PdfCodec.decode(v2Payload(p))
        assertEquals(p, decoded)
        assertNull(decoded.printSize)
        assertEquals(PdfFit.Contain, decoded.placementMode)
    }

    @Test
    fun version3RoundTripsPrintSizeAndPlacementMode() {
        val p =
            project().copy(printSize = PdfPrintSize.Print13x18.id, placementMode = PdfFit.Cover)
        val encoded = PdfCodec.encode(p)
        assertTrue(JSONObject(encoded).getInt("version") == 3)
        val decoded = PdfCodec.decode(encoded)
        assertEquals(p, decoded)
        assertEquals(PdfPrintSize.Print13x18.id, decoded.printSize)
        assertEquals(PdfFit.Cover, decoded.placementMode)
    }

    @Test
    fun freeGridProjectRoundTripsNullPrintSize() {
        val p = project()
        assertNull(p.printSize)
        val decoded = PdfCodec.decode(PdfCodec.encode(p))
        assertNull(decoded.printSize)
    }

    @Test
    fun unknownFutureVersionIsRejected() {
        val encoded = JSONObject(PdfCodec.encode(project()))
        encoded.put("version", 4)
        assertTrue(runCatching { PdfCodec.decode(encoded.toString()) }.isFailure)
    }

    @Test
    fun versionZeroIsAlsoRejected() {
        val encoded = JSONObject(PdfCodec.encode(project()))
        encoded.put("version", 0)
        assertTrue(runCatching { PdfCodec.decode(encoded.toString()) }.isFailure)
    }

    @Test
    fun wrongFormatStringIsRejectedRegardlessOfVersion() {
        val encoded = JSONObject(PdfCodec.encode(project()))
        encoded.put("format", "com.other.thing")
        assertTrue(runCatching { PdfCodec.decode(encoded.toString()) }.isFailure)
    }

    @Test
    fun customPrintSizeRoundTrips() {
        val p = project().copy(printSize = PdfPrintSize.custom(30.0, 20.0).id)
        val decoded = PdfCodec.decode(PdfCodec.encode(p))
        assertEquals(p, decoded)
        assertEquals("custom:30x20", decoded.printSize)
        assertEquals(PdfPrintSize.custom(30.0, 20.0), PdfPrintSize.fromId(decoded.printSize))
    }

    @Test
    fun snapGridRoundTripsAndOldPayloadsDefaultToFiveMm() {
        val p = project().copy(snap = true, snapGridXMm = 30.0, snapGridYMm = 20.0)
        val decoded = PdfCodec.decode(PdfCodec.encode(p))
        assertEquals(p, decoded)
        assertEquals(30.0, decoded.snapGridXMm, 0.0)
        assertEquals(20.0, decoded.snapGridYMm, 0.0)
        assertTrue(JSONObject(PdfCodec.encode(p)).getInt("version") == 3)

        val old = JSONObject(PdfCodec.encode(p)).apply { remove("snapGridX"); remove("snapGridY") }
        val oldDecoded = PdfCodec.decode(old.toString())
        assertEquals(5.0, oldDecoded.snapGridXMm, 0.0)
        assertEquals(5.0, oldDecoded.snapGridYMm, 0.0)

        val bad = JSONObject(PdfCodec.encode(p)).apply { put("snapGridX", -3.0); put("snapGridY", 0.0) }
        val badDecoded = PdfCodec.decode(bad.toString())
        assertEquals(5.0, badDecoded.snapGridXMm, 0.0)
        assertEquals(5.0, badDecoded.snapGridYMm, 0.0)
    }
}
