package com.ugallery.feature.pdfstudio

import com.ugallery.core.designsystem.GalleryFoldInfo
import com.ugallery.core.designsystem.GalleryFoldOrientation

/**
 * How [PdfEditorBody] arranges the pages rail, canvas and inspector for the current window shape
 * (Phase F item 1).
 */
internal enum class PdfStudioLayoutMode {
    /** Narrow window: bottom sheets/tool bar, canvas takes the rest. */
    Compact,

    /** Wide window, no separating fold: pages rail + canvas + tabbed inspector side by side. */
    ExpandedThreePane,

    /** A vertical separating fold/hinge splits the window and both sides are wide enough for
     * their own pane: pages rail + canvas on one side, inspector on the other, nothing under the
     * hinge. */
    HingeSplit,

    /** A horizontal separating fold (device half-opened, laid flat — "tabletop"): canvas in the
     * top half, tools (page strip + tool bar + panel content) in the bottom half, nothing on the
     * crease. */
    Tabletop,
}

/** A single policy for actual parent constraints, including embedded panes, a device fold/hinge,
 * and large text. */
internal data class PdfStudioLayoutPolicy(
    val expanded: Boolean,
    val compactChrome: Boolean,
    val mode: PdfStudioLayoutMode = PdfStudioLayoutMode.Compact,
    val foldInfo: GalleryFoldInfo? = null,
) {
    companion object {
        /** Minimum usable width for a single pane (pages+canvas, or the inspector) on either side
         * of a hinge; below this a hinge-split pane would be too cramped to use and the layout
         * falls back to [PdfStudioLayoutMode.Compact] sheets (item 1d). */
        private const val MinHingePaneDp = 280f

        fun forSize(
            widthDp: Float,
            heightDp: Float,
            fontScale: Float,
            foldInfo: GalleryFoldInfo? = null,
        ): PdfStudioLayoutPolicy {
            val scale = fontScale.coerceIn(1f, 2f)
            val expanded = widthDp >= 840f * scale
            val compactChrome = heightDp < 600f || scale >= 1.5f || widthDp < 360f
            val mode =
                when {
                    foldInfo != null &&
                        foldInfo.isSeparating &&
                        foldInfo.orientation == GalleryFoldOrientation.Horizontal ->
                        PdfStudioLayoutMode.Tabletop

                    foldInfo != null && foldInfo.enablesSideBySide && expanded -> {
                        val leftWidth = foldInfo.left.value
                        val rightWidth = widthDp - foldInfo.right.value
                        val minPane = MinHingePaneDp * scale
                        if (leftWidth >= minPane && rightWidth >= minPane) {
                            PdfStudioLayoutMode.HingeSplit
                        } else {
                            PdfStudioLayoutMode.Compact
                        }
                    }

                    expanded -> PdfStudioLayoutMode.ExpandedThreePane
                    else -> PdfStudioLayoutMode.Compact
                }
            return PdfStudioLayoutPolicy(expanded, compactChrome, mode, foldInfo)
        }
    }
}
