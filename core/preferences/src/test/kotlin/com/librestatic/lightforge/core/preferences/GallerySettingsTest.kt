package com.librestatic.lightforge.core.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySettingsTest {
    @Test fun `legacy seek bar is the default video scrubbing mode`() {
        assertEquals(VideoScrubbingMode.LegacySeekBar, GallerySettings().playback.videoScrubbingMode)
        assertEquals(FrameInterpolationEngine.Automatic, GallerySettings().playback.frameInterpolationEngine)
        assertEquals(20, GallerySettings().analysis.fullAnalysisMinimumBatteryPercent)
        assertEquals(5, GallerySettings.CurrentSchemaVersion)
    }

    @Test fun `exact bucket overrides the deepest inherited path`() {
        val rules = mapOf(
            FolderSelectionTarget.Path("external_primary", "Pictures/") to false,
            FolderSelectionTarget.Path("external_primary", "Pictures/Family/") to true,
            FolderSelectionTarget.Bucket("external_primary", 7L) to false,
        )

        assertTrue(FolderSelectionPolicy.isSelected(true, rules, "external_primary", 6L, "Pictures/Family/Trip/"))
        assertFalse(FolderSelectionPolicy.isSelected(true, rules, "external_primary", 7L, "Pictures/Family/"))
        assertFalse(FolderSelectionPolicy.isSelected(true, rules, "external_primary", 8L, "Pictures/Other/"))
        assertTrue(FolderSelectionPolicy.isSelected(true, rules, "sd-card", 8L, "Pictures/Other/"))
    }

    @Test fun `relative paths are canonicalized`() {
        assertEquals("Pictures/Family/", FolderSelectionPolicy.normalizeRelativePath("/Pictures//Family\\"))
        assertEquals(null, FolderSelectionPolicy.normalizeRelativePath("///"))
    }

    @Test fun `legacy bucket sets migrate without changing the active mode`() {
        val token = GalleryFolderToken.encode("external_primary", 42L)
        val excludedMode = migrateLegacyFolderRules(
            FolderSelectionMode.AllExceptExcluded,
            included = setOf(token),
            excluded = setOf(token),
        )
        val includedMode = migrateLegacyFolderRules(
            FolderSelectionMode.OnlyIncluded,
            included = setOf(token),
            excluded = setOf(token),
        )

        val target = FolderSelectionTarget.Bucket("external_primary", 42L)
        assertFalse(excludedMode.getValue(target))
        assertTrue(includedMode.getValue(target))
    }
}
