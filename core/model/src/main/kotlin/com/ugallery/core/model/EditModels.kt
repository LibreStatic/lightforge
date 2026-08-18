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

    fun apply(operation: EditOperation): EditHistory = copy(
        past = (past + present).takeLast(maxEntries),
        present = present.append(operation),
        future = emptyList(),
    )

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
        is EditOperation.Tone -> "tone,${operation.brightness},${operation.contrast},${operation.saturation}"
        is EditOperation.Filter -> "filter,${operation.name}"
    }

    fun decode(encoded: String): EditOperation {
        val parts = encoded.split(',')
        return when (parts.firstOrNull()) {
            "crop" -> EditOperation.Crop(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt())
            "rotate" -> EditOperation.Rotate(parts[1].toInt())
            "flip" -> EditOperation.Flip(parts[1] == "h")
            "tone" -> EditOperation.Tone(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
            "filter" -> EditOperation.Filter(parts[1])
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
