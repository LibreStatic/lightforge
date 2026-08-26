package com.ugallery.core.model

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
}

data class EditRecipe(
    val recipeId: String,
    val source: MediaKey,
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
            is EditOperation.Crop -> replaceSlot<EditOperation.Crop>(operation)
            is EditOperation.Straighten -> replaceSlot<EditOperation.Straighten>(
                operation.takeUnless { it.degrees == 0f },
            )
            is EditOperation.RawDevelop -> replaceSlot<EditOperation.RawDevelop>(operation)
            is EditOperation.Rotate, is EditOperation.Flip -> present.operations + operation
        }
        if (operations == present.operations) return this
        return copy(
            past = (past + present).takeLast(maxEntries),
            present = present.copy(operations = operations, revision = present.revision + 1),
            future = emptyList(),
        )
    }

    private inline fun <reified T : EditOperation> replaceSlot(replacement: T?): List<EditOperation> {
        val firstIndex = present.operations.indexOfFirst { it is T }
        if (firstIndex < 0) return if (replacement == null) present.operations else present.operations + replacement
        return buildList {
            present.operations.forEachIndexed { index, existing ->
                when {
                    index == firstIndex && replacement != null -> add(replacement)
                    existing !is T -> add(existing)
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
}

data class PreviewCacheKey(
    val source: MediaKey,
    val sourceGenerationModified: Long,
    val recipeRevision: Int,
    val maxDimension: Int,
) : java.io.Serializable
