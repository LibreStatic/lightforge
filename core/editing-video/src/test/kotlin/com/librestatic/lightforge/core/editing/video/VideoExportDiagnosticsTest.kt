package com.librestatic.lightforge.core.editing.video

import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExportDiagnosticsTest {
    @Test
    fun retainsNestedFailureDetails() {
        val root = IllegalStateException("No call to setSamplerTexId() before bind")
        val failure = IllegalArgumentException("Video frame processing failed", root)

        val diagnostic = videoExportDiagnostic(failure, "fallback")

        assertTrue(diagnostic.contains("Video frame processing failed"))
        assertTrue(diagnostic.contains("setSamplerTexId"))
    }
}
