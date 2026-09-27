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

    /**
     * As [constrain], but clamps x/y to the full physical page instead of the margin box.
     * Margins are a *guide*, not a hard wall, for manual per-image edits — move ([moveImage]/
     * [moveImageTo]), resize ([resize], [resizeFromCorner]) and align's `relativeToMargins =
     * false` path all use this, so a user can deliberately place or size an image right up to
     * the page edge. Layout-driven re-fits (grid Arrange, and re-fitting every image after a
     * paper/margin/custom-size change) keep using [constrain] instead, since those are the
     * project's own layout rules, not a one-off manual placement.
     */
    fun constrainToPage(i: PdfImage, p: PdfPage): PdfImage {
        // Scale down only if it can't fit the physical page at all (never the margin box —
        // that's the whole point of this variant).
        val scale = min(1.0, min(p.width / i.width, p.height / i.height))
        val w = i.width * scale
        val h = i.height * scale
        return i.copy(
            width = w,
            height = h,
            x = i.x.coerceIn(0.0, maxOf(0.0, p.width - w)),
            y = i.y.coerceIn(0.0, maxOf(0.0, p.height - h)),
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
        // Manual resize (steppers, corner-drag equivalent): margins are a guide, not a wall.
        return constrainToPage(i.copy(width = w, height = h), p)
    }

    /** Which corner a resize handle drag is anchored to; the opposite corner stays fixed. */
    enum class Corner {
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight,
    }

    /** Relative-to-page alignment target for the canvas's Align menu. */
    enum class Align {
        Left,
        Center,
        Right,
        Top,
        Middle,
        Bottom,
    }

    /**
     * Aligns [i] relative to [p]'s margins, then [constrain]s the result. Routing through
     * [constrain] matters: Right/Bottom (or any image wider/taller than the margin box) would
     * otherwise land at a negative x/y, which fails [PdfProject.validate] downstream.
     */
    fun align(i: PdfImage, p: PdfPage, align: Align): PdfImage = align(i, p, align, relativeToMargins = true)

    /**
     * As [align], but [relativeToMargins] chooses whether Left/Right/Top/Bottom/Center/Middle
     * treat the margin box or the full physical page as the reference frame (Phase D's Adjust
     * panel "Relative to Page / Margins" choice). `relativeToMargins = true` reproduces the
     * 3-argument [align] exactly: edges land on the margin, and Center/Middle land at the
     * physical page's midpoint (which — because margins are symmetric — is also the margin box's
     * midpoint, so the two reference frames agree there).
     */
    fun align(i: PdfImage, p: PdfPage, align: Align, relativeToMargins: Boolean): PdfImage {
        val left = if (relativeToMargins) p.margin else 0.0
        val top = if (relativeToMargins) p.margin else 0.0
        val right = if (relativeToMargins) p.width - p.margin else p.width
        val bottom = if (relativeToMargins) p.height - p.margin else p.height
        val x =
            when (align) {
                Align.Left -> left
                Align.Center -> (left + right - i.width) / 2
                Align.Right -> right - i.width
                else -> i.x
            }
        val y =
            when (align) {
                Align.Top -> top
                Align.Middle -> (top + bottom - i.height) / 2
                Align.Bottom -> bottom - i.height
                else -> i.y
            }
        return if (relativeToMargins) constrain(i.copy(x = x, y = y), p) else constrainToPage(i.copy(x = x, y = y), p)
    }

    /**
     * Resizes [i] by dragging [corner], keeping the opposite corner fixed in page space. Used by
     * the canvas's four corner handles; [Corner.BottomRight] matches the pre-existing single-handle
     * behavior exactly (anchor at top-left, width/height grow to the right/down).
     */
    /**
     * Resizes [i] by dragging [corner], keeping the opposite corner ("the anchor") fixed in page
     * space no matter what — including when the requested size would overflow the page, where
     * routing through [resize]/[constrain] (which clamp x/y independently of which corner is
     * being dragged) used to let the anchor drift. Instead this scales width/height to the room
     * actually available *from the anchor* to the page's far margin, then derives x/y purely from
     * the anchor and the (possibly shrunk) size, so the anchor corner never moves.
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
        var w = if (i.locked && !widthChanged) height * i.width / i.height else width
        var h = if (i.locked && widthChanged) width * i.height / i.width else height
        val anchorX = if (corner == Corner.TopLeft || corner == Corner.BottomLeft) right else i.x
        val anchorY = if (corner == Corner.TopLeft || corner == Corner.TopRight) bottom else i.y
        // Manual corner-drag resize: margins are a guide, not a wall — room runs to the
        // physical page edge, not the margin box (matches [resize] and [moveImage]).
        val roomX = if (corner == Corner.TopLeft || corner == Corner.BottomLeft) anchorX
                    else p.width - anchorX
        val roomY = if (corner == Corner.TopLeft || corner == Corner.TopRight) anchorY
                    else p.height - anchorY
        val scale =
            listOf(1.0, roomX / w, roomY / h).filter { it.isFinite() }.minOrNull()?.coerceAtLeast(0.0)
                ?: 1.0
        w = (w * scale).coerceAtLeast(.1)
        h = (h * scale).coerceAtLeast(.1)
        val x = if (corner == Corner.TopLeft || corner == Corner.BottomLeft) anchorX - w else anchorX
        val y = if (corner == Corner.TopLeft || corner == Corner.TopRight) anchorY - h else anchorY
        return i.copy(x = x, y = y, width = w, height = h)
    }

    /**
     * Swaps [i]'s asset for [newAsset] (replacing [oldAsset]) while keeping its frame geometry
     * (x/y/width/height/rotation/fit) — the core of the canvas's contextual Replace action. If the
     * fit is Cover and the new asset's aspect ratio differs noticeably from the old one, the crop
     * focus resets to center so an off-center crop tuned for the old photo doesn't carry over onto
     * different content framed at the same spot.
     */
    fun replaceAsset(i: PdfImage, oldAsset: PdfAsset, newAsset: PdfAsset): PdfImage {
        fun aspect(a: PdfAsset) = if (a.height != 0) a.width.toDouble() / a.height else 1.0
        val resetFocus =
            i.fit == PdfFit.Cover && kotlin.math.abs(aspect(oldAsset) - aspect(newAsset)) > .01
        return i.copy(
            asset = newAsset.hash,
            focusX = if (resetFocus) .5 else i.focusX,
            focusY = if (resetFocus) .5 else i.focusY,
        )
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
