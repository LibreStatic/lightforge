package com.librestatic.lightforge.core.model.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderForestTest {
    private data class Item(val volume: String, val path: String?, val bucket: Long, val count: Long = 1, val name: String = "b$bucket")

    private fun forest(vararg items: Item) = buildFolderForest(
        items.toList(), { it.volume }, { it.path }, { it.bucket.toString() }, { it.name },
    )

    private val primary = "external_primary"

    @Test fun buildsSyntheticParentsAndNormalisesPaths() {
        val roots = forest(Item(primary, "DCIM\\Camera", 1), Item(primary, "/DCIM/Camera/Raw//", 2))
        val dcim = roots.single()
        assertEquals("DCIM/", dcim.relativePath)
        assertNull(dcim.direct)
        val camera = dcim.children.single()
        assertEquals(1L, camera.direct?.bucket)
        assertEquals("DCIM/Camera/Raw/", camera.children.single().relativePath)
        assertEquals(listOf(1L, 2L), dcim.allDirect.map { it.bucket })
    }

    @Test fun pathlessItemsAreRootsPerVolumeAndBucket() {
        val roots = forest(Item(primary, null, 7), Item(primary, " ", 8), Item("sd", null, 7))
        assertEquals(3, roots.size)
        assertTrue(roots.all { it.relativePath == null && it.direct != null })
    }

    @Test fun primaryVolumeFirstThenVolumeThenNameIgnoringCase() {
        val roots = forest(Item("zzz", "A/", 1), Item("aaa", "B/", 2), Item(primary, "b/", 3), Item(primary, "A/", 4))
        assertEquals(listOf(primary to "A", primary to "b", "aaa" to "B", "zzz" to "A"), roots.map { it.volumeName to it.name })
    }

    @Test fun aggregateFindAndBreadcrumb() {
        val roots = forest(Item(primary, "DCIM/", 1, 2), Item(primary, "DCIM/Camera/", 2, 3), Item(primary, "Pictures/", 3, 10))
        assertEquals(5L, roots.first().aggregate(0L, { it.count }, Long::plus))
        assertEquals(2L, roots.find(primary, "DCIM/Camera/")?.direct?.bucket)
        assertNull(roots.find("sd", "DCIM/"))
        assertEquals(listOf(FolderCrumb("DCIM", "DCIM/"), FolderCrumb("Camera", "DCIM/Camera/")), breadcrumb("DCIM/Camera/"))
        assertTrue(breadcrumb(null).isEmpty())
    }

    @Test fun compactsOnlyPureSingleChildChains() {
        val roots = forest(
            Item(primary, "Android/media/com.whatsapp/WhatsApp/Media/", 1),
            Item(primary, "Pictures/", 2),
            Item(primary, "Pictures/Sub/", 3),
            Item(primary, "Pictures/Sub/Deep/", 4),
            Item(primary, "Multi/a/", 5),
            Item(primary, "Multi/b/", 6),
        ).compactSingleChildChains()
        val android = roots.single { it.name.startsWith("Android") }
        assertEquals("Android/media/com.whatsapp/WhatsApp/Media", android.name)
        assertEquals("Android/media/com.whatsapp/WhatsApp/Media/", android.relativePath)
        assertEquals(1L, android.direct?.bucket)
        // Pictures and Sub have direct items, so nothing merges into or below them.
        val pictures = roots.single { it.name == "Pictures" }
        assertEquals("Sub", pictures.children.single().name)
        assertEquals("Deep", pictures.children.single().children.single().name)
        // Branching parents are not merged.
        assertEquals(listOf("a", "b"), roots.single { it.name == "Multi" }.children.map { it.name })
    }

    @Test fun naturalOrderSortsDigitRunsNumerically() {
        val sorted = listOf("img10", "IMG2", "img1", "a", "img02", "B").sortedWith(NaturalNameOrder)
        assertEquals(listOf("a", "B", "img1", "img02", "IMG2", "img10"), sorted)
        assertTrue(NaturalNameOrder.compare("x", "x") == 0)
    }

    @Test fun treeTotalsRollUpWithNewestCover() {
        fun stats(n: Long, max: Long, cover: Long, bucket: Long = n) =
            FolderStats(bucket, n, n, 0, n * 10, max - 5, max, cover, true)
        val forest = listOf(
            FolderNode("v", "A/", "A", null, listOf(
                FolderNode("v", "A/B/", "B", stats(2, 100, 20), emptyList()),
                FolderNode("v", "A/C/", "C", stats(3, 300, 30), emptyList()),
            )),
            FolderNode("v", null, "loose", stats(1, 50, 5, bucket = 9), emptyList()),
        )
        val tree = FolderTree.from(forest)
        val root = tree.volumes.single()
        val rootTotals = tree.totals(root)
        assertEquals(6L, rootTotals.totalCount)
        assertEquals(60L, rootTotals.totalBytes)
        assertEquals(30L, rootTotals.coverMediaStoreId)
        assertEquals(45L, rootTotals.minSortMillis)
        assertEquals(300L, rootTotals.maxSortMillis)
        val a = tree.find("v", "A/")!!
        assertEquals(5L, tree.totals(a).totalCount)
        assertEquals(1L, tree.totals(root.children.single { it.relativePath == null }).totalCount)
    }
}
