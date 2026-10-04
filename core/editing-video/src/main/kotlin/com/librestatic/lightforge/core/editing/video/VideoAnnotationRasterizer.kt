package com.librestatic.lightforge.core.editing.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF

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
        val path = VideoAnnotationGeometry.path(layer.shape, layer.points, width, height)
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
            target.drawPath(VideoAnnotationGeometry.arrowHead(layer.points, layer.style.strokeWidth * minOf(width, height) * 5f, width, height).also { it.transform(matrix) }, paint)
        }
    }

    private fun paint(layer: VideoAnnotationLayer, minimumDimension: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = layer.style.strokeWidth * minimumDimension
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = if (layer.shape != VideoAnnotationShape.Line && layer.shape != VideoAnnotationShape.Arrow && (layer.style.filled || (
                layer.style.appearance in setOf(VideoAnnotationAppearance.Blur, VideoAnnotationAppearance.Mosaic) &&
                    layer.shape !in setOf(VideoAnnotationShape.Freehand, VideoAnnotationShape.Line, VideoAnnotationShape.Arrow)
                ))) Paint.Style.FILL else Paint.Style.STROKE
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
}
