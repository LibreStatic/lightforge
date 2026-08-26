package com.ugallery.core.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class GallerySettingsTest {
    @Test fun `legacy seek bar is the default video scrubbing mode`() {
        assertEquals(VideoScrubbingMode.LegacySeekBar, GallerySettings().playback.videoScrubbingMode)
        assertEquals(20, GallerySettings().analysis.fullAnalysisMinimumBatteryPercent)
        assertEquals(4, GallerySettings.CurrentSchemaVersion)
    }
}
