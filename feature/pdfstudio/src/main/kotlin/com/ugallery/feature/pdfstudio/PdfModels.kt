package com.ugallery.feature.pdfstudio

import java.util.UUID
import kotlin.math.min

internal fun newId(): String = UUID.randomUUID().toString()

enum class PdfFit {
    Contain,
    Cover,
}

enum class PdfUnit(val millimeters: Double) {
    Millimeter(1.0),
    Centimeter(10.0),
    Inch(25.4),
    Pixel(0.0);

    fun factor(dpi: Int): Double = if (this == Pixel) 25.4 / dpi else millimeters
}

data class PdfAsset(
    val hash: String,
    val mime: String,
    val width: Int = 0,
    val height: Int = 0,
    val orientation: Int = 1,
)

data class PdfImage(
    val id: String = newId(),
    val asset: String,
    val x: Double = 10.0,
    val y: Double = 10.0,
    val width: Double,
    val height: Double,
    val fit: PdfFit = PdfFit.Contain,
    val focusX: Double = .5,
    val focusY: Double = .5,
    val locked: Boolean = true,
    val rotation: Int = 0,
)

data class PdfPage(
    val id: String = newId(),
    val width: Double = 210.0,
    val height: Double = 297.0,
    val margin: Double = 10.0,
    val images: List<PdfImage> = emptyList(),
    val source: String? = null,
    val sourcePage: Int = 0,
    val rotation: Int = 0,
)

data class PdfProject(
    val id: String = newId(),
    val name: String,
    val pages: List<PdfPage> = listOf(PdfPage()),
    val assets: List<PdfAsset> = emptyList(),
    val unit: PdfUnit = PdfUnit.Millimeter,
    val dpi: Int = 300,
    val columns: Int = 2,
    val gap: Double = 4.0,
    val snap: Boolean = false,
    val updated: Long = System.currentTimeMillis(),
) {
    fun usedAssets(): Set<String> =
        pages.flatMap { p -> listOfNotNull(p.source) + p.images.map { it.asset } }.toSet()

    fun validate(): PdfProject = apply {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && name.isNotBlank() && name.length <= 80)
        require(pages.isNotEmpty() && pages.size <= 100 && dpi in 72..600)
        require(columns in 1..6 && gap.isFinite() && gap in 0.0..30.0)
        require(usedAssets().size <= 128)
        require(assets.map { it.hash }.distinct().size == assets.size)
        assets.forEach {
            require(it.orientation in 1..8)
            require(
                it.hash.matches(Regex("[a-f0-9]{64}")) &&
                    it.mime in setOf("application/pdf", "image/jpeg", "image/png", "image/webp")
            )
        }
        val known = assets.map { it.hash }.toSet()
        require(pages.map { it.id }.distinct().size == pages.size)
        pages.forEach { p ->
            require(p.id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
            require(p.images.map { it.id }.distinct().size == p.images.size)
            require(
                p.width.isFinite() &&
                    p.height.isFinite() &&
                    p.width in 20.0..2000.0 &&
                    p.height in 20.0..2000.0
            )
            require(
                p.rotation in setOf(0, 90, 180, 270) &&
                    p.margin.isFinite() &&
                    p.margin in 0.0..min(p.width, p.height) / 4
            )
            require(p.images.size <= 24 && p.sourcePage >= 0)
            p.source?.let { hash ->
                require(
                    hash in known &&
                        assets.first { it.hash == hash }.mime == "application/pdf" &&
                        p.images.isEmpty()
                )
            }
            p.images.forEach { i ->
                require(i.id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
                require(
                    i.asset in known &&
                        assets.first { it.hash == i.asset }.mime.startsWith("image/")
                )
                require(
                    listOf(i.x, i.y, i.width, i.height, i.focusX, i.focusY).all(Double::isFinite)
                )
                require(
                    i.x >= 0 &&
                        i.y >= 0 &&
                        i.width > 0 &&
                        i.height > 0 &&
                        i.x + i.width <= p.width + .001 &&
                        i.y + i.height <= p.height + .001
                )
                require(
                    i.focusX in 0.0..1.0 &&
                        i.focusY in 0.0..1.0 &&
                        i.rotation in setOf(0, 90, 180, 270)
                )
            }
        }
    }
}

object PdfGeometry {
    fun constrain(i: PdfImage, p: PdfPage): PdfImage {
        val scale =
            min(1.0, min((p.width - 2 * p.margin) / i.width, (p.height - 2 * p.margin) / i.height))
        val w = i.width * scale
        val h = i.height * scale
        return i.copy(
            width = w,
            height = h,
            x = i.x.coerceIn(p.margin, maxOf(p.margin, p.width - p.margin - w)),
            y = i.y.coerceIn(p.margin, maxOf(p.margin, p.height - p.margin - h)),
        )
    }

    fun resize(
        i: PdfImage,
        p: PdfPage,
        width: Double,
        height: Double,
        widthChanged: Boolean,
    ): PdfImage {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
        val w = if (i.locked && !widthChanged) height * i.width / i.height else width
        val h = if (i.locked && widthChanged) width * i.height / i.width else height
        return constrain(i.copy(width = w, height = h), p)
    }

    /** Which corner a resize handle drag is anchored to; the opposite corner stays fixed. */
    enum class Corner {
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight,
    }

    /**
     * Resizes [i] by dragging [corner], keeping the opposite corner fixed in page space. Used by
     * the canvas's four corner handles; [Corner.BottomRight] matches the pre-existing single-handle
     * behavior exactly (anchor at top-left, width/height grow to the right/down).
     */
    fun resizeFromCorner(
        i: PdfImage,
        p: PdfPage,
        corner: Corner,
        dxMm: Double,
        dyMm: Double,
    ): PdfImage {
        val right = i.x + i.width
        val bottom = i.y + i.height
        var width = i.width
        var height = i.height
        var widthChanged = true
        when (corner) {
            Corner.BottomRight -> {
                width = i.width + dxMm
                height = i.height + dyMm
            }
            Corner.BottomLeft -> {
                width = i.width - dxMm
                height = i.height + dyMm
            }
            Corner.TopRight -> {
                width = i.width + dxMm
                height = i.height - dyMm
                widthChanged = false
            }
            Corner.TopLeft -> {
                width = i.width - dxMm
                height = i.height - dyMm
                widthChanged = false
            }
        }
        width = width.coerceAtLeast(.1)
        height = height.coerceAtLeast(.1)
        val resized = resize(i, p, width, height, widthChanged)
        val x = if (corner == Corner.TopLeft || corner == Corner.BottomLeft) right - resized.width else i.x
        val y = if (corner == Corner.TopLeft || corner == Corner.TopRight) bottom - resized.height else i.y
        return constrain(resized.copy(x = x, y = y), p)
    }

    fun grid(p: PdfPage, columns: Int, gap: Double): PdfPage {
        require(columns in 1..6 && gap in 0.0..30.0)
        if (p.images.isEmpty()) return p
        val rows = (p.images.size + columns - 1) / columns
        val w = (p.width - 2 * p.margin - gap * (columns - 1)) / columns
        val h = (p.height - 2 * p.margin - gap * (rows - 1)) / rows
        require(w > 0 && h > 0)
        return p.copy(
            images =
                p.images.mapIndexed { n, i ->
                    i.copy(
                        x = p.margin + n % columns * (w + gap),
                        y = p.margin + n / columns * (h + gap),
                        width = w,
                        height = h,
                    )
                }
        )
    }
}
