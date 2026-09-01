package com.ugallery.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderHierarchyTest {
    @Test fun `parents aggregate direct media and nested folders per volume`() {
        val roots = buildFolderHierarchy(
            listOf(
                option(1, "Pictures/", 2),
                option(2, "Pictures/Family/", 3),
                option(3, "Pictures/Family/Trips/", 5),
                option(4, "DCIM/Camera/", 7),
                option(5, null, 11, displayName = "Legacy"),
                option(6, "Pictures/", 13, volume = "sd-card"),
            ),
        )

        val pictures = roots.single { it.volumeName == "external_primary" && it.name == "Pictures" }
        assertEquals(10L, pictures.itemCount)
        assertEquals(2L, pictures.directOption?.itemCount)
        assertEquals(listOf("Family"), pictures.children.map { it.name })
        assertEquals(8L, pictures.children.single().itemCount)

        val legacy = roots.single { it.name == "Legacy" }
        assertNull(legacy.relativePath)
        assertEquals(11L, legacy.itemCount)
        assertEquals(13L, roots.single { it.volumeName == "sd-card" }.itemCount)
    }

    private fun option(
        bucketId: Long,
        path: String?,
        count: Long,
        displayName: String = path?.trimEnd('/')?.substringAfterLast('/') ?: bucketId.toString(),
        volume: String = "external_primary",
    ) = GalleryFolderOption(volume, bucketId, path, displayName, count)
}
