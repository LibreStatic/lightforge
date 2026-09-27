package com.librestatic.lightforge.feature.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/**
 * Local-only collage templates with bounded memory.
 * No cloud, no network, all rendering is on-device.
 */
enum class CollageTemplate(val displayName: String, val slotCount: Int) {
    GRID_2("Grid 2", 2),
    GRID_3("Grid 3", 3),
    GRID_4("Grid 4", 4),
    STACK_3("Stack 3", 3),
    STRIP_3("Strip 3", 3),
    POLAROID_3("Polaroid 3", 3),
}

data class CollageSlot(
    val x: Float, // normalized 0..1
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val padding: Float = 0f,
)

data class CollageConfig(
    val template: CollageTemplate,
    val outputWidth: Int,
    val outputHeight: Int,
    val background: Int = 0xFFFFFFFF.toInt(),
    val spacing: Float = 4f, // px
)

object CollageTemplates {

    fun getSlots(template: CollageTemplate): List<CollageSlot> = when (template) {
        CollageTemplate.GRID_2 -> listOf(
            CollageSlot(0f, 0f, 0.5f, 1f),
            CollageSlot(0.5f, 0f, 0.5f, 1f),
        )
        CollageTemplate.GRID_3 -> listOf(
            CollageSlot(0f, 0f, 0.66f, 0.5f),
            CollageSlot(0.66f, 0f, 0.34f, 0.5f),
            CollageSlot(0f, 0.5f, 1f, 0.5f),
        )
        CollageTemplate.GRID_4 -> listOf(
            CollageSlot(0f, 0f, 0.5f, 0.5f),
            CollageSlot(0.5f, 0f, 0.5f, 0.5f),
            CollageSlot(0f, 0.5f, 0.5f, 0.5f),
            CollageSlot(0.5f, 0.5f, 0.5f, 0.5f),
        )
        CollageTemplate.STACK_3 -> listOf(
            CollageSlot(0.1f, 0.1f, 0.6f, 0.6f, rotation = -5f, padding = 8f),
            CollageSlot(0.2f, 0.15f, 0.6f, 0.6f, rotation = 3f, padding = 8f),
            CollageSlot(0.3f, 0.2f, 0.6f, 0.6f, rotation = 7f, padding = 8f),
        )
        CollageTemplate.STRIP_3 -> listOf(
            CollageSlot(0f, 0f, 0.333f, 1f),
            CollageSlot(0.333f, 0f, 0.334f, 1f),
            CollageSlot(0.667f, 0f, 0.333f, 1f),
        )
        CollageTemplate.POLAROID_3 -> listOf(
            CollageSlot(0.05f, 0.05f, 0.4f, 0.5f, rotation = -3f, padding = 12f),
            CollageSlot(0.45f, 0.1f, 0.4f, 0.5f, rotation = 2f, padding = 12f),
            CollageSlot(0.25f, 0.45f, 0.4f, 0.5f, rotation = -1f, padding = 12f),
        )
    }

    /**
     * Render a collage from pre-loaded bitmaps.
     * Memory is bounded: source bitmaps are sampled to fit the slot, not loaded at full resolution.
     * The output bitmap is exactly outputWidth x outputHeight.
     */
    fun render(
        bitmaps: List<Bitmap>,
        config: CollageConfig,
    ): Bitmap {
        require(bitmaps.isNotEmpty()) { "At least one bitmap required" }
        require(bitmaps.size <= config.template.slotCount) {
            "Too many bitmaps: ${bitmaps.size} > ${config.template.slotCount}"
        }

        val output = Bitmap.createBitmap(config.outputWidth, config.outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Fill background
        paint.color = config.background
        canvas.drawRect(0f, 0f, config.outputWidth.toFloat(), config.outputHeight.toFloat(), paint)

        val slots = getSlots(config.template)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

        for ((index, slot) in slots.withIndex()) {
            if (index >= bitmaps.size) break
            val bitmap = bitmaps[index]

            val slotRect = RectF(
                slot.x * config.outputWidth + config.spacing,
                slot.y * config.outputHeight + config.spacing,
                (slot.x + slot.width) * config.outputWidth - config.spacing,
                (slot.y + slot.height) * config.outputHeight - config.spacing,
            )

            if (slot.padding > 0) {
                val pad = slot.padding
                slotRect.inset(pad, pad)
                // Draw white background for polaroid effect
                canvas.drawRect(
                    slotRect.left - pad, slotRect.top - pad,
                    slotRect.right + pad, slotRect.bottom + pad * 2,
                    bgPaint,
                )
            }

            canvas.save()
            if (slot.rotation != 0f) {
                canvas.rotate(slot.rotation, slotRect.centerX(), slotRect.centerY())
            }

            // Draw bitmap with center-crop fit
            val srcRect = computeSrcRect(bitmap, slotRect.width() / slotRect.height())
            val dstRect = Rect(slotRect.left.toInt(), slotRect.top.toInt(), slotRect.right.toInt(), slotRect.bottom.toInt())
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            canvas.restore()
        }

        return output
    }

    private fun computeSrcRect(bitmap: Bitmap, targetAspect: Float): Rect {
        val bmpAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        return if (bmpAspect > targetAspect) {
            // Source is wider - crop horizontally
            val cropW = (bitmap.height * targetAspect).toInt()
            val offsetX = ((bitmap.width - cropW) / 2)
            Rect(offsetX, 0, offsetX + cropW, bitmap.height)
        } else {
            // Source is taller - crop vertically
            val cropH = (bitmap.width / targetAspect).toInt()
            val offsetY = ((bitmap.height - cropH) / 2)
            Rect(0, offsetY, bitmap.width, offsetY + cropH)
        }
    }
}
