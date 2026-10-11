package com.librestatic.lightforge.core.model

/** A non-destructive edit operation. Coordinates are normalized to 0..1000 permille. */
sealed interface EditOperation : java.io.Serializable {
    data class Crop(
        val leftPermille: Int,
        val topPermille: Int,
        val rightPermille: Int,
        val bottomPermille: Int,
    ) : EditOperation {
        init {
            require(leftPermille in 0..999 && topPermille in 0..999)
            require(rightPermille in 1..1000 && bottomPermille in 1..1000)
            require(rightPermille > leftPermille && bottomPermille > topPermille)
        }

        /** A crop that keeps every pixel; the history drops it like a neutral Tone or Filter. */
        val isFullFrame: Boolean
            get() = leftPermille == 0 && topPermille == 0 && rightPermille == 1_000 && bottomPermille == 1_000
    }

    data class Rotate(val degrees: Int) : EditOperation {
        init { require(degrees % 90 == 0) }
    }

    data class Flip(val horizontal: Boolean) : EditOperation

    /** Clockwise fine rotation used by the interactive crop tool. */
    data class Straighten(val degrees: Float) : EditOperation {
        init { require(degrees in -45f..45f) }
    }

    data class Tone(
        val brightness: Float = 0f,
        val contrast: Float = 1f,
        val saturation: Float = 1f,
    ) : EditOperation {
        init {
            require(brightness in -1f..1f)
            require(contrast in 0f..4f)
            require(saturation in 0f..4f)
        }
    }

    data class Filter(val name: String) : EditOperation {
        init { require(name in setOf("none", "natural", "vivid", "mono")) }
    }

    data class RawDevelop(val settings: RawDevelopmentSettings) : EditOperation

    /**
     * Sparse set of tone and color sliders, each -1..1 where 0 is neutral. Zero entries are dropped
     * and the map is ordered by [AdjustKey] so equal adjustments compare and encode identically.
     * Shares the color slot with the legacy [Tone].
     */
    class Adjust(values: Map<AdjustKey, Float>) : EditOperation {
        val values: Map<AdjustKey, Float> = values.entries
            .onEach { require(it.value.isFinite() && it.value in -1f..1f) { "Adjust ${it.key} out of range" } }
            .filter { it.value != 0f }
            .sortedBy { it.key.ordinal }
            .associateTo(LinkedHashMap()) { it.key to it.value }

        val isNeutral: Boolean get() = values.isEmpty()

        operator fun get(key: AdjustKey): Float = values[key] ?: 0f

        override fun equals(other: Any?): Boolean = other is Adjust && other.values == values
        override fun hashCode(): Int = values.hashCode()
        override fun toString(): String = "Adjust($values)"
    }

    /** Per-hue-band adjustments, exactly one band per [ColorMixHue] in declaration order. */
    data class ColorMix(val bands: List<ColorMixBand>) : EditOperation {
        init { require(bands.size == ColorMixHue.entries.size) }

        val isNeutral: Boolean get() = bands.all { it.isNeutral }

        operator fun get(hue: ColorMixHue): ColorMixBand = bands[hue.ordinal]

        companion object {
            val Neutral = ColorMix(List(ColorMixHue.entries.size) { ColorMixBand() })
        }
    }

    /**
     * A LUT look applied at [intensity] 0..1. [id] is `builtin:<name>` or `custom:<lowercase uuid>`.
     * Shares the filter slot with the legacy [Filter].
     */
    data class Look(val id: String, val intensity: Float) : EditOperation {
        init {
            require(BuiltinId.matches(id) || CustomId.matches(id)) { "Invalid look id" }
            require(intensity in 0f..1f)
        }

        val isCustom: Boolean get() = id.startsWith("custom:")

