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
        assertTrue(JSONObject(encoded).getInt("version") == 2)
        assertEquals(p, PdfCodec.decode(encoded))
    }

    @Test
    fun unknownFutureVersionIsRejected() {
        val encoded = JSONObject(PdfCodec.encode(project()))
        encoded.put("version", 3)
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
}
