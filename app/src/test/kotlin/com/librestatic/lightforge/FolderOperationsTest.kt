package com.librestatic.lightforge

import com.librestatic.lightforge.core.mediastore.ALREADY_HIDDEN_BY_OTHERS
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.model.folders.HiddenMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderOperationsTest {
    @Test
    fun resultsMapToMessages() {
        assertEquals(FolderMessage.Done(FolderOp.Hide), AdvancedResult.Done.toFolderMessage(FolderOp.Hide))
        assertEquals(FolderMessage.NotGranted, AdvancedResult.NotGranted.toFolderMessage(FolderOp.Rename))
        assertEquals(
            FolderMessage.AlreadyHiddenByOthers,
            AdvancedResult.Failed(ALREADY_HIDDEN_BY_OTHERS).toFolderMessage(FolderOp.Hide),
        )
        assertEquals(FolderMessage.Failed(FolderOp.Delete), AdvancedResult.Failed("boom").toFolderMessage(FolderOp.Delete))
        assertEquals(FolderMessage.Failed(FolderOp.Create), AdvancedResult.Unsupported.toFolderMessage(FolderOp.Create))
        assertEquals(FolderMessage.Failed(FolderOp.Unhide), AdvancedResult.NeedsTreeGrant("/x").toFolderMessage(FolderOp.Unhide))
    }

    @Test
    fun renamedPathReplacesTheLastSegment() {
        assertEquals("DCIM/New/", renamedRelativePath("DCIM/Old/", "New"))
        assertEquals("New/", renamedRelativePath("Old/", "New"))
    }

    @Test
    fun hiddenRowsGroupByFolderAndKind() {
        fun row(id: Long, path: String, kind: HiddenMediaKind, date: Long) =
            HiddenRowFacts("v", id, path, kind, 10, date)
        val groups = groupHiddenFacts(
            listOf(
                row(1, "A/B", HiddenMediaKind.Image, 5),
                row(2, "A/B/", HiddenMediaKind.Image, 9),
                row(3, "A/B/", HiddenMediaKind.Video, 7),
                row(4, "", HiddenMediaKind.Image, 1),
            ),
        )
        assertEquals(2, groups.size)
        val images = groups.single { it.kind == HiddenMediaKind.Image }
        assertEquals("A/B/", images.relativePath)
        assertEquals(2L, images.count)
        assertEquals(20L, images.bytes)
        assertEquals(5L, images.minSortMillis)
        assertEquals(9L, images.maxSortMillis)
        assertEquals(2L, images.coverMediaStoreId)
    }
}
