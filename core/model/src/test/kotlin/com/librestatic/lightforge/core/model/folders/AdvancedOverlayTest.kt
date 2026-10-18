package com.librestatic.lightforge.core.model.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedOverlayTest {
    private fun stats(count: Long, max: Long, cover: Long, bucket: Long? = 1) =
        FolderStats(bucket, count, count, 0, count * 10, max - 5, max, cover, true)

    private fun baseTree() = FolderTree.from(
        listOf(
            FolderNode("v", "DCIM/", "DCIM", null, listOf(FolderNode("v", "DCIM/Camera/", "Camera", stats(3, 300, 30), emptyList()))),
            FolderNode("v", null, "loose", stats(1, 50, 5, bucket = 9), emptyList()),
        ),
    )

    private fun hidden(path: String, count: Long, kind: HiddenMediaKind, max: Long = 900, cover: Long? = 77, volume: String = "v") =
        HiddenFolderGroup(volume, path, count, count * 100, max - 10, max, cover, kind)

    @Test fun emptyOverlayReturnsTheSameTree() {
        val tree = baseTree()
        assertSame(tree, mergeAdvancedOverlay(tree, emptyList(), emptyList()))
    }

    @Test fun hiddenGroupsCreateFoldersAndParentsAndCombineKinds() {
        val merged = mergeAdvancedOverlay(
            baseTree(),
            listOf(hidden("Private/Vault/", 2, HiddenMediaKind.Image), hidden("Private/Vault/", 1, HiddenMediaKind.Video, max = 950)),
            emptyList(),
        )
        val vault = merged.find("v", "Private/Vault/")!!
        val direct = vault.direct!!
        assertEquals(3L, direct.directCount)
        assertEquals(2L, direct.imageCount)
        assertEquals(1L, direct.videoCount)
        assertEquals(300L, direct.totalBytes)
        assertEquals(950L, direct.maxSortMillis)
        assertTrue(direct.isHidden)
        assertFalse(direct.isEmptyDirectory)
        assertNull(merged.find("v", "Private/")!!.direct)
        assertEquals(3L, merged.totals(merged.find("v", "Private/")!!).totalCount)
        // The original folders survive untouched.
        assertEquals(stats(3, 300, 30), merged.find("v", "DCIM/Camera/")!!.direct)
        assertEquals(1L, merged.totals(merged.volumes.single().children.single { it.relativePath == null }).totalCount)
    }

    @Test fun hiddenGroupOnAVisibleFolderSumsAndFlagsIt() {
        val merged = mergeAdvancedOverlay(baseTree(), listOf(hidden("DCIM/Camera/", 2, HiddenMediaKind.Image, max = 200, cover = 88)), emptyList())
        val direct = merged.find("v", "DCIM/Camera/")!!.direct!!
        assertEquals(5L, direct.directCount)
        assertTrue(direct.isHidden)
        assertEquals(30L, direct.coverMediaStoreId)
        assertEquals(190L, direct.minSortMillis)
    }

    @Test fun emptyDirectoriesAreZeroStatsNodesUnlessTheFolderHasMedia() {
        val merged = mergeAdvancedOverlay(
            baseTree(),
            listOf(hidden("Vault/", 1, HiddenMediaKind.Image)),
            listOf(EmptyDirectory("v", "Drafts/"), EmptyDirectory("v", "DCIM/Camera/"), EmptyDirectory("v", "Vault/"), EmptyDirectory("w", "Other/")),
        )
        val drafts = merged.find("v", "Drafts/")!!.direct!!
        assertTrue(drafts.isEmptyDirectory)
        assertEquals(0L, drafts.directCount)
        assertFalse(merged.find("v", "DCIM/Camera/")!!.direct!!.isEmptyDirectory)
        assertFalse(merged.find("v", "Vault/")!!.direct!!.isEmptyDirectory)
        assertTrue(merged.find("w", "Other/")!!.direct!!.isEmptyDirectory)
        assertEquals(listOf("v", "w"), merged.volumes.map { it.name })
        assertEquals(0L, merged.totals(merged.find("v", "Drafts/")!!).totalCount)
    }

    @Test fun duplicateDirectsThatNormaliseAlikeAreCombined() {
        val forest = buildFolderForest(
            items = listOf("DCIM\\Camera" to 2, "DCIM/Camera/" to 3),
            volumeOf = { "v" },
            relativePathOf = { it.first },
            fallbackKeyOf = { it.first },
            fallbackNameOf = { it.first },
            combine = { a, b -> a.first to a.second + b.second },
        )
        assertEquals(5, forest.single().children.single().direct!!.second)
    }
}
