package com.librestatic.lightforge

import com.librestatic.lightforge.core.mediastore.MediaActionTarget
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.preferences.FolderSelectionTarget
import com.librestatic.lightforge.core.selection.MediaQuery
import com.librestatic.lightforge.core.selection.SelectionSpec
import java.io.IOException
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.io.Serializable
import java.util.Collections
import java.util.UUID

/** Only app-private SavedState data. This is not an archive or a Java-deserialization endpoint. */
data class CreationSelectionSnapshot(
    val selection: SelectionSpec,
    val targets: List<MediaActionTarget>,
    val count: Long,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

data class CreationVideoSourceSnapshot(
    val uri: String,
    val generationModified: Long,
    val generationAdded: Long,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

data class CreationVideoSnapshot(
    val id: String,
    val title: String?,
    val sources: List<CreationVideoSourceSnapshot>,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

data class CreationCollageSnapshot(
    val id: String,
    val sources: List<CreationVideoSourceSnapshot>,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

data class CreationGifSnapshot(
    val id: String,
    val sources: List<CreationVideoSourceSnapshot>,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/** Current public media identity, not decoded pixels, an editor recipe or an export job. */
data class ViewerRestoreSnapshot(
    val key: MediaKey,
    val kind: MediaKind,
    val generationModified: Long,
    val generationAdded: Long,
    val isTrashed: Boolean,
    val query: MediaQuery,
) : Serializable {
    val identity: String get() = "${key.volumeName}:${key.mediaStoreId}:${kind.name}:$generationModified:$generationAdded:$isTrashed"
    fun matchesSource(key: MediaKey, kind: MediaKind, modified: Long, added: Long, trashed: Boolean): Boolean =
        this.key == key && this.kind == kind && generationModified == modified && generationAdded == added && isTrashed == trashed
    companion object { private const val serialVersionUID = 1L }
}

/**
 * One envelope, one 64 KiB ceiling for selection, video, collage, GIF, current viewer video editor AND manual memories together. A rejected snapshot means
 * omit recovery, never trim/replace the live selection or silently drop one video source.
 * A validated video still requires fresh Room/MediaStore/access checks before rendering.
 */
data class CreationRestoreSnapshot(
    val selection: CreationSelectionSnapshot?,
    val video: CreationVideoSnapshot?,
    val version: Int = CurrentVersion,
    val collage: CreationCollageSnapshot? = null,
    val gif: CreationGifSnapshot? = null,
    val viewer: ViewerRestoreSnapshot? = null,
    val videoEditor: VideoEditorRestoreSnapshot? = null,
    val manualMoment: ManualMomentRestoreSnapshot? = null,
    val externalVideoEditor: ExternalVideoEditorRestoreSnapshot? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
        const val CurrentVersion = 7
        const val MaxSerializedBytes = 64 * 1024
        private val mediaImageUri = Regex("content://media/([A-Za-z0-9_-]{1,128})/images/media/(0|[1-9][0-9]*)")

        fun capture(
            selection: SelectionSpec?,
            targets: Collection<MediaActionTarget> = emptyList(),
            count: Long = 0,
            video: CreationVideoSnapshot? = null,
            collage: CreationCollageSnapshot? = null,
            gif: CreationGifSnapshot? = null,
            viewer: ViewerRestoreSnapshot? = null,
            videoEditor: VideoEditorRestoreSnapshot? = null,
            manualMoment: ManualMomentRestoreSnapshot? = null,
            externalVideoEditor: ExternalVideoEditorRestoreSnapshot? = null,
        ): CreationRestoreSnapshot? = guarded {
            require(selection != null || (targets.isEmpty() && count == 0L))
            require(targets.size <= MaxSerializedBytes)
            validatedOrNull(CreationRestoreSnapshot(
                selection?.let { CreationSelectionSnapshot(it, targets.toList(), count) }, video, collage = collage, gif = gif, viewer = viewer, videoEditor = videoEditor, manualMoment = manualMoment, externalVideoEditor = externalVideoEditor
            ))
        }

        /** Rebuilds collections and reruns invariants: Java restoration bypasses Kotlin init. */
        fun validatedOrNull(raw: Any?): CreationRestoreSnapshot? = guarded {
            val snapshot = raw as? CreationRestoreSnapshot ?: return@guarded null
            require(snapshot.version in 1..CurrentVersion)
            val detached = CreationRestoreSnapshot(
                snapshot.selection?.let(::copySelection), snapshot.video?.let(::copyVideo),
                collage = snapshot.collage?.let(::copyCollage), gif = snapshot.gif?.let(::copyGif), viewer = snapshot.viewer?.let(::copyViewer),
                videoEditor = snapshot.videoEditor?.let { VideoEditorRestoreSnapshot.validatedCopy(it.copy(source = copyViewer(it.source))) },
                manualMoment = snapshot.manualMoment?.let(ManualMomentRestoreSnapshot::validatedCopy),
                externalVideoEditor = snapshot.externalVideoEditor?.let(ExternalVideoEditorRestoreSnapshot::validatedCopy)
            )
            require(detached.videoEditor == null || detached.externalVideoEditor == null)
            require(detached.videoEditor == null || detached.viewer == detached.videoEditor.source)
            if (serializedSizeOrNull(detached) == null) null else detached
        }

        /** Counts the exact Java Serializable envelope, stopping before allocating oversized bytes. */
        fun serializedSizeOrNull(snapshot: CreationRestoreSnapshot): Int? = guarded {
            var count = 0
            val sink = object : OutputStream() {
                override fun write(value: Int) { add(1) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { add(length) }
                private fun add(length: Int) {
                    if (length < 0 || length > MaxSerializedBytes - count) throw IOException("Snapshot exceeds saved-state budget")
                    count += length
                }
            }
            ObjectOutputStream(sink).use { it.writeObject(snapshot) }
            count
        }

        private fun copySelection(value: CreationSelectionSnapshot): CreationSelectionSnapshot {
            require(value.count >= 0 && value.targets.size <= MaxSerializedBytes)
            val targets = value.targets.map { MediaActionTarget(copyKey(it.key), it.kind) }
            require(targets.map { it.key }.distinct().size == targets.size)
            val selection = when (val source = value.selection) {
                is SelectionSpec.Explicit -> {
                    require(source.keys.size <= MaxSerializedBytes)
                    val keys = source.keys.map(::copyKey)
                    require(value.count == keys.size.toLong())
                    require(targets.map { it.key }.toSet() == keys.toSet())
                    SelectionSpec.explicit(keys)
                }
                is SelectionSpec.QueryAll -> {
                    require(source.exclusions.size <= MaxSerializedBytes && targets.isEmpty())
                    SelectionSpec.queryAll(copyQuery(source.querySnapshot), source.exclusions.map(::copyKey))
                }
            }
            return CreationSelectionSnapshot(selection, Collections.unmodifiableList(targets), value.count)
        }

        private fun copyKey(value: MediaKey): MediaKey = MediaKey(value.volumeName, value.mediaStoreId)

        private fun copyQuery(value: MediaQuery): MediaQuery {
            require(value.folderRules.size <= MaxSerializedBytes)
            val scope = when (val source = value.scope) {
                MediaQuery.Scope.Timeline -> MediaQuery.Scope.Timeline
                MediaQuery.Scope.Screenshots -> MediaQuery.Scope.Screenshots
                is MediaQuery.Scope.PhysicalAlbum -> source.copy()
                is MediaQuery.Scope.VirtualAlbum -> source.copy()
                is MediaQuery.Scope.Search -> source.copy()
                is MediaQuery.Scope.LargeVideos -> source.copy()
                is MediaQuery.Scope.BlurryCandidates -> source.copy().also { require(it.maximumScore.isFinite()) }
                is MediaQuery.Scope.ExactDuplicateGroup -> source.copy()
            }
            val rules = value.folderRules.entries.associate { (target, selected) ->
                val copied = when (target) {
                    is FolderSelectionTarget.Path -> target.copy()
                    is FolderSelectionTarget.Bucket -> target.copy()
                }
                copied to selected
            }
            return value.copy(scope = scope, folderRules = Collections.unmodifiableMap(rules))
        }

        private fun copyViewer(value: ViewerRestoreSnapshot): ViewerRestoreSnapshot {
            require(value.generationModified >= 0 && value.generationAdded >= 0)
            require(value.kind == MediaKind.Image || value.kind == MediaKind.Video)
            return value.copy(key = copyKey(value.key), query = copyQuery(value.query))
        }

        private fun copyGif(value: CreationGifSnapshot): CreationGifSnapshot {
            require(value.sources.size in 2..60)
            val checked = copyVideo(CreationVideoSnapshot(value.id, null, value.sources))
            return value.copy(sources = checked.sources)
        }

        private fun copyCollage(value: CreationCollageSnapshot): CreationCollageSnapshot {
            require(value.sources.size in 2..4)
            val checked = copyVideo(CreationVideoSnapshot(value.id, null, value.sources))
            return value.copy(sources = checked.sources)
        }

        private fun copyVideo(value: CreationVideoSnapshot): CreationVideoSnapshot {
            require(UUID.fromString(value.id).toString() == value.id)
            require(value.title == null || value.title.length <= MaxSerializedBytes)
            require(value.sources.size in 1..120)
            val sources = value.sources.map { source ->
                val match = requireNotNull(mediaImageUri.matchEntire(source.uri))
                requireNotNull(match.groupValues[2].toLongOrNull())
                require(source.generationModified >= 0 && source.generationAdded >= 0)
                source.copy()
            }
            require(sources.map { it.uri }.distinct().size == sources.size)
            return value.copy(sources = Collections.unmodifiableList(sources))
        }

        private inline fun <T> guarded(block: () -> T?): T? = try { block() } catch (_: Exception) { null }
    }
}
