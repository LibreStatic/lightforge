package com.librestatic.lightforge

import com.librestatic.lightforge.core.editing.video.VideoAnnotationLayer
import com.librestatic.lightforge.core.editing.video.VideoEditRecipe
import com.librestatic.lightforge.core.editing.video.VideoEditRecipeCodec
import com.librestatic.lightforge.core.model.MediaKind
import java.io.Serializable
import java.io.StringReader
import java.io.StringWriter
import java.util.Collections
import java.util.Properties
import java.util.UUID

/** Bounded review state only: restoring it never writes a recipe, starts tracking or enqueues export. */
data class VideoEditorRestoreSnapshot(
    override val id: String,
    val source: ViewerRestoreSnapshot,
    override val durationMillis: Long,
    override val recipe: String,
    override val baselineRecipe: String,
    override val positionMillis: Long,
    override val pendingExportRecipe: String? = null,
    override val exportJobId: String? = null,
    override val selectedAnnotationId: String? = null,
    override val selectedSlowMotionSegmentId: String? = null,
    override val slowMotionMarkInMillis: Long? = null,
    override val annotationTrackingCorrectionMillis: Long? = null,
    override val undoAnnotations: List<String> = emptyList(),
    override val redoAnnotations: List<String> = emptyList(),
) : VideoEditorDraftRestore {
    companion object {
        private const val serialVersionUID = 1L
        private const val MaximumBytes = 64 * 1024
        // VideoAnnotationCodec's canonical empty list; public recipe codec omits this property.
        private const val EmptyAnnotations = "AAAAAQAAAAA="

        fun validatedCopy(value: VideoEditorRestoreSnapshot): VideoEditorRestoreSnapshot {
            require(UUID.fromString(value.id).toString() == value.id)
            require(value.durationMillis > 0)
            require(value.source.kind == MediaKind.Video && !value.source.isTrashed)
            require(value.source.generationModified >= 0 && value.source.generationAdded >= 0)
            require(value.undoAnnotations.size <= 100 && value.redoAnnotations.size <= 100)
            // Check every encoded input together before invoking any permissive downstream decoder.
            boundedText(listOfNotNull(value.id, value.recipe, value.baselineRecipe,
                value.pendingExportRecipe, value.exportJobId, value.selectedAnnotationId,
                value.selectedSlowMotionSegmentId) + value.undoAnnotations + value.redoAnnotations)
            val current = decodeRecipeExact(value.recipe)
            val baseline = decodeRecipeExact(value.baselineRecipe)
            validateDuration(current, value.durationMillis)
            validateDuration(baseline, value.durationMillis)
            value.pendingExportRecipe?.let { validateDuration(decodeRecipeExact(it), value.durationMillis) }
            require(value.exportJobId == null || UUID.fromString(value.exportJobId).toString() == value.exportJobId)
            require(value.pendingExportRecipe == null || value.exportJobId != null)
            val end = current.endMillis ?: value.durationMillis
            require(value.positionMillis in current.startMillis until end)
            require(value.selectedAnnotationId == null || current.annotations.any { it.id == value.selectedAnnotationId })
            require(value.selectedSlowMotionSegmentId == null || current.slowMotionSegments.any { it.id == value.selectedSlowMotionSegmentId })
            require(value.slowMotionMarkInMillis == null || value.slowMotionMarkInMillis in current.startMillis until end)
            require(value.annotationTrackingCorrectionMillis == null || value.annotationTrackingCorrectionMillis in current.startMillis until end)
            fun history(entries: List<String>): List<String> = Collections.unmodifiableList(entries.map {
                val layers = decodeAnnotationsExact(it)
                require(layers.all { layer -> layer.startMillis >= 0 && layer.endMillis <= value.durationMillis })
                it
            })
            // Reuse the envelope's detached MediaKey/query validation without another editor entry.
            val detachedSource = requireNotNull(CreationRestoreSnapshot.capture(selection = null, viewer = value.source)?.viewer)
            return value.copy(source = detachedSource, undoAnnotations = history(value.undoAnnotations),
                redoAnnotations = history(value.redoAnnotations))
        }

        fun encodeRecipeExact(recipe: VideoEditRecipe): String {
            val encoded = VideoEditRecipeCodec.encode(recipe)
            require(decodeRecipeExact(encoded) == recipe) { "Recipe would lose information during restoration" }
            return encoded
        }

        fun decodeRecipeExact(encoded: String): VideoEditRecipe {
            boundedText(listOf(encoded))
            val original = properties(encoded)
            require(original.getProperty("version") == "5") { "Recovery requires the complete current recipe format" }
            val decoded = VideoEditRecipeCodec.decode(encoded)
            require(original == properties(VideoEditRecipeCodec.encode(decoded))) { "Recipe is malformed, incomplete or noncanonical" }
            validateLayerIds(decoded.annotations)
            require(decoded.slowMotionSegments.all { it.id.isNotBlank() })
            require(decoded.slowMotionSegments.map { it.id }.distinct().size == decoded.slowMotionSegments.size)
            return decoded.copy(
                colorGrade = decoded.colorGrade.copy(hueBands = Collections.unmodifiableList(decoded.colorGrade.hueBands.toList())),
                slowMotionSegments = Collections.unmodifiableList(decoded.slowMotionSegments.toList()),
                annotations = immutableLayers(decoded.annotations),
            )
        }

        /** Public codec carrier avoids exposing the editing module's internal annotation codec. */
        fun encodeAnnotationsExact(layers: List<VideoAnnotationLayer>): String {
            val carrier = VideoEditRecipe(annotations = layers)
            val encoded = properties(encodeRecipeExact(carrier)).getProperty("annotations", EmptyAnnotations)
            require(decodeAnnotationsExact(encoded) == layers)
            return encoded
        }

        fun decodeAnnotationsExact(encoded: String): List<VideoAnnotationLayer> {
            boundedText(listOf(encoded))
            val carrier = properties(VideoEditRecipeCodec.encode(VideoEditRecipe())).apply {
                setProperty("annotations", encoded)
            }
            val text = StringWriter().also { carrier.store(it, null) }.toString()
            val layers = VideoEditRecipeCodec.decode(text).annotations
            val canonical = properties(VideoEditRecipeCodec.encode(VideoEditRecipe(annotations = layers)))
                .getProperty("annotations", EmptyAnnotations)
            require(canonical == encoded) { "Annotation history is malformed or contains trailing bytes" }
            validateLayerIds(layers)
            return immutableLayers(layers)
        }

        private fun immutableLayers(layers: List<VideoAnnotationLayer>): List<VideoAnnotationLayer> =
            Collections.unmodifiableList(layers.map { layer -> layer.copy(
                points = Collections.unmodifiableList(layer.points.toList()),
                keyframes = Collections.unmodifiableList(layer.keyframes.toList()),
            ) })

        private fun validateDuration(recipe: VideoEditRecipe, duration: Long) {
            val end = recipe.endMillis ?: duration
            require(recipe.startMillis in 0 until duration && end in (recipe.startMillis + 1)..duration)
            require(recipe.slowMotionSegments.all { it.startMillis >= recipe.startMillis && it.endMillis <= end })
            require(recipe.annotations.all { it.startMillis >= recipe.startMillis && it.endMillis <= end })
        }

        private fun validateLayerIds(layers: List<VideoAnnotationLayer>) {
            require(layers.all { it.id.isNotBlank() })
            require(layers.map { it.id }.distinct().size == layers.size)
        }

        private fun boundedText(values: List<String>) {
            var remaining = MaximumBytes
            values.forEach {
                require(it.length <= remaining) { "Recovery text exceeds its budget" }
                val bytes = it.toByteArray(Charsets.UTF_8).size
                require(bytes <= remaining) { "Recovery text exceeds its budget" }
                remaining -= bytes
            }
        }

        private fun properties(encoded: String): Properties = object : Properties() {
            override fun put(key: Any, value: Any): Any? {
                require(!containsKey(key)) { "Duplicate recipe property" }
                return super.put(key, value)
            }
        }.apply { load(StringReader(encoded)) }
    }
}
