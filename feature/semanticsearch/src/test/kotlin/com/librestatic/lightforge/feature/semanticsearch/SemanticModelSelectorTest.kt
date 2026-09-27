package com.librestatic.lightforge.feature.semanticsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticModelSelectorTest {
    private fun model(id: String, minimum: Int, recommended: Int) = SemanticModelDescriptor(
        id, id, "v1",
        "https://github.com/LibreStatic/lightforge-models/releases/download/models-v1/$id.ugmodel",
        1, "a".repeat(64), "signature", minimum, recommended,
    )

    @Test fun selectionUsesLargestRecommendedCompatibleModel() {
        val small = model("small", 4096, 4096)
        val large = model("large", 4096, 6144)
        assertEquals(large, SemanticModelSelector.recommended(listOf(small, large), SemanticHardwareProfile(8192, listOf("arm64-v8a"))))
        assertEquals(small, SemanticModelSelector.recommended(listOf(small, large), SemanticHardwareProfile(5000, listOf("arm64-v8a"))))
    }

    @Test fun unsupportedAbiHasNoRecommendation() {
        assertEquals(null, SemanticModelSelector.recommended(listOf(model("small", 4096, 4096)), SemanticHardwareProfile(8192, listOf("armeabi-v7a"))))
    }

    @Test fun builtInCatalogUsesImmutableSignedReleaseAssets() {
        assertEquals(setOf("tinyclip-balanced", "tinyclip-quality"), SemanticModelCatalog.models.map { it.id }.toSet())
        SemanticModelCatalog.models.forEach { model ->
            assertTrue(model.packageUrl.startsWith("https://github.com/LibreStatic/lightforge-models/releases/download/semantic-models-v1/"))
            assertEquals(64, model.packageSha256.length)
            assertTrue(model.packageBytes > 1_000_000)
            assertTrue(model.signatureBase64.length > 64)
        }
    }
}
