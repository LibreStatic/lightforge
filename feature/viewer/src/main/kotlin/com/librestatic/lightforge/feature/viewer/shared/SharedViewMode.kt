package com.librestatic.lightforge.feature.viewer.shared

/** How the viewer is currently being shared with a second viewer. */
enum class SharedViewMode {
    Off,

    /** The presenter browses on the inner screen while the cover screen mirrors the media. */
    OuterDisplay,

    /** The window is split in two halves, each turned to face one of two people across a table. */
    FaceToFace,
}

/**
 * Zoom and pan of the presenter's photo. [panX] and [panY] are normalized to the size of the photo
 * fitted into its pane at scale 1 (a pan of 0.5 moves the photo by half its fitted width), so the
 * same value places the photo identically in panes of different sizes and orientations.
 */
data class SharedZoom(
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val isZoomed: Boolean get() = scale > 1.01f
}
