package com.ugallery.core.selection

import com.ugallery.core.model.MediaKey

/** Keyset-paged source. A page must be strictly after [afterExclusive]. */
fun interface SelectionKeySource {
    suspend fun page(
        query: MediaQuery,
        afterExclusive: MediaKey?,
        limit: Int,
    ): List<MediaKey>
}

class SelectionChunker(private val source: SelectionKeySource) {
    suspend fun forEachChunk(
        selection: SelectionSpec,
        chunkSize: Int,
        action: suspend (List<MediaKey>) -> Unit,
    ) {
        require(chunkSize > 0)
        when (selection) {
            is SelectionSpec.Explicit -> selection.keys
                .sortedWith(MediaKeyOrder)
                .chunked(chunkSize)
                .forEach { action(it) }
            is SelectionSpec.QueryAll -> streamQueryAll(selection, chunkSize, action)
        }
    }

    private suspend fun streamQueryAll(
        selection: SelectionSpec.QueryAll,
        chunkSize: Int,
        action: suspend (List<MediaKey>) -> Unit,
    ) {
        var after: MediaKey? = null
        val output = ArrayList<MediaKey>(chunkSize)
        while (true) {
            val page = source.page(selection.querySnapshot, after, chunkSize)
            if (page.isEmpty()) break
            validatePage(page, after)
            for (key in page) {
                if (key !in selection.exclusions) {
                    output += key
                    if (output.size == chunkSize) {
                        action(output.toList())
                        output.clear()
                    }
                }
            }
            after = page.last()
        }
        if (output.isNotEmpty()) action(output.toList())
    }

    private fun validatePage(page: List<MediaKey>, after: MediaKey?) {
        var previous = after
        page.forEach { key ->
            require(previous == null || MediaKeyOrder.compare(previous!!, key) < 0) {
                "Selection source must return unique keys in strict keyset order"
            }
            previous = key
        }
    }

    private companion object {
        val MediaKeyOrder = compareBy<MediaKey>({ it.volumeName }, { it.mediaStoreId })
    }
}
