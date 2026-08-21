package com.ugallery.feature.viewer

import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.selection.MediaQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ViewerPosition(
    val sourceQuery: MediaQuery,
    val current: MediaKey,
)

data class ViewerUiState(
    val items: List<TimelineMedia> = emptyList(),
    val currentIndex: Int = -1,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val isLoading: Boolean = false,
) {
    val current: TimelineMedia? get() = items.getOrNull(currentIndex)
}

enum class NeighborDirection { Previous, Next }

fun interface ViewerNeighborSource {
    suspend fun neighbor(
        sourceQuery: MediaQuery,
        current: MediaKey,
        direction: NeighborDirection,
    ): MediaKey?
}

/** Navigation always resolves against the query captured when the viewer was opened. */
class ViewerNavigationController(
    sourceQuery: MediaQuery,
    anchor: MediaKey,
    private val neighbors: ViewerNeighborSource,
) {
    private val mutex = Mutex()
    private val mutablePosition = MutableStateFlow(ViewerPosition(sourceQuery, anchor))
    val position: StateFlow<ViewerPosition> = mutablePosition

    suspend fun move(direction: NeighborDirection): Boolean = mutex.withLock {
        val current = mutablePosition.value
        val neighbor = neighbors.neighbor(current.sourceQuery, current.current, direction)
            ?: return false
        mutablePosition.value = current.copy(current = neighbor)
        true
    }
}
