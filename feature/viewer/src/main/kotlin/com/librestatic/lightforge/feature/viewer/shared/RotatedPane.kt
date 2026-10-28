package com.librestatic.lightforge.feature.viewer.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

/**
 * Lays [content] out turned clockwise by [degrees] (a multiple of 90). A quarter turn measures the
 * content with the width and height constraints swapped, so it fills this pane once turned. The
 * turn is a graphics layer, so touches reach the content already in its own, unrotated frame.
 */
@Composable
fun RotatedPane(
    degrees: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val quarterTurn = degrees % 180 != 0
        val childConstraints = if (quarterTurn) {
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            )
        } else constraints
        val placeable = measurables.single().measure(childConstraints)
        val width = if (quarterTurn) placeable.height else placeable.width
        val height = if (quarterTurn) placeable.width else placeable.height
        layout(width, height) {
            // The layer rotates around the child's center, which is kept on the pane's center.
            placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
                rotationZ = degrees.toFloat()
            }
        }
    }
}
