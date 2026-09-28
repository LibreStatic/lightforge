package com.librestatic.lightforge.feature.pdfstudio

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
    /** Paint order (Phase G1a) — see [PdfLayers]. Defaults to 0, the value every image encoded
     * before texts existed decodes with, so a v1 project's stacking (list order, images only)
     * is unchanged. */
    val z: Int = 0,
)

/** Simple text-layer font family (Phase G1a). Bundled as Noto Sans / Noto Serif, Regular and
 * Bold only — see `feature/pdfstudio/src/main/assets/fonts/`. */
enum class PdfFontFamily {
    Sans,
    Serif,
}

enum class PdfFontWeight {
    Regular,
    Bold,
}

/** Horizontal text alignment within [PdfText]'s box. Distinct from [PdfGeometry.Align], which
 * aligns a whole element relative to the page/margins; `Start` is always the physical left edge —
 * the page is never mirrored, including in RTL locales. */
enum class PdfTextAlign {
    Start,
    Center,
    End,
}

/** Fixed print-space ink palette for the text layer. Never resolved through the Material theme —
 * see [PdfPaperTokens], the only file allowed to turn these into actual color values, for both
 * Compose (editor, Phase G1b) and the isolated PDFBox renderer. */
enum class PdfInk {
    Black,
    DarkGray,
    Red,
    Blue,
    Green,
}

/**
 * A simple text box (Phase G1a). Geometry (`x`/`y`/`width`/`height`) uses the same page-space mm
 * coordinates as [PdfImage]. Not allowed on an imported-PDF page ([PdfPage.source] != null) — see
 * [PdfProject.validate].
 */
data class PdfText(
    val id: String = newId(),
    val text: String,
    val x: Double = 10.0,
    val y: Double = 10.0,
    val width: Double = 100.0,
    val height: Double = 40.0,
    val sizePt: Double = 12.0,
    val font: PdfFontFamily = PdfFontFamily.Sans,
    val weight: PdfFontWeight = PdfFontWeight.Regular,
    val align: PdfTextAlign = PdfTextAlign.Center,
    val ink: PdfInk = PdfInk.Black,
    /** Paint order (Phase G1a) — see [PdfLayers]. */
    val z: Int = 0,
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
    /** Text layer (Phase G1a). Always empty on an imported-PDF page ([source] != null) — see
     * [PdfProject.validate]. Serialized by [PdfCodec] version 2; a version-1 project decodes with
     * an empty list, unchanged from before texts existed. */
    val texts: List<PdfText> = emptyList(),
)

/**
 * Shared paint order for a page's images and texts (Phase G1a). The simplest robust option that
 * lets the two element kinds interleave without a separate ordering list to keep in sync with
 * [PdfPage.images]/[PdfPage.texts]: each element carries its own `z` int, and paint order is
 * simply "sort by `z`". Ties keep each list's own relative order, images before texts — so a v1
 * project, where every image defaults to `z == 0` and no texts exist, paints exactly as it always
 * did. A future Layer menu (Phase G1b: bring forward/back) only has to change one element's `z`
 * (e.g. `max(every other z) + 1` to bring to front, `min(...) - 1` to send to back); nothing else
 * needs to move.
 */
object PdfLayers {
    sealed interface Element {
        val z: Int

        data class Img(val image: PdfImage) : Element {
            override val z get() = image.z
        }

        data class Txt(val text: PdfText) : Element {
            override val z get() = text.z
        }
    }

    /** [page]'s images and texts, interleaved in paint order (back to front). */
    fun order(page: PdfPage): List<Element> =
        (page.images.map(Element::Img) + page.texts.map(Element::Txt)).sortedBy { it.z }

    /** The `z` a newly added element should use to paint in front of everything already on
     * [page]. */
    fun nextZ(page: PdfPage): Int =
        (page.images.asSequence().map { it.z } + page.texts.asSequence().map { it.z }).maxOrNull()?.plus(1) ?: 0

    /** The id of [element] regardless of kind — images and texts share one id space (see
     * [PdfProject.validate]), so a Layer menu can look an element up by id alone. */
    fun elementId(element: Element): String =
        when (element) {
            is Element.Img -> element.image.id
            is Element.Txt -> element.text.id
        }

    /** [page] with the element identified by [id] (either an image or a text) given a new [z],
     * every other element untouched (Phase G1b's Layer menu: bring forward/backward/front/back
     * across both kinds — a single z-swap between two adjacent [order] entries, whichever kind
     * they are). A no-op if [id] matches nothing. */
    fun withZ(page: PdfPage, id: String, z: Int): PdfPage =
        page.copy(
            images = page.images.map { if (it.id == id) it.copy(z = z) else it },
            texts = page.texts.map { if (it.id == id) it.copy(z = z) else it },
        )

