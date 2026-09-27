package com.librestatic.lightforge.core.selection

import com.librestatic.lightforge.core.model.MediaKey
import java.io.Serializable

/**
 * Bounded selection representation. QueryAll represents any library size with one query snapshot
 * and only stores explicit exceptions.
 */
sealed interface SelectionSpec : Serializable {
    data class Explicit internal constructor(val keys: Set<MediaKey>) : SelectionSpec

    data class QueryAll internal constructor(
        val querySnapshot: MediaQuery,
        val exclusions: Set<MediaKey>,
    ) : SelectionSpec

    companion object {
        fun explicit(keys: Collection<MediaKey> = emptyList()): Explicit =
            Explicit(keys.toSet())

        fun queryAll(
            querySnapshot: MediaQuery,
            exclusions: Collection<MediaKey> = emptyList(),
        ): QueryAll = QueryAll(querySnapshot, exclusions.toSet())
    }
}

/** Stateless transitions keep ViewModels and saved-state integration straightforward. */
object SelectionReducer {
    fun selectAll(query: MediaQuery): SelectionSpec = SelectionSpec.queryAll(query)

    fun clear(): SelectionSpec = SelectionSpec.explicit()

    fun toggle(
        selection: SelectionSpec,
        key: MediaKey,
        belongsToQuerySnapshot: Boolean = true,
    ): SelectionSpec = when (selection) {
        is SelectionSpec.Explicit -> {
            val next = selection.keys.toMutableSet()
            if (!next.add(key)) next.remove(key)
            SelectionSpec.explicit(next)
        }
        is SelectionSpec.QueryAll -> {
            if (!belongsToQuerySnapshot) return selection
            val next = selection.exclusions.toMutableSet()
            if (!next.add(key)) next.remove(key)
            SelectionSpec.queryAll(selection.querySnapshot, next)
        }
    }

    fun setSelected(
        selection: SelectionSpec,
        key: MediaKey,
        selected: Boolean,
        belongsToQuerySnapshot: Boolean = true,
    ): SelectionSpec {
        val current = isSelected(selection, key, belongsToQuerySnapshot)
        return if (current == selected) selection else {
            toggle(selection, key, belongsToQuerySnapshot)
        }
    }

    fun isSelected(
        selection: SelectionSpec,
        key: MediaKey,
        belongsToQuerySnapshot: Boolean = true,
    ): Boolean = when (selection) {
        is SelectionSpec.Explicit -> key in selection.keys
        is SelectionSpec.QueryAll -> belongsToQuerySnapshot && key !in selection.exclusions
    }

    fun count(selection: SelectionSpec, queryMatchCount: Long? = null): Long = when (selection) {
        is SelectionSpec.Explicit -> selection.keys.size.toLong()
        is SelectionSpec.QueryAll -> {
            requireNotNull(queryMatchCount) { "QueryAll count requires its query snapshot count" }
            (queryMatchCount - selection.exclusions.size).coerceAtLeast(0)
        }
    }
}
