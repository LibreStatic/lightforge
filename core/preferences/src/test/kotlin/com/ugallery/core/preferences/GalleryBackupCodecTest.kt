package com.ugallery.core.preferences

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryBackupCodecTest {
    @Test fun `round trips settings and favorite fingerprints`() {
        val favorite = FavoriteBackupRecord(
            "external_primary", 42L, "image", "photo.jpg", "image/jpeg",
            1234L, 50_000L, 60L, "DCIM/Camera/",
        )
        val decoded = GalleryBackupCodec.decode(
            GalleryBackupCodec.encode(JSONObject().put("schemaVersion", 1), listOf(favorite), 7L),
        )

        assertEquals(1, decoded.settings.getInt("schemaVersion"))
        assertEquals(listOf(favorite), decoded.favorites)
    }

    @Test fun `accepts legacy settings-only export`() {
        val legacy = JSONObject().put("schemaVersion", 1).put("playback", JSONObject())
        val decoded = GalleryBackupCodec.decode(legacy)
        assertTrue(decoded.favorites.isEmpty())
        assertEquals(legacy, decoded.settings)
    }

    @Test fun `ignores malformed favorite records`() {
        val root = GalleryBackupCodec.encode(JSONObject(), emptyList())
        root.getJSONArray("favorites").put(JSONObject().put("mediaKind", "document"))
        assertTrue(GalleryBackupCodec.decode(root).favorites.isEmpty())
    }
}