    /**
     * [page] with EVERY element's `z` reassigned to its index within [newOrder] — a full
     * permutation of every element's id on the page, back to front. Used by the Layer menu's
     * bring-forward/send-backward/to-front/to-back commands (Phase G1b round-2 fix): swapping
     * only the two z VALUES being reordered is a no-op whenever they started tied, which is the
     * common case since every image defaults to `z = 0`. Reassigning by index instead guarantees a
     * strictly increasing z per position regardless of the old z values, so the op always has a
     * visible (and exported) effect.
     */
    fun normalizeZ(page: PdfPage, newOrder: List<String>): PdfPage {
        var result = page
        newOrder.forEachIndexed { index, id -> result = withZ(result, id, index) }
        return result
    }
}

/**
 * Which single element is selected on the canvas (Phase G1b): generalizes the old bare
 * `state.image: Int` to also cover a selected text, while keeping images addressed by list index
 * (as every existing image call site — [PdfStudioViewModel.imageEdit], the accessibility tests —
 * already does) rather than switching everything to id lookups. Multi-selection (Phase G2) is a
 * `Set<PdfElementRef>` this same sealed type slots into directly; nothing here forecloses that.
 */
sealed interface PdfElementRef {
    data class Image(val index: Int) : PdfElementRef

    data class Text(val id: String) : PdfElementRef
}

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
    /**
     * Print-layout settings (feedback item B, added when [PdfCodec] bumped to version 3):
     * [printSize] is the id of a [PdfPrintSize] the project's photos are laid out at exact
     * physical dimensions on the current paper (see [PdfPrintLayout]), or `null` for the
     * pre-existing "free grid" behavior ([columns]/[PdfGeometry.grid]). A version 1/2 project has
     * no such concept and decodes with `null`, reproducing its old free-grid layout exactly.
     */
    val printSize: String? = null,
    /**
     * Default photo placement mode (feedback item A) applied automatically to every photo placed
     * by layout (Arrange, grid templates, print-size layouts, gallery handoff, Media/import
     * inserts into auto slots): [PdfFit.Cover] ("Fill" — crop to fit, matching Windows Photo
     * Printing's "Fill picture frame") or [PdfFit.Contain] ("Fit" — whole photo, letterboxed).
     * Per-image `fit` in the Adjust panel remains a per-photo override on top of this default. A
     * version 1/2 project has no such concept and decodes with [PdfFit.Contain], reproducing the
     * fit every pre-existing template/free-grid image already defaulted to.
     */
    val placementMode: PdfFit = PdfFit.Contain,
) {
    fun usedAssets(): Set<String> =
        pages.flatMap { p -> listOfNotNull(p.source) + p.images.map { it.asset } }.toSet()

    fun validate(): PdfProject = apply {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && name.isNotBlank() && name.length <= 80)
        require(pages.isNotEmpty() && pages.size <= 100 && dpi in 72..600)
        require(columns in 1..6 && gap.isFinite() && gap in 0.0..30.0)
        require(printSize == null || PdfPrintSize.fromId(printSize) != null)
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
            // Ids are unique across BOTH element kinds (Phase G1a): they share one id space so a
            // Layer menu or a selection set can reference either kind without a type tag.
            val elementIds = p.images.map { it.id } + p.texts.map { it.id }
            require(elementIds.distinct().size == elementIds.size)
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
            // 24-element cap now counts images AND texts together (Phase G1a).
            require(p.images.size + p.texts.size <= 24 && p.sourcePage >= 0)
            p.source?.let { hash ->
                require(
                    hash in known &&
                        assets.first { it.hash == hash }.mime == "application/pdf" &&
                        p.images.isEmpty() &&
                        // Imported PDF pages keep their original vector content; the text layer is
                        // only for pages this app lays out itself.
                        p.texts.isEmpty()
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
            p.texts.forEach { t ->
                require(t.id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
                require(t.text.isNotEmpty() && t.text.length <= 2000)
                require(PdfTextSupport.check(t.text).isSuccess)
                require(listOf(t.x, t.y, t.width, t.height, t.sizePt).all(Double::isFinite))
                require(
                    t.x >= 0 &&
                        t.y >= 0 &&
                        t.width > 0 &&
                        t.height > 0 &&
                        t.x + t.width <= p.width + .001 &&
                        t.y + t.height <= p.height + .001
                )
                require(t.sizePt in 6.0..144.0)
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

    /** A text box's minimum edge (mm) — small enough to stay out of the way, large enough that a
     * corner-drag resize never collapses the box to something unusable. */
    private const val MIN_TEXT_EDGE = 5.0

    /**
     * As [constrainToPage], but for a [PdfText] box: scales down only if it can't fit the
     * physical page at all, then clamps x/y to the page. Text has no aspect-ratio lock (resizing
     * its box never changes the font size), so this is simpler than the image version.
     */
    fun constrainTextToPage(t: PdfText, p: PdfPage): PdfText {
        val scale = min(1.0, min(p.width / t.width, p.height / t.height))
        val w = (t.width * scale).coerceAtLeast(MIN_TEXT_EDGE)
        val h = (t.height * scale).coerceAtLeast(MIN_TEXT_EDGE)
        return t.copy(
            width = w,
            height = h,
            x = t.x.coerceIn(0.0, maxOf(0.0, p.width - w)),
            y = t.y.coerceIn(0.0, maxOf(0.0, p.height - h)),
        )
    }

    /**
     * As [resizeFromCorner], but for a [PdfText] box: the opposite corner stays fixed in page
     * space, width/height are free (no locked aspect ratio — resizing the box never changes the
     * font size), and the result never overflows the page.
     */
    fun resizeTextFromCorner(
        t: PdfText,
        p: PdfPage,
        corner: Corner,
        dxMm: Double,
        dyMm: Double,
    ): PdfText {
        val right = t.x + t.width
        val bottom = t.y + t.height
        var width = t.width
        var height = t.height
        when (corner) {
            Corner.BottomRight -> {
                width = t.width + dxMm
                height = t.height + dyMm
            }
            Corner.BottomLeft -> {
                width = t.width - dxMm
                height = t.height + dyMm
            }
            Corner.TopRight -> {
                width = t.width + dxMm
                height = t.height - dyMm
            }
            Corner.TopLeft -> {
                width = t.width - dxMm
                height = t.height - dyMm
            }
        }
        width = width.coerceAtLeast(MIN_TEXT_EDGE)
        height = height.coerceAtLeast(MIN_TEXT_EDGE)
        val anchorX = if (corner == Corner.TopLeft || corner == Corner.BottomLeft) right else t.x
        val anchorY = if (corner == Corner.TopLeft || corner == Corner.TopRight) bottom else t.y
        val roomX = if (corner == Corner.TopLeft || corner == Corner.BottomLeft) anchorX else p.width - anchorX
        val roomY = if (corner == Corner.TopLeft || corner == Corner.TopRight) anchorY else p.height - anchorY
        val w = width.coerceAtMost(roomX.coerceAtLeast(MIN_TEXT_EDGE))
        val h = height.coerceAtMost(roomY.coerceAtLeast(MIN_TEXT_EDGE))
        val x = if (corner == Corner.TopLeft || corner == Corner.BottomLeft) anchorX - w else anchorX
        val y = if (corner == Corner.TopLeft || corner == Corner.TopRight) anchorY - h else anchorY
        return t.copy(x = x, y = y, width = w, height = h)
    }

    /** As [align], for a [PdfText] box — same reference-frame rule (margins vs. the full page),
     * but no [constrain]/[constrainToPage] aspect handling since text has none. */
    fun alignText(t: PdfText, p: PdfPage, align: Align, relativeToMargins: Boolean = true): PdfText {
        val left = if (relativeToMargins) p.margin else 0.0
        val top = if (relativeToMargins) p.margin else 0.0
        val right = if (relativeToMargins) p.width - p.margin else p.width
        val bottom = if (relativeToMargins) p.height - p.margin else p.height
        val x =
            when (align) {
                Align.Left -> left
                Align.Center -> (left + right - t.width) / 2
                Align.Right -> right - t.width
                else -> t.x
            }
        val y =
            when (align) {
                Align.Top -> top
                Align.Middle -> (top + bottom - t.height) / 2
                Align.Bottom -> bottom - t.height
                else -> t.y
            }
        return constrainTextToPage(t.copy(x = x, y = y), p)
    }

    /**
     * Arranges [p]'s images into a [columns]-wide grid. [rowsHint], when given, comes from the
     * grid-template tile the user picked (see [PdfLayoutTemplates.rowsFor]) so that templates
     * sharing a column count — 4 (2×2 in portrait) vs 6 (2×3) — still produce differently
     * proportioned frames instead of both collapsing to `ceil(imageCount / columns)` rows. It is
     * always widened to fit every image (never clipped), so a hint smaller than the image count
     * only affects frame height/width, never drops images off the page. Phase F item 0.
     */
    fun grid(p: PdfPage, columns: Int, gap: Double, rowsHint: Int? = null): PdfPage {
        require(columns in 1..6 && gap in 0.0..30.0)
        if (p.images.isEmpty()) return p
        val minRows = (p.images.size + columns - 1) / columns
        val rows = maxOf(rowsHint?.takeIf { it > 0 } ?: minRows, minRows)
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
