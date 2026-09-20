package com.ugallery.app

import com.ugallery.core.data.ManualMomentDraft
import com.ugallery.core.data.ManualMomentSource
import com.ugallery.core.mediastore.MediaActionTarget
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.selection.SelectionSpec
import java.io.Serializable
import java.util.Collections
import java.util.UUID

data class ManualMomentSourceSnapshot(
    val key: MediaKey,
    val generationAdded: Long,
    val generationModified: Long,
    val requiresSpecialMedia: Boolean,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/** Exact prepared identity, not a committed-memory receipt or a request to save automatically. */
data class ManualMomentRestoreSnapshot(
    val id: String,
    val sources: List<ManualMomentSourceSnapshot>,
    val originSelection: CreationSelectionSnapshot? = null,
) : Serializable {
    fun toDraft(): ManualMomentDraft {
        val checked = validatedCopy(this)
        return ManualMomentDraft(checked.id, Collections.unmodifiableList(checked.sources.map {
            ManualMomentSource(MediaKey(it.key.volumeName, it.key.mediaStoreId),
                it.generationAdded, it.generationModified, it.requiresSpecialMedia)
        }))
    }

    companion object {
        private const val serialVersionUID = 1L

        fun capture(
            draft: ManualMomentDraft,
            originSelection: CreationSelectionSnapshot?,
        ): ManualMomentRestoreSnapshot = validatedCopy(ManualMomentRestoreSnapshot(
            draft.id,
            draft.sources.map { ManualMomentSourceSnapshot(it.key, it.generationAdded,
                it.generationModified, it.requiresSpecialMedia) },
            originSelection,
        ))

        /** Reruns validation after SavedState deserialization, which bypasses Kotlin constructors. */
        fun validatedCopy(value: ManualMomentRestoreSnapshot): ManualMomentRestoreSnapshot {
            require(UUID.fromString(value.id).toString() == value.id)
            require(value.sources.size in 1..120)
            val sources = value.sources.map { source ->
                require(source.generationAdded >= 0 && source.generationModified >= 0)
                source.copy(key = MediaKey(source.key.volumeName, source.key.mediaStoreId))
            }
            val keys = sources.map { it.key }
            require(keys.distinct().size == keys.size)
            val origin = value.originSelection?.let { selection ->
                val checked = requireNotNull(CreationRestoreSnapshot.capture(
                    selection.selection, selection.targets, selection.count,
                )?.selection)
                val explicit = requireNotNull(checked.selection as? SelectionSpec.Explicit)
                require(explicit.keys.toList() == keys)
                require(checked.targets.all { it.kind == MediaKind.Image })
                checked
            }
            return ManualMomentRestoreSnapshot(value.id, Collections.unmodifiableList(sources), origin)
        }
    }
}

/** Only the originating Photos selection may be consumed by this manual-memory commit. */
internal fun manualMomentOwnsSelection(
    origin: CreationSelectionSnapshot?,
    originRevision: Long?,
    currentSelection: SelectionSpec,
    currentTargets: List<MediaActionTarget>,
    currentCount: Long,
    currentRevision: Long,
): Boolean = origin != null && originRevision == currentRevision &&
    origin.selection == currentSelection && origin.targets == currentTargets &&
    origin.count == currentCount
