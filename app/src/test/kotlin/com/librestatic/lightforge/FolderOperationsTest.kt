package com.librestatic.lightforge

import com.librestatic.lightforge.core.mediastore.ALREADY_HIDDEN_BY_OTHERS
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.DirectoryContents
import com.librestatic.lightforge.core.mediastore.FOLDER_HAS_TRASH
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.model.folders.HiddenMediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private class InspectingStorage(private val contents: Map<String, DirectoryContents>) : AdvancedStorage {
        val deleted = ArrayList<String>()
        override val isSupported = true
        override fun isGranted() = true
        override fun permissionIntent(): android.content.Intent? = null
        override suspend fun hideFolder(absolutePath: String) = AdvancedResult.Done
        override suspend fun unhideFolder(absolutePath: String) = AdvancedResult.Done
        override suspend fun hiddenByApp(): List<HiddenEntry> = emptyList()
        override suspend fun unhideAllRecorded(): List<HiddenEntry> = emptyList()
        override suspend fun renameInPlace(sourceAbsolutePath: String, targetAbsolutePath: String) = AdvancedResult.Done
        override suspend fun deleteEmptyDirectory(absolutePath: String) = AdvancedResult.Done
        override suspend fun inspectDirectory(absolutePath: String): DirectoryContents? = contents[absolutePath]
        override suspend fun deleteDirectory(absolutePath: String): AdvancedResult {
            deleted += absolutePath
            return AdvancedResult.Done
        }
    }

    private fun contents(files: Long, other: Long = 0, hidden: Long = 0, trashed: Long = 0, folders: Long = 0) =
        DirectoryContents(files, folders, files * 10, files - other - trashed, other, hidden, trashed)

    @Test
    fun deleteSummaryAddsUpTheRealContentsOfEveryFolderAndDeletesNothing() = runBlocking {
        val storage = InspectingStorage(
            mapOf(
                "/a" to contents(files = 301, other = 300, folders = 2),
                "/b" to contents(files = 4, hidden = 3, trashed = 1, folders = 1),
            ),
        )
        val total = summarizeFolderDelete(storage, listOf("/a", "/b"))!!
        assertEquals(305L, total.files)
        assertEquals(3L, total.folders)
        assertEquals(300L, total.otherFiles)
        assertEquals(3L, total.hiddenMedia)
        assertEquals(1L, total.trashedFiles)
        assertEquals(3050L, total.bytes)
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun deleteSummaryIsUnknownWhenAnyFolderCannotBeRead() = runBlocking {
        val storage = InspectingStorage(mapOf("/a" to contents(files = 1)))
        assertNull(summarizeFolderDelete(storage, listOf("/a", "/missing")))
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun aFolderHoldingTrashMapsToItsOwnMessage() {
        assertEquals(FolderMessage.FolderHasTrash, AdvancedResult.Failed(FOLDER_HAS_TRASH).toFolderMessage(FolderOp.Delete))
    }

    /** A tiny disk: the directories that exist, as relative paths without the trailing slash. */
    private class FakeDisk(vararg dirs: String) {
        val dirs = dirs.toMutableSet()
        /** Directories that hold a file the library does not index (a PDF, an APK). */
        val withFiles = mutableSetOf<String>()
        fun list(parent: String?): List<com.librestatic.lightforge.core.mediastore.DirectoryEntry>? {
            val base = parent?.trimEnd('/')
            if (base != null && base !in dirs) return null
            return dirs.filter { it.substringBeforeLast('/', "") == base.orEmpty() && it.isNotEmpty() }.map { path ->
                val name = path.substringAfterLast('/')
                com.librestatic.lightforge.core.mediastore.DirectoryEntry(
                    name, "/disk/$path",
                    isEmpty = path !in withFiles && dirs.none { it.startsWith("$path/") },
                    hasNoMedia = false,
                    isDotPrefixed = name.startsWith("."),
                )
            }
        }
    }

    private fun overlayTree(disk: FakeDisk, at: String?): com.librestatic.lightforge.core.model.folders.FolderTree {
        val base = com.librestatic.lightforge.core.model.folders.FolderTree.from(
            listOf(
                com.librestatic.lightforge.core.model.folders.FolderNode(
                    "v", "DCIM/", "DCIM",
                    com.librestatic.lightforge.core.model.folders.FolderStats(null, 1, 1, 0, 10, 1, 1, 5, isAvailable = true),
                    emptyList(),
                ),
            ),
        )
        val dirs = runBlocking { diskOverlayDirectories("v", at, showHidden = false, listChildren = disk::list) }
        return com.librestatic.lightforge.core.model.folders.mergeAdvancedOverlay(base, emptyList(), dirs)
    }

    @Test
    fun aFolderOnlyTheDiskKnowsStaysOpenableAndNestable() {
        val disk = FakeDisk("A")
        // Open A: it is not in the library tree, yet it must resolve to itself and not to the volume root.
        val inA = overlayTree(disk, "A/")
        assertEquals(
            com.librestatic.lightforge.feature.folders.FolderLocation("v", "A/"),
            com.librestatic.lightforge.feature.folders.resolveLocation(inA, com.librestatic.lightforge.feature.folders.FolderLocation("v", "A/")),
        )
        // Create A/B while inside A: both stay in the tree whichever of the two is open.
        disk.dirs += "A/B"
        for (at in listOf("A/", "A/B/", null)) {
            val tree = overlayTree(disk, at)
            assertTrue("A visible at $at", tree.find("v", "A/") != null)
            if (at != null) assertTrue("A/B visible at $at", tree.find("v", "A/B/") != null)
        }
        assertEquals(
            com.librestatic.lightforge.feature.folders.FolderLocation("v", "A/B/"),
            com.librestatic.lightforge.feature.folders.resolveLocation(
                overlayTree(disk, "A/B/"), com.librestatic.lightforge.feature.folders.FolderLocation("v", "A/B/"),
            ),
        )
    }

    @Test
    fun aFolderHoldingOnlyNonMediaFilesOrUnlistedSubfoldersIsNotShownAsEmpty() {
        val disk = FakeDisk("Documents", "Documents/Work", "Download", "Download/Empty", "Tree", "Tree/Sub")
        disk.withFiles += "Documents/Work"
        val tree = overlayTree(disk, "Documents/")
        assertFalse(tree.find("v", "Documents/Work/")!!.direct!!.isEmptyDirectory)
        assertFalse(tree.find("v", "Documents/")!!.direct!!.isEmptyDirectory)
        // A folder with subfolders the open path does not list is not empty either.
        assertFalse(overlayTree(disk, null).find("v", "Tree/")!!.direct!!.isEmptyDirectory)
        assertTrue(overlayTree(disk, "Download/").find("v", "Download/Empty/")!!.direct!!.isEmptyDirectory)
    }

    @Test
    fun aDirectoryThatIsGoneEndsTheWalk() {
        val disk = FakeDisk("A")
        assertEquals(listOf("A/"), runBlocking { diskOverlayDirectories("v", "Gone/Deeper/", false, disk::list) }.map { it.relativePath })
    }
}
