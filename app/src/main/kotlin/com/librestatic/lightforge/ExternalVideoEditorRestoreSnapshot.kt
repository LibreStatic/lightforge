package com.librestatic.lightforge

import com.librestatic.lightforge.core.editing.video.VideoEditRecipe
import java.io.IOException
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.io.Serializable
import java.net.URI
import java.util.Collections
import java.util.UUID

/** Observed provider identity. Recovery must freshly check access and these bytes before opening. */
data class ExternalVideoSourceSnapshot(
    val uriString: String,
    val mimeType: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val sizeBytes: Long,
    val sha256: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        fun validatedCopy(value: ExternalVideoSourceSnapshot): ExternalVideoSourceSnapshot {
            externalVideoBoundedText(listOf(value.uriString, value.mimeType, value.displayName, value.sha256))
            val uri = URI(value.uriString)
            require(uri.scheme == "content" && !uri.isOpaque)
            require(!uri.rawAuthority.isNullOrBlank() && uri.rawAuthority.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")))
            require(!uri.rawPath.isNullOrEmpty() && uri.rawPath.startsWith("/") && uri.rawFragment == null)
            require(value.mimeType == "video/*" || value.mimeType.matches(Regex("video/[a-z0-9][a-z0-9!#$&^_.+\\-]*")))
            require(value.displayName.isNotBlank() && '\u0000' !in value.displayName)
            require(value.width > 0 && value.height > 0 && value.durationMillis > 0 && value.sizeBytes > 0)
            require(value.sha256.matches(Regex("[0-9a-f]{64}")))
            return value.copy().also(::externalVideoCheckSerializedSize)
        }
    }
}

/** App-private SavedState only. No URI grant, export, tracking job or playback is started by decoding. */
data class ExternalVideoEditorRestoreSnapshot(
    override val id: String,
    val source: ExternalVideoSourceSnapshot,
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
        const val MaxSerializedBytes = 64 * 1024

        fun validatedCopy(value: ExternalVideoEditorRestoreSnapshot): ExternalVideoEditorRestoreSnapshot {
            require(UUID.fromString(value.id).toString() == value.id)
            require(value.durationMillis > 0 && value.durationMillis == value.source.durationMillis)
            require(value.undoAnnotations.size <= 100 && value.redoAnnotations.size <= 100)
            externalVideoBoundedText(listOfNotNull(value.id, value.recipe, value.baselineRecipe, value.pendingExportRecipe,
                value.exportJobId, value.selectedAnnotationId, value.selectedSlowMotionSegmentId,
                value.source.uriString, value.source.mimeType, value.source.displayName, value.source.sha256) +
                value.undoAnnotations + value.redoAnnotations)
            val source = ExternalVideoSourceSnapshot.validatedCopy(value.source)
            val current = VideoEditorRestoreSnapshot.decodeRecipeExact(value.recipe)
            validateDuration(current, value.durationMillis)
            validateDuration(VideoEditorRestoreSnapshot.decodeRecipeExact(value.baselineRecipe), value.durationMillis)
            value.pendingExportRecipe?.let { validateDuration(VideoEditorRestoreSnapshot.decodeRecipeExact(it), value.durationMillis) }
            require(value.exportJobId == null || UUID.fromString(value.exportJobId).toString() == value.exportJobId)
            require(value.pendingExportRecipe == null || value.exportJobId != null)
            val end = current.endMillis ?: value.durationMillis
            require(value.positionMillis in current.startMillis until end)
            require(value.selectedAnnotationId == null || current.annotations.any { it.id == value.selectedAnnotationId })
            require(value.selectedSlowMotionSegmentId == null || current.slowMotionSegments.any { it.id == value.selectedSlowMotionSegmentId })
            require(value.slowMotionMarkInMillis == null || value.slowMotionMarkInMillis in current.startMillis until end)
            require(value.annotationTrackingCorrectionMillis == null || value.annotationTrackingCorrectionMillis in current.startMillis until end)
            fun history(entries: List<String>): List<String> = Collections.unmodifiableList(entries.map { encoded ->
                val layers = VideoEditorRestoreSnapshot.decodeAnnotationsExact(encoded)
                require(layers.all { it.startMillis >= 0 && it.endMillis <= value.durationMillis })
                encoded
            })
            return value.copy(source = source, undoAnnotations = history(value.undoAnnotations),
                redoAnnotations = history(value.redoAnnotations)).also(::externalVideoCheckSerializedSize)
        }

        fun validatedOrNull(raw: Any?): ExternalVideoEditorRestoreSnapshot? = try {
            (raw as? ExternalVideoEditorRestoreSnapshot)?.let(::validatedCopy)
        } catch (_: RuntimeException) { null
        } catch (_: IOException) { null
        } catch (_: java.net.URISyntaxException) { null }

        private fun validateDuration(recipe: VideoEditRecipe, duration: Long) {
            val end = recipe.endMillis ?: duration
            require(recipe.startMillis in 0 until duration && end in (recipe.startMillis + 1)..duration)
            require(recipe.annotations.all { it.startMillis >= recipe.startMillis && it.endMillis <= end })
            require(recipe.slowMotionSegments.all { it.startMillis >= recipe.startMillis && it.endMillis <= end })
        }
    }
}

private fun externalVideoBoundedText(values: List<String>) {
    var remaining = ExternalVideoEditorRestoreSnapshot.MaxSerializedBytes
    values.forEach {
        require(it.length <= remaining) { "External editor input exceeds its budget" }
        val bytes = it.toByteArray(Charsets.UTF_8).size
        require(bytes <= remaining) { "External editor input exceeds its budget" }
        remaining -= bytes
    }
}

/** Count actual Java serialization, including metadata/collection overhead, without an oversized buffer. */
private fun externalVideoCheckSerializedSize(value: Serializable) {
    val bounded = object : OutputStream() {
        var remaining = ExternalVideoEditorRestoreSnapshot.MaxSerializedBytes
        override fun write(b: Int) { require(remaining > 0) { "External editor serialization exceeds its budget" }; --remaining }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length <= remaining) { "External editor serialization exceeds its budget" }
            remaining -= length
        }
    }
    ObjectOutputStream(bounded).use { it.writeObject(value) }
}

/** Opening-session identity stays authoritative even after pending has been consumed by the loader. */
internal fun externalVideoDraftIsRestoring(
    snapshot: ExternalVideoEditorRestoreSnapshot?, sessionId: String?, opening: Boolean, uriString: String? = null,
): Boolean = opening && snapshot != null && snapshot.id == sessionId &&
    (uriString == null || snapshot.source.uriString == uriString)
