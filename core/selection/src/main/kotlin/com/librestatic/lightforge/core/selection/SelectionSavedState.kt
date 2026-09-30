package com.librestatic.lightforge.core.selection

import com.librestatic.lightforge.core.model.MediaKey
import java.io.Serializable

/** Serializable, versioned state suitable for SavedStateHandle without retaining library IDs. */
data class SelectionSavedState(
    val version: Int = CurrentVersion,
    val explicitKeys: List<SavedMediaKey>? = null,
    val queryAll: SavedQueryAll? = null,
) : Serializable {
    init {
        require((explicitKeys == null) xor (queryAll == null)) { "Exactly one selection mode is required" }
    }

    companion object {
        const val CurrentVersion = 1
    }
}

data class SavedMediaKey(val volumeName: String, val mediaStoreId: Long) : Serializable

data class SavedQueryAll(
    val query: MediaQuery,
    val exclusions: List<SavedMediaKey>,
) : Serializable

object SelectionStateCodec {
    fun save(selection: SelectionSpec): SelectionSavedState = when (selection) {
        is SelectionSpec.Explicit -> SelectionSavedState(
            explicitKeys = selection.keys.sortedWith(MediaKeyOrder).map { it.saved() },
        )
        is SelectionSpec.QueryAll -> SelectionSavedState(
            queryAll = SavedQueryAll(
                query = selection.querySnapshot,
                exclusions = selection.exclusions.sortedWith(MediaKeyOrder).map { it.saved() },
            ),
        )
    }

    fun restore(state: SelectionSavedState): SelectionSpec {
        require(state.version == SelectionSavedState.CurrentVersion) {
            "Unsupported selection state version ${state.version}"
        }
        return state.explicitKeys?.let { keys -> SelectionSpec.explicit(keys.map { it.mediaKey() }) }
            ?: state.queryAll!!.let { saved ->
                SelectionSpec.queryAll(saved.query, saved.exclusions.map { it.mediaKey() })
            }
    }

    private fun MediaKey.saved() = SavedMediaKey(volumeName, mediaStoreId)
    private fun SavedMediaKey.mediaKey() = MediaKey(volumeName, mediaStoreId)

    private val MediaKeyOrder = compareBy<MediaKey>({ it.volumeName }, { it.mediaStoreId })
}