        private companion object {
            val BuiltinId = Regex("builtin:[a-z0-9_]{1,32}")
            val CustomId = Regex("custom:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        }
    }

    /**
     * Four source corners (top-left, top-right, bottom-right, bottom-left of the output) in the
     * frame after Rotate/Flip, which are mapped onto the full output rectangle.
     */
    data class Perspective(val corners: List<NormalizedPoint>) : EditOperation {
        init {
            require(corners.size == 4)
            // Every turn must have the same positive sign: a convex, non-degenerate quad.
            for (i in 0 until 4) {
                val a = corners[i]
                val b = corners[(i + 1) % 4]
                val c = corners[(i + 2) % 4]
                require((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 0f) { "Degenerate quad" }
            }
        }

        val isIdentity: Boolean get() = corners == Identity

        companion object {
            val Identity = listOf(
                NormalizedPoint(0f, 0f), NormalizedPoint(1f, 0f),
                NormalizedPoint(1f, 1f), NormalizedPoint(0f, 1f),
            )
        }
    }
}

enum class AdjustKey(val token: String) {
    Brightness("brightness"),
    Contrast("contrast"),
    Tone("tone"),
    WhitePoint("whitepoint"),
    Highlights("highlights"),
    Shadows("shadows"),
    BlackPoint("blackpoint"),
    Saturation("saturation"),
    Warmth("warmth"),
    Tint("tint"),
    SkinTone("skintone"),
    BlueTone("bluetone"),
    Vignette("vignette"),
}

enum class ColorMixHue(val token: String, val centerDegrees: Int) {
    Red("red", 0),
    Orange("orange", 45),
    Yellow("yellow", 90),
    Green("green", 135),
    Aqua("aqua", 180),
    Blue("blue", 225),
    Purple("purple", 270),
    Magenta("magenta", 315),
}

data class ColorMixBand(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
) : java.io.Serializable {
    init {
        require(hue in -1f..1f && saturation in -1f..1f && luminance in -1f..1f)
    }

    val isNeutral: Boolean get() = hue == 0f && saturation == 0f && luminance == 0f
}

/** A point in a frame normalized to 0..1 on both axes, y pointing down. */
data class NormalizedPoint(val x: Float, val y: Float) : java.io.Serializable {
    init { require(x in 0f..1f && y in 0f..1f) }
}

data class EditRecipe(
    val recipeId: String,
    val source: MediaKey?,
    val sourceGenerationModified: Long,
    val operations: List<EditOperation> = emptyList(),
    val revision: Int = 0,
) : java.io.Serializable {
    init {
        require(recipeId.isNotBlank())
        require(sourceGenerationModified >= 0)
        require(revision >= 0)
    }

    val isIdentity: Boolean get() = operations.isEmpty()

    fun append(operation: EditOperation): EditRecipe = copy(
        operations = operations + operation,
        revision = revision + 1,
    )

    fun replaceLast(operation: EditOperation): EditRecipe = if (operations.isEmpty()) {
        append(operation)
    } else {
        copy(operations = operations.dropLast(1) + operation, revision = revision + 1)
    }

    companion object {
        fun forSource(source: MediaKey, generationModified: Long): EditRecipe = EditRecipe(
            recipeId = EditRecipeIds.forSource(source, generationModified),
            source = source,
            sourceGenerationModified = generationModified,
        )

        fun ephemeral(sourceId: String): EditRecipe = EditRecipe(
            recipeId = "external:$sourceId",
            source = null,
            sourceGenerationModified = 0,
        )
    }
}

/** Bounded command history. Redo is invalidated by every new edit. */
data class EditHistory(
    val past: List<EditRecipe>,
    val present: EditRecipe,
    val future: List<EditRecipe> = emptyList(),
    val maxEntries: Int = 100,
) : java.io.Serializable {
    init { require(maxEntries in 1..500) }

    fun apply(operation: EditOperation): EditHistory {
        val operations = when (operation) {
            is EditOperation.Filter -> replaceSlot<EditOperation.Filter>(
                operation.takeUnless { it.name == "none" },
            )
            is EditOperation.Tone -> replaceSlot<EditOperation.Tone>(
                operation.takeUnless { it == EditOperation.Tone() },
            )
            is EditOperation.Crop -> placeCrop(operation.takeUnless { it.isFullFrame })
            is EditOperation.Straighten -> replaceSlot<EditOperation.Straighten>(
                operation.takeUnless { it.degrees == 0f },
            )
            is EditOperation.RawDevelop -> replaceSlot<EditOperation.RawDevelop>(operation)
            is EditOperation.Adjust -> replaceSlotOf(operation.takeUnless { it.isNeutral }) {
                it is EditOperation.Adjust || it is EditOperation.Tone
            }
            is EditOperation.ColorMix -> replaceSlot<EditOperation.ColorMix>(
                operation.takeUnless { it.isNeutral },
            )
            is EditOperation.Look -> replaceSlotOf(operation.takeUnless { it.intensity == 0f }) {
                it is EditOperation.Look || it is EditOperation.Filter
            }
            is EditOperation.Perspective -> placePerspective(operation.takeUnless { it.isIdentity })
            is EditOperation.Rotate, is EditOperation.Flip -> insertGeometry(operation)
        }
        if (operations == present.operations) return this
        return copy(
            past = (past + present).takeLast(maxEntries),
            present = present.copy(operations = operations, revision = present.revision + 1),
            future = emptyList(),
        )
    }

    /**
     * The crop tool frames the image with every Rotate/Flip applied, so a Crop must render after
     * all geometry operations. A Crop that an older recipe left before a Rotate/Flip moves behind it.
     */
    private fun placeCrop(replacement: EditOperation.Crop?): List<EditOperation> {
        val operations = replaceSlot<EditOperation.Crop>(replacement)
        if (replacement == null) return operations
        val cropIndex = operations.indexOfFirst { it is EditOperation.Crop }
        val lastGeometry = operations.indexOfLast { it is EditOperation.Rotate || it is EditOperation.Flip }
        if (cropIndex > lastGeometry) return operations
        return operations.toMutableList().apply {
            removeAt(cropIndex)
            add(lastGeometry, replacement)
        }
    }

    /** Perspective renders after every Rotate/Flip and before Straighten and Crop. */
    private fun placePerspective(replacement: EditOperation.Perspective?): List<EditOperation> {
        val operations = replaceSlot<EditOperation.Perspective>(null)
        if (replacement == null) return operations
        val firstFramed = operations.indexOfFirst { it is EditOperation.Crop || it is EditOperation.Straighten }
        val lastGeometry = operations.indexOfLast { it is EditOperation.Rotate || it is EditOperation.Flip }
        val index = when {
            firstFramed < 0 -> operations.size
            firstFramed > lastGeometry -> firstFramed
            else -> lastGeometry + 1
        }
        return operations.toMutableList().apply { add(index, replacement) }
    }

    /**
     * Inserts Rotate/Flip before an existing Crop/Straighten/Perspective and maps them into the new frame, which
     * renders the same pixels as appending it while keeping the Crop in the crop tool's frame.
     */
    private fun insertGeometry(operation: EditOperation): List<EditOperation> {
        val operations = present.operations
        val firstFramed = operations.indexOfFirst { it.isFramed }
        val geometryFollowsFrame = firstFramed >= 0 && operations.drop(firstFramed)
            .any { it is EditOperation.Rotate || it is EditOperation.Flip }
        if (firstFramed < 0 || geometryFollowsFrame) return operations + operation
        return operations.take(firstFramed) + operation + operations.drop(firstFramed).map { existing ->
            when (existing) {
                is EditOperation.Crop -> existing.afterGeometry(operation)
                is EditOperation.Perspective -> existing.afterGeometry(operation)
                is EditOperation.Straighten ->
                    if (operation is EditOperation.Flip) EditOperation.Straighten(-existing.degrees) else existing
                else -> existing
            }
        }
    }

    private inline fun <reified T : EditOperation> replaceSlot(replacement: T?): List<EditOperation> =
        replaceSlotOf(replacement) { it is T }

    /** Replaces the first operation matching [inSlot] (removing the others), or appends it. */
    private inline fun replaceSlotOf(
        replacement: EditOperation?,
        inSlot: (EditOperation) -> Boolean,
    ): List<EditOperation> {
        val firstIndex = present.operations.indexOfFirst(inSlot)
        if (firstIndex < 0) return if (replacement == null) present.operations else present.operations + replacement
        return buildList {
            present.operations.forEachIndexed { index, existing ->
                when {
                    index == firstIndex && replacement != null -> add(replacement)
                    !inSlot(existing) -> add(existing)
                }
            }
        }
    }

    fun applyRawDevelopment(settings: RawDevelopmentSettings): EditHistory {
        val operation = EditOperation.RawDevelop(settings)
        val updated = present.copy(
            operations = present.operations.filterNot { it is EditOperation.RawDevelop } + operation,
            revision = present.revision + 1,
        )
        return copy(
            past = (past + present).takeLast(maxEntries),
            present = updated,
            future = emptyList(),
        )
    }

    /** Replaces the complete color grade as one undoable editor action. */
    fun replaceColorOperations(
        tone: EditOperation.Tone?,
        filter: EditOperation.Filter? = null,
    ): EditHistory {
        val replacements = buildList<EditOperation> {
            tone?.takeUnless { it == EditOperation.Tone() }?.let(::add)
            filter?.takeUnless { it.name == "none" }?.let(::add)
        }
        val updatedOperations = present.operations
            .filterNot { it is EditOperation.Tone || it is EditOperation.Filter } + replacements
        if (updatedOperations == present.operations) return this
        return copy(
            past = (past + present).takeLast(maxEntries),
            present = present.copy(operations = updatedOperations, revision = present.revision + 1),
            future = emptyList(),
        )
    }

    /** Replaces Tone, Filter, Adjust, ColorMix and Look together as one undoable editor action. */
    fun replaceColorGrade(
        adjust: EditOperation.Adjust?,
        colorMix: EditOperation.ColorMix?,
        look: EditOperation.Look?,
    ): EditHistory {
        val replacements = buildList<EditOperation> {
            adjust?.takeUnless { it.isNeutral }?.let(::add)
            colorMix?.takeUnless { it.isNeutral }?.let(::add)
            look?.takeUnless { it.intensity == 0f }?.let(::add)
        }
        val updatedOperations = present.operations.filterNot {
            it is EditOperation.Tone || it is EditOperation.Filter || it is EditOperation.Adjust ||
                it is EditOperation.ColorMix || it is EditOperation.Look
        } + replacements
        if (updatedOperations == present.operations) return this
        return copy(
            past = (past + present).takeLast(maxEntries),
            present = present.copy(operations = updatedOperations, revision = present.revision + 1),
            future = emptyList(),
        )
    }

    fun undo(): EditHistory = if (past.isEmpty()) this else copy(
        past = past.dropLast(1),
        present = past.last(),
        future = (future + present).takeLast(maxEntries),
    )

    fun redo(): EditHistory = if (future.isEmpty()) this else copy(
        past = (past + present).takeLast(maxEntries),
        present = future.last(),
        future = future.dropLast(1),
    )

    companion object {
        fun initial(recipe: EditRecipe, maxEntries: Int = 100) = EditHistory(
            past = emptyList(), present = recipe, maxEntries = maxEntries,
        )
    }
}

/** The same crop expressed in the frame produced by [geometry] (a Rotate or Flip). */
internal fun EditOperation.Crop.afterGeometry(geometry: EditOperation): EditOperation.Crop = when (geometry) {
    is EditOperation.Flip -> if (geometry.horizontal) {
        EditOperation.Crop(1_000 - rightPermille, topPermille, 1_000 - leftPermille, bottomPermille)
    } else {
        EditOperation.Crop(leftPermille, 1_000 - bottomPermille, rightPermille, 1_000 - topPermille)
    }
    is EditOperation.Rotate -> {
        var crop = this
        repeat((((geometry.degrees % 360) + 360) % 360) / 90) {
            crop = EditOperation.Crop(
                1_000 - crop.bottomPermille,
                crop.leftPermille,
                1_000 - crop.topPermille,
                crop.rightPermille,
            )
        }
        crop
    }
    else -> this
}

private val EditOperation.isFramed: Boolean
    get() = this is EditOperation.Crop || this is EditOperation.Straighten || this is EditOperation.Perspective

/** Maps a normalized point into the frame produced by [geometry] (a Rotate or Flip). */
internal fun mapThroughGeometry(point: NormalizedPoint, geometry: EditOperation): NormalizedPoint = when (geometry) {
    is EditOperation.Flip ->
        if (geometry.horizontal) NormalizedPoint(1f - point.x, point.y) else NormalizedPoint(point.x, 1f - point.y)
    is EditOperation.Rotate -> {
        var mapped = point
        repeat((((geometry.degrees % 360) + 360) % 360) / 90) { mapped = NormalizedPoint(1f - mapped.y, mapped.x) }
        mapped
    }
    else -> point
}

/**
 * The same perspective expressed in the frame produced by [geometry]. Corner order follows the
 * output rectangle, so the corner that fed the output top-left keeps doing so after the remap.
 */
internal fun EditOperation.Perspective.afterGeometry(geometry: EditOperation): EditOperation.Perspective {
    val rect = EditOperation.Perspective.Identity
    val mapped = arrayOfNulls<NormalizedPoint>(4)
    corners.forEachIndexed { index, corner ->
        val target = rect.indexOf(mapThroughGeometry(rect[index], geometry))
        mapped[target] = mapThroughGeometry(corner, geometry)
    }
    return EditOperation.Perspective(mapped.map { it!! })
}

object EditRecipeIds {
    fun forSource(source: MediaKey, generationModified: Long): String =
        "${source.volumeName}:${source.mediaStoreId}:$generationModified"
}

object EditOperationCodec {
    fun encode(operation: EditOperation): String = when (operation) {
        is EditOperation.Crop -> "crop,${operation.leftPermille},${operation.topPermille}," +
            "${operation.rightPermille},${operation.bottomPermille}"
        is EditOperation.Rotate -> "rotate,${operation.degrees}"
        is EditOperation.Flip -> "flip,${if (operation.horizontal) "h" else "v"}"
        is EditOperation.Straighten -> "straighten,${operation.degrees}"
        is EditOperation.Tone -> "tone,${operation.brightness},${operation.contrast},${operation.saturation}"
        is EditOperation.Filter -> "filter,${operation.name}"
        is EditOperation.Adjust -> "adjust,1," + operation.values.entries.sortedBy { it.key.token }
            .joinToString(";") { "${it.key.token}=${it.value}" }
        is EditOperation.ColorMix -> "colormix,1," + ColorMixHue.entries
            .filter { !operation[it].isNeutral }
            .joinToString(";") { hue ->
                val band = operation[hue]
                "${hue.token}=${band.hue}:${band.saturation}:${band.luminance}"
            }
        is EditOperation.Look -> "look,1,${operation.id},${operation.intensity}"
        is EditOperation.Perspective -> "perspective,1," +
            operation.corners.joinToString(",") { "${it.x},${it.y}" }
        is EditOperation.RawDevelop -> buildString {
            val value = operation.settings
            append("raw,1,")
            append(listOf(
                value.exposureEv, value.temperatureKelvin, value.tint, value.highlights,
                value.shadows, value.whites, value.blacks, value.contrast, value.saturation,
                value.vibrance, value.highlightRecovery, value.luminanceNoiseReduction,
                value.chromaNoiseReduction, value.sharpening,
                if (value.chromaticAberrationCorrection) 1 else 0,
                if (value.lensCorrection) 1 else 0,
            ).joinToString(","))
        }
    }

    fun decode(encoded: String): EditOperation {
        val parts = encoded.split(',')
        return when (parts.firstOrNull()) {
            "crop" -> EditOperation.Crop(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt())
            "rotate" -> EditOperation.Rotate(parts[1].toInt())
            "flip" -> EditOperation.Flip(parts[1] == "h")
            "straighten" -> EditOperation.Straighten(parts[1].toFloat())
            "tone" -> EditOperation.Tone(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
            "filter" -> EditOperation.Filter(parts[1])
            "adjust" -> decodeAdjust(parts)
            "colormix" -> decodeColorMix(parts)
            "look" -> {
                require(parts.size == 4 && parts[1] == "1") { "Bad look: $encoded" }
                EditOperation.Look(parts[2], parts[3].toFloat())
            }
            "perspective" -> {
                require(parts.size == 10 && parts[1] == "1") { "Bad perspective: $encoded" }
                EditOperation.Perspective(
                    List(4) { NormalizedPoint(parts[2 + it * 2].toFloat(), parts[3 + it * 2].toFloat()) },
                )
            }
            "raw" -> EditOperation.RawDevelop(
                RawDevelopmentSettings(
                    exposureEv = parts[2].toFloat(),
                    temperatureKelvin = parts[3].toInt(),
                    tint = parts[4].toFloat(),
                    highlights = parts[5].toFloat(),
                    shadows = parts[6].toFloat(),
                    whites = parts[7].toFloat(),
                    blacks = parts[8].toFloat(),
                    contrast = parts[9].toFloat(),
                    saturation = parts[10].toFloat(),
                    vibrance = parts[11].toFloat(),
                    highlightRecovery = parts[12].toFloat(),
                    luminanceNoiseReduction = parts[13].toFloat(),
                    chromaNoiseReduction = parts[14].toFloat(),
                    sharpening = parts[15].toFloat(),
                    chromaticAberrationCorrection = parts[16] == "1",
                    lensCorrection = parts[17] == "1",
                ),
            )
            else -> error("Unknown edit operation: $encoded")
        }
    }

    private fun entries(parts: List<String>): List<List<String>> {
        require(parts.size == 3 && parts[1] == "1") { "Unsupported version" }
        return if (parts[2].isEmpty()) emptyList() else parts[2].split(';').map { it.split('=') }
    }

    private fun decodeAdjust(parts: List<String>): EditOperation.Adjust {
        val values = LinkedHashMap<AdjustKey, Float>()
        entries(parts).forEach { entry ->
            require(entry.size == 2) { "Malformed adjust entry" }
            val key = requireNotNull(AdjustKey.entries.firstOrNull { it.token == entry[0] }) { "Unknown adjust key" }
            require(values.put(key, entry[1].toFloat()) == null) { "Duplicate adjust key" }
        }
        return EditOperation.Adjust(values)
    }

    private fun decodeColorMix(parts: List<String>): EditOperation.ColorMix {
        val bands = MutableList(ColorMixHue.entries.size) { ColorMixBand() }
        val seen = mutableSetOf<ColorMixHue>()
        entries(parts).forEach { entry ->
            require(entry.size == 2) { "Malformed colormix entry" }
            val hue = requireNotNull(ColorMixHue.entries.firstOrNull { it.token == entry[0] }) { "Unknown hue" }
            require(seen.add(hue)) { "Duplicate hue" }
            val values = entry[1].split(':')
            require(values.size == 3) { "Malformed colormix band" }
            bands[hue.ordinal] = ColorMixBand(values[0].toFloat(), values[1].toFloat(), values[2].toFloat())
        }
        return EditOperation.ColorMix(bands)
    }
}

data class PreviewCacheKey(
    val source: MediaKey,
    val sourceGenerationModified: Long,
    val recipeRevision: Int,
    val maxDimension: Int,
) : java.io.Serializable
