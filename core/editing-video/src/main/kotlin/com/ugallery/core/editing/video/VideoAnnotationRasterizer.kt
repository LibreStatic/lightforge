package com.ugallery.core.editing.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object VideoAnnotationRasterizer {
    fun draw(
        layers: List<VideoAnnotationLayer>,
        presentationTimeMillis: Long,
        inkBitmap: Bitmap,
        maskBitmap: Bitmap,
    ) {
        val inkCanvas = Canvas(inkBitmap).apply { drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) }
        val maskCanvas = Canvas(maskBitmap).apply { drawColor(Color.BLACK) }
        layers.asSequence()
            .filter { presentationTimeMillis in it.startMillis until it.endMillis }
            .forEach { layer -> drawLayer(layer, presentationTimeMillis, inkCanvas, maskCanvas) }
    }

    private fun drawLayer(layer: VideoAnnotationLayer, timeMillis: Long, ink: Canvas, mask: Canvas) {
        val target = if (layer.style.appearance in setOf(
                VideoAnnotationAppearance.Blur,
                VideoAnnotationAppearance.Mosaic,
            )
        ) mask else ink
        val width = target.width.toFloat()
        val height = target.height.toFloat()
        val path = buildPath(layer, width, height)
        @Suppress("DEPRECATION")
        val bounds = RectF().also { path.computeBounds(it, true) }
        val transform = layer.transformAt(timeMillis)
        val matrix = Matrix().apply {
            postScale(transform.scaleX, transform.scaleY, bounds.centerX(), bounds.centerY())
            postRotate(transform.rotationDegrees, bounds.centerX(), bounds.centerY())
            postTranslate(transform.translationX * width, transform.translationY * height)
        }
        path.transform(matrix)
        val paint = paint(layer, minOf(width, height))
        target.drawPath(path, paint)
        if (layer.shape == VideoAnnotationShape.Arrow) {
            target.drawPath(arrowHead(layer, width, height, matrix), paint)
        }
    }

    private fun paint(layer: VideoAnnotationLayer, minimumDimension: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = layer.style.strokeWidth * minimumDimension
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = if (layer.style.filled || (
                layer.style.appearance in setOf(VideoAnnotationAppearance.Blur, VideoAnnotationAppearance.Mosaic) &&
                    layer.shape !in setOf(VideoAnnotationShape.Freehand, VideoAnnotationShape.Line, VideoAnnotationShape.Arrow)
                )) Paint.Style.FILL else Paint.Style.STROKE
        color = when (layer.style.appearance) {
            VideoAnnotationAppearance.Pen -> layer.style.colorArgb
            VideoAnnotationAppearance.Highlighter -> layer.style.colorArgb
            VideoAnnotationAppearance.Blur -> Color.rgb(255, (layer.style.intensity * 255).toInt(), 0)
            VideoAnnotationAppearance.Mosaic -> Color.rgb(0, (layer.style.intensity * 255).toInt(), 255)
        }
        alpha = when (layer.style.appearance) {
            VideoAnnotationAppearance.Highlighter -> (layer.style.opacity * 0.38f * 255).toInt()
            VideoAnnotationAppearance.Pen -> (layer.style.opacity * 255).toInt()
            else -> 255
        }.coerceIn(0, 255)
    }

    private fun buildPath(layer: VideoAnnotationLayer, width: Float, height: Float): Path {
        fun NormalizedPoint.xPx() = x * width
        fun NormalizedPoint.yPx() = y * height
        val first = layer.points.first()
        val last = layer.points.last()
        val left = minOf(first.xPx(), last.xPx())
        val top = minOf(first.yPx(), last.yPx())
        val right = maxOf(first.xPx(), last.xPx())
        val bottom = maxOf(first.yPx(), last.yPx())
        val rect = RectF(left, top, right, bottom)
        return Path().apply {
            when (layer.shape) {
                VideoAnnotationShape.Freehand -> {
                    moveTo(first.xPx(), first.yPx())
                    layer.points.drop(1).forEach { lineTo(it.xPx(), it.yPx()) }
                }
                VideoAnnotationShape.Line, VideoAnnotationShape.Arrow -> {
                    moveTo(first.xPx(), first.yPx()); lineTo(last.xPx(), last.yPx())
                }
                VideoAnnotationShape.Rectangle -> addRect(rect, Path.Direction.CW)
                VideoAnnotationShape.Oval -> addOval(rect, Path.Direction.CW)
                VideoAnnotationShape.Triangle -> polygon(rect, 3, -90f)
                VideoAnnotationShape.Star -> star(rect)
                VideoAnnotationShape.SpeechBubble -> speechBubble(rect)
            }
        }
    }

    private fun Path.polygon(rect: RectF, sides: Int, startDegrees: Float) {
        repeat(sides) { index ->
            val angle = (startDegrees + index * 360f / sides) * PI.toFloat() / 180f
            val x = rect.centerX() + cos(angle) * rect.width() / 2f
            val y = rect.centerY() + sin(angle) * rect.height() / 2f
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun Path.star(rect: RectF) {
        repeat(10) { index ->
            val radius = if (index % 2 == 0) 0.5f else 0.22f
            val angle = (-90f + index * 36f) * PI.toFloat() / 180f
            val x = rect.centerX() + cos(angle) * rect.width() * radius
            val y = rect.centerY() + sin(angle) * rect.height() * radius
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

    private fun arrowHead(layer: VideoAnnotationLayer, width: Float, height: Float, matrix: Matrix): Path {
        val start = layer.points.first()
        val end = layer.points.last()
        val angle = kotlin.math.atan2((end.y - start.y) * height, (end.x - start.x) * width)
        val length = layer.style.strokeWidth * minOf(width, height) * 5f
        val endPoint = floatArrayOf(end.x * width, end.y * height)
        val points = floatArrayOf(
            endPoint[0], endPoint[1],
            endPoint[0] - cos(angle - 0.55f) * length, endPoint[1] - sin(angle - 0.55f) * length,
            endPoint[0] - cos(angle + 0.55f) * length, endPoint[1] - sin(angle + 0.55f) * length,
        )
        matrix.mapPoints(points)
        return Path().apply {
            moveTo(points[0], points[1]); lineTo(points[2], points[3])
            moveTo(points[0], points[1]); lineTo(points[4], points[5])
        }
    }
}
