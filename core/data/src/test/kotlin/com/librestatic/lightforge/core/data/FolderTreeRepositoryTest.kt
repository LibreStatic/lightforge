package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.database.FolderSummaryRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FolderTreeRepositoryTest {
    private fun row(
        path: String?, bucket: Long, count: Long = 1, bytes: Long = 10, min: Long = 1, max: Long = 1,
        cover: Long? = bucket, volume: String = "external_primary", name: String? = null, images: Long = count,
    ) = FolderSummaryRow(volume, path, bucket, name ?: path?.trimEnd('/')?.substringAfterLast('/'), count, images, count - images, bytes, min, max, cover, true)

    @Test fun aggregatesSubtreesAndSortsNaturally() {
        val tree = buildFolderTree(
            listOf(
                row("DCIM/Camera/", 1, count = 3, bytes = 300, min = 100, max = 900, cover = 31, images = 2),
                row("DCIM/", 2, count = 1, bytes = 50, min = 10, max = 20, cover = 21),
                row("Pictures/img10/", 3), row("Pictures/img2/", 4),
                row("Movies/", 5, volume = "1234-ABCD"),
            ),
        )
        assertEquals(listOf("external_primary", "1234-ABCD"), tree.volumes.map { it.name })
        val primary = tree.volumes.first()
        assertEquals(listOf("DCIM", "Pictures"), primary.children.map { it.name })
        val dcim = tree.find("external_primary", "DCIM/")!!
        val totals = tree.totals(dcim)
        assertEquals(4L, totals.totalCount)
        assertEquals(350L, totals.totalBytes)
        assertEquals(3L, totals.imageCount)
        assertEquals(1L, totals.videoCount)
        assertEquals(10L, totals.minSortMillis)
        assertEquals(900L, totals.maxSortMillis)
        assertEquals(31L, totals.coverMediaStoreId)
        // Pictures is synthetic: its totals still roll up.
        val pictures = tree.find("external_primary", "Pictures/")!!
        assertNull(pictures.direct)
        assertEquals(2L, tree.totals(pictures).totalCount)
        assertEquals(listOf("img2", "img10"), pictures.children.map { it.name })
        assertEquals(6L, tree.totals(primary).totalCount)
        assertEquals(1L, tree.totals(tree.volumes.last()).totalCount)
    }

    @Test fun pathlessFoldersAreKeptPerBucket() {
        val tree = buildFolderTree(listOf(row(null, 7, name = "Loose"), row(null, 8, name = "Other")))
        val root = tree.volumes.single()
        assertEquals(listOf("Loose", "Other"), root.children.map { it.name })
        assertEquals(1L, tree.totals(root.children[1]).totalCount)
        assertEquals(2L, tree.totals(root).totalCount)
    }

    @Test fun debouncesBurstsButNotTheFirstEmission() = runTest {
        val upstream = flow {
            emit(listOf(row("A/", 1)))
            delay(10)
            emit(listOf(row("A/", 1), row("B/", 2)))
            delay(10)
            emit(listOf(row("A/", 1), row("B/", 2), row("C/", 3)))
        }
        val emitted = observeFolderTree(upstream, 300).toList()
        // First state is immediate; the two rapid follow-ups collapse into the last one.
        assertEquals(listOf(1, 3), emitted.map { it.volumes.single().children.size })
        assertEquals(true, currentTime >= 320)
    }
}
