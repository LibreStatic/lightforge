package com.librestatic.lightforge.core.thumbnail

import com.librestatic.lightforge.core.thumbnail.ThumbnailLoadPriority.Prefetch
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoadPriority.Visible
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailDecodeOrderTest {
    private data class Task(val priority: ThumbnailLoadPriority, val sequence: Long)

    private fun order(vararg tasks: Task): List<Long> = tasks.sortedWith { a, b ->
        ThumbnailLoader.compareOrder(a.priority, a.sequence, b.priority, b.sequence)
    }.map { it.sequence }

    @Test
    fun newestVisibleRequestRunsFirst() {
        assertEquals(listOf(3L, 2L, 1L), order(Task(Visible, 1), Task(Visible, 2), Task(Visible, 3)))
    }

    @Test
    fun prefetchKeepsPlanningOrderBehindAllVisibleWork() {
        assertEquals(
            listOf(5L, 1L, 2L, 4L),
            order(Task(Prefetch, 2), Task(Visible, 1), Task(Prefetch, 4), Task(Visible, 5)),
        )
    }
}
