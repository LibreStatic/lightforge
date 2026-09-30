package com.librestatic.lightforge.feature.pdfstudio

import kotlin.math.floor

/**
 * Pure pane-width math for [PdfHingeSplitEditorBody] (Phase F review fix, DEVICE FAIL): kept
 * dependency-free (dp values as plain [Float], no [androidx.compose.ui.unit.Dp]) so it has plain
 * JVM tests, mirroring [PdfRulerMath]/[PdfSnapGuides].
 *
 * The device pass found the canvas still overlapping the hinge by a fraction of a pixel (canvas
 * right edge 530px vs hinge left 529.7px at the 840dp/16dp@0.5 fold case): using the hinge's raw
 * bounds directly leaves zero margin for dp->px rounding anywhere in the layout/measure pipeline
 * to round a pane's edge a hair past the hinge. [compute] both floors every width (never rounds
 * up) and subtracts an explicit [HingeGapDp] from the hinge-facing edge of each pane, so no
 * amount of downstream rounding can ever push a pane back into the hinge band.
 */
internal object PdfHingeSplitGeometry {
    /** Explicit clearance kept between each pane and the hinge itself, beyond whatever margin
     * floor()-ing alone provides. */
    const val HingeGapDp = 8f

    data class Panes(
        val leftWidthDp: Float,
        val rightWidthDp: Float,
        /** The canvas (with rulers) goes alone in whichever pane is larger; ties favor the left
         * pane (arbitrary but deterministic). */
        val canvasOnLeft: Boolean,
    )

    /**
     * @param totalWidthDp the full window width.
     * @param hingeLeftDp the hinge band's left edge (`GalleryFoldInfo.left`).
     * @param hingeRightDp the hinge band's right edge (`GalleryFoldInfo.right`), `>= hingeLeftDp`.
     */
    fun compute(totalWidthDp: Float, hingeLeftDp: Float, hingeRightDp: Float): Panes {
        require(totalWidthDp > 0f)
        require(hingeRightDp >= hingeLeftDp)
        val left = floor((hingeLeftDp - HingeGapDp).coerceAtLeast(0f))
        val right = floor((totalWidthDp - hingeRightDp - HingeGapDp).coerceAtLeast(0f))
        return Panes(left, right, canvasOnLeft = left >= right)
    }
}
