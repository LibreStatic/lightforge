package com.librestatic.lightforge.core.ml

import com.librestatic.lightforge.core.database.MediaLabelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PetLabelExclusivityTest {
    @Test
    fun `strongest pet label wins while unrelated labels remain`() {
        val labels = listOf(
            label("dog", .82f),
            label("cat", .91f),
            label("beach", .75f),
        )

        assertEquals(
            listOf("cat", "beach"),
            labels.keepStrongestPetLabel().map(MediaLabelEntity::canonicalLabel),
        )
    }

    @Test
    fun `dog wins deterministic pet label tie`() {
        val labels = listOf(label("cat", .9f), label("dog", .9f))

        assertEquals(listOf("dog"), labels.keepStrongestPetLabel().map(MediaLabelEntity::canonicalLabel))
    }

    private fun label(canonical: String, confidence: Float) = MediaLabelEntity(
        volumeName = "external_primary",
        mediaStoreId = 1,
        canonicalLabel = canonical,
        rawLabel = canonical,
        confidence = confidence,
        modelVersion = ImageLabelMlEngine.ModelVersion,
    )
}
