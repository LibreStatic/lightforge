package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.dp

/**
 * Hand-built panel-toggle glyphs (User feedback item 1): the shared [GalleryIcons] set has no
 * "hide/show side panel" pair, and `GalleryIcons.kt` already carries someone else's uncommitted
 * work on this branch (see the module guardrails), so these live here as private-to-pdfstudio
 * `ImageVector`s instead of being added there. Both are a plain rounded-rectangle "window" frame
 * (drawn as a ring via [PathFillType.EvenOdd]) with a solid column on the side the icon names —
 * the conventional "dock/undock panel" glyph shape. Each path's own fill is [PdfPaperTokens.Ink]
 * (the one existing opaque-black token, reused rather than adding a literal here) purely so the
 * glyph has visible pixels to begin with; `Icon`'s own `tint` recolors all of them at the call
 * site regardless, so the token choice has no visible effect.
 */
internal val PdfPanelLeftIcon: ImageVector by lazy {
    ImageVector.Builder(
            name = "PdfPanelLeft",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        .apply {
            path(fill = SolidColor(PdfPaperTokens.Ink), pathFillType = PathFillType.EvenOdd) {
                moveTo(3f, 5f)
                lineTo(21f, 5f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
                moveTo(4f, 6f)
                lineTo(4f, 18f)
                lineTo(20f, 18f)
                lineTo(20f, 6f)
                close()
            }
            path(fill = SolidColor(PdfPaperTokens.Ink)) {
                moveTo(4f, 6f)
                lineTo(9.5f, 6f)
                lineTo(9.5f, 18f)
                lineTo(4f, 18f)
                close()
            }
        }
        .build()
}

internal val PdfPanelRightIcon: ImageVector by lazy {
    ImageVector.Builder(
            name = "PdfPanelRight",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        .apply {
            path(fill = SolidColor(PdfPaperTokens.Ink), pathFillType = PathFillType.EvenOdd) {
                moveTo(3f, 5f)
                lineTo(21f, 5f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
                moveTo(4f, 6f)
                lineTo(4f, 18f)
                lineTo(20f, 18f)
                lineTo(20f, 6f)
                close()
            }
            path(fill = SolidColor(PdfPaperTokens.Ink)) {
                moveTo(14.5f, 6f)
                lineTo(20f, 6f)
                lineTo(20f, 18f)
                lineTo(14.5f, 18f)
                close()
            }
        }
        .build()
}
