package com.librestatic.lightforge.core.editing.video

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Builds the outline of an annotation shape; shared by the export rasterizer and the live drag preview. */
object VideoAnnotationGeometry {
    /** Outline of [shape] for the dragged [points], scaled to a [width] x [height] pixel frame. */
    fun path(shape: VideoAnnotationShape, points: List<NormalizedPoint>, width: Float, height: Float): Path {
        fun NormalizedPoint.xPx() = x * width
        fun NormalizedPoint.yPx() = y * height
        val first = points.first()
        val last = points.last()
        val rect = RectF(
            minOf(first.xPx(), last.xPx()),
            minOf(first.yPx(), last.yPx()),
            maxOf(first.xPx(), last.xPx()),
            maxOf(first.yPx(), last.yPx()),
        )
        return Path().apply {
            when (shape) {
                VideoAnnotationShape.Freehand -> {
                    moveTo(first.xPx(), first.yPx())
                    points.drop(1).forEach { lineTo(it.xPx(), it.yPx()) }
                }
                VideoAnnotationShape.Line, VideoAnnotationShape.Arrow -> {
                    moveTo(first.xPx(), first.yPx()); lineTo(last.xPx(), last.yPx())
                }
                VideoAnnotationShape.Rectangle -> addRect(rect, Path.Direction.CW)
                VideoAnnotationShape.Oval -> addOval(rect, Path.Direction.CW)
                VideoAnnotationShape.Triangle -> triangle(rect)
                VideoAnnotationShape.Star -> star(rect)
                VideoAnnotationShape.SpeechBubble -> speechBubble(rect)
            }
        }
    }

    /** The two barbs of an arrow's head at the last point, [headLength] pixels long. */
    fun arrowHead(points: List<NormalizedPoint>, headLength: Float, width: Float, height: Float): Path {
        val start = points.first()
        val end = points.last()
        val angle = atan2((end.y - start.y) * height, (end.x - start.x) * width)
        val x = end.x * width
        val y = end.y * height
        return Path().apply {
            moveTo(x, y)
            lineTo(x - cos(angle - 0.55f) * headLength, y - sin(angle - 0.55f) * headLength)
            moveTo(x, y)
            lineTo(x - cos(angle + 0.55f) * headLength, y - sin(angle + 0.55f) * headLength)
        }
    }

    // An isosceles triangle spanning the whole dragged box (apex top-centre, base on the bottom edge).
    private fun Path.triangle(rect: RectF) {
        moveTo(rect.centerX(), rect.top)
        lineTo(rect.right, rect.bottom)
        lineTo(rect.left, rect.bottom)
        close()
    }

    // A five-pointed star whose extents are stretched to fill the dragged box.
    private fun Path.star(rect: RectF) {
        val unit = List(10) { index ->
            val radius = if (index % 2 == 0) 1f else 0.44f
            val angle = (-90f + index * 36f) * PI.toFloat() / 180f
            cos(angle) * radius to sin(angle) * radius
        }
        val minX = unit.minOf { it.first }
        val maxX = unit.maxOf { it.first }
        val minY = unit.minOf { it.second }
        val maxY = unit.maxOf { it.second }
        unit.forEachIndexed { index, (ux, uy) ->
            val x = rect.left + (ux - minX) / (maxX - minX) * rect.width()
            val y = rect.top + (uy - minY) / (maxY - minY) * rect.height()
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun Path.speechBubble(rect: RectF) {
        val bodyBottom = rect.bottom - rect.height() * 0.22f
        addRoundRect(RectF(rect.left, rect.top, rect.right, bodyBottom), rect.width() * 0.12f, rect.width() * 0.12f, Path.Direction.CW)
        moveTo(rect.left + rect.width() * 0.55f, bodyBottom)
        lineTo(rect.left + rect.width() * 0.68f, rect.bottom)
        lineTo(rect.left + rect.width() * 0.75f, bodyBottom)
        close()
    }
}
