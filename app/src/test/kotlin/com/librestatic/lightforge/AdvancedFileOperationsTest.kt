package com.librestatic.lightforge

import android.content.Intent
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedFileOperationsTest {
    /** Records every call; [moveResults] and [copyResults] choose the answer per source path. */
    private class FakeStorage : AdvancedStorage {
        override val isSupported = true
        override fun isGranted() = true
        override fun permissionIntent(): Intent? = null
        override suspend fun hideFolder(absolutePath: String) = AdvancedResult.Done
        override suspend fun unhideFolder(absolutePath: String) = AdvancedResult.Done
        override suspend fun hiddenByApp(): List<HiddenEntry> = emptyList()
        override suspend fun unhideAllRecorded(): List<HiddenEntry> = emptyList()

        val moveResults = HashMap<String, AdvancedResult>()
        val copyResults = HashMap<String, AdvancedResult>()
        val moves = ArrayList<List<Pair<String, String>>>()
        val copies = ArrayList<List<Pair<String, String>>>()
        val renames = ArrayList<Pair<String, String>>()
        val deletedDirectories = ArrayList<String>()
        var renameResult: AdvancedResult = AdvancedResult.Done
        var directoryResult: AdvancedResult = AdvancedResult.Done
        var deleteResult: (String) -> AdvancedResult = { AdvancedResult.Done }
        val directoryCalls = ArrayList<String>()

        override fun absolutePathOf(volumeName: String, relativePath: String?, displayName: String?): String =
            "/$volumeName" + listOfNotNull(relativePath, displayName).joinToString("") { "/" + it.trim('/') }

        override suspend fun renameInPlace(sourceAbsolutePath: String, targetAbsolutePath: String): AdvancedResult {
            renames += sourceAbsolutePath to targetAbsolutePath
            return renameResult
        }

        override suspend fun deleteEmptyDirectory(absolutePath: String): AdvancedResult {
            deletedDirectories += absolutePath
            return deleteResult(absolutePath)
        }

        override suspend fun moveAll(moves: List<Pair<String, String>>): List<AdvancedResult> {
            this.moves += moves
            return moves.map { moveResults[it.first] ?: AdvancedResult.Done }
        }

        override suspend fun copyAll(copies: List<Pair<String, String>>): List<AdvancedResult> {
            this.copies += copies
            return copies.map { copyResults[it.first] ?: AdvancedResult.Done }
        }

        override suspend fun moveDirectory(absolutePath: String, newParentAbsolutePath: String): AdvancedResult {
            directoryCalls += "move $absolutePath -> $newParentAbsolutePath"
            return directoryResult
        }

        override suspend fun renameDirectory(absolutePath: String, newName: String): AdvancedResult {
            directoryCalls += "rename $absolutePath -> $newName"
            return directoryResult
        }

        val existing = HashSet<String>()
        val deletedFiles = ArrayList<String>()
        var deleteFilesResult: (String) -> AdvancedResult = { AdvancedResult.Unsupported }

        override suspend fun pathExists(absolutePath: String): Boolean = absolutePath in existing

        override suspend fun deleteFiles(absolutePaths: List<String>): List<AdvancedResult> {
            deletedFiles += absolutePaths
            return absolutePaths.map(deleteFilesResult)
        }

        override suspend fun copyDirectory(absolutePath: String, newParentAbsolutePath: String, newName: String?): AdvancedResult {
            directoryCalls += "copy $absolutePath -> $newParentAbsolutePath as $newName"
            return directoryResult
        }

        override suspend fun deleteDirectory(absolutePath: String): AdvancedResult {
            directoryCalls += "delete $absolutePath"
            return directoryResult
        }
    }

    private class Env(
        val storage: FakeStorage = FakeStorage(),
        var active: Boolean = true,
        var deleteEmpty: Boolean = false,
    ) {
        val hints = ArrayList<MediaKey>()
        val reconciled = ArrayList<Pair<String, String>>()
        var refreshes = 0
        val retired = ArrayList<List<TransferSource>>()
        val relocations = ArrayList<List<String>>()
        val rows = HashMap<MediaKey, TransferSource>()
        /** Keys whose MediaStore row no longer matches the index. */
        val changedOnDisk = HashSet<MediaKey>()
        /** Keys whose copy does not match the item's own bytes. */
        val corruptCopies = HashSet<MediaKey>()

        fun item(id: Long, volume: String = "external_primary", path: String = "DCIM/Camera/", name: String = "IMG_$id.jpg"): MediaKey {
            val key = MediaKey(volume, id)
            rows[key] = TransferSource(key, MediaKind.Image, volume, path, name)
            return key
        }

        val operations = AdvancedFileOperations(
            advanced = storage,
            isActive = { active },
            deleteEmptyFolders = { deleteEmpty },
            sources = { keys -> keys.mapNotNull(rows::get) },
            applyHint = { hints += it },
            reconcile = { volume, prefix -> reconciled += volume to prefix },
            refresh = { refreshes++ },
            retirement = { retired += it },
            folderBuckets = { _, _ -> emptyMap() },
            folderRelocated = { oldVolume, oldPrefix, newVolume, newPrefix, _ -> relocations += listOf(oldVolume, oldPrefix, newVolume, newPrefix) },
            verifyIdentity = { sources -> sources.map { it.key }.filterNot { it in changedOnDisk }.toSet() },
            verifyCopy = { source, _ -> source.key !in corruptCopies },
        )
    }

    private val dest = FolderTarget("external_primary", "Pictures/Trips/")

    @Test fun sameVolumeMoveRenamesEverythingInOneBatchAndHintsEachRow() = runBlocking {
        val env = Env()
        val keys = listOf(env.item(1), env.item(2), env.item(3))
        val result = env.operations.transfer(TransferPurpose.Move, keys, dest)
        assertEquals(1, env.storage.moves.size)
        assertEquals(
            listOf(
                "/external_primary/DCIM/Camera/IMG_1.jpg" to "/external_primary/Pictures/Trips/IMG_1.jpg",
                "/external_primary/DCIM/Camera/IMG_2.jpg" to "/external_primary/Pictures/Trips/IMG_2.jpg",
                "/external_primary/DCIM/Camera/IMG_3.jpg" to "/external_primary/Pictures/Trips/IMG_3.jpg",
            ),
            env.storage.moves.single(),
        )
        assertEquals(keys, env.hints)
        assertTrue(env.storage.copies.isEmpty())
        assertTrue(env.retired.isEmpty())
        assertEquals(1, env.refreshes)
        assertEquals(TransferResult(TransferPurpose.Move, 3, moved = 3), result)
    }

    @Test fun onlyTheRefusedItemFallsBackToTheVerifiedCopyAndItsOriginalIsRetired() = runBlocking {
        val env = Env()
        val one = env.item(1)
        val two = env.item(2)
        val three = env.item(3)
        env.storage.moveResults["/external_primary/DCIM/Camera/IMG_2.jpg"] = AdvancedResult.Failed("Target exists-ish")
        val result = env.operations.transfer(TransferPurpose.Move, listOf(one, two, three), dest)
        assertEquals(listOf(one, three), env.hints)
        assertEquals(
            listOf("/external_primary/DCIM/Camera/IMG_2.jpg" to "/external_primary/Pictures/Trips/IMG_2.jpg"),
            env.storage.copies.single(),
        )
        assertEquals(listOf(listOf(env.rows.getValue(two))), env.retired)
        assertEquals(2, result.moved)
        assertEquals(listOf(env.rows.getValue(two)), result.awaitingDelete)
        assertEquals(0, result.failed)
    }

    @Test fun anItemRenamedSuccessfullyIsNeverCopiedOrRetired() = runBlocking {
        val env = Env()
        val keys = listOf(env.item(1), env.item(2))
        env.operations.transfer(TransferPurpose.Move, keys, dest)
        assertTrue(env.storage.copies.isEmpty())
        assertTrue(env.retired.isEmpty())
    }

    @Test fun aFailedFallbackCopyCountsAsFailedAndRetiresNothing() = runBlocking {
        val env = Env()
        val one = env.item(1)
        env.storage.moveResults["/external_primary/DCIM/Camera/IMG_1.jpg"] = AdvancedResult.Failed("x")
        env.storage.copyResults["/external_primary/DCIM/Camera/IMG_1.jpg"] = AdvancedResult.Failed("Target exists")
        val result = env.operations.transfer(TransferPurpose.Move, listOf(one), dest)
        assertEquals(1, result.failed)
        assertTrue(env.retired.isEmpty())
        assertTrue(env.hints.isEmpty())
        assertEquals(0, env.refreshes)
    }

    @Test fun permissionLostMidBatchIsReportedAndKeepsWhatAlreadyMoved() = runBlocking {
        val env = Env()
        val one = env.item(1)
        val two = env.item(2)
        env.storage.moveResults["/external_primary/DCIM/Camera/IMG_2.jpg"] = AdvancedResult.NotGranted
        val result = env.operations.transfer(TransferPurpose.Move, listOf(one, two), dest)
        assertTrue(result.notGranted)
        assertEquals(1, result.moved)
        assertEquals(1, result.failed)
        assertEquals(listOf(one), env.hints)
        assertTrue(env.storage.copies.isEmpty())
        assertTrue(env.retired.isEmpty())
    }

    @Test fun crossVolumeMoveIsAVerifiedCopyThenRetiresTheOriginalWithoutRenaming() = runBlocking {
        val env = Env()
        val card = env.item(7, volume = "1234-ABCD", path = "DCIM/")
        val result = env.operations.transfer(TransferPurpose.Move, listOf(card), dest)
        assertTrue(env.storage.moves.isEmpty())
        assertEquals(
            listOf("/1234-ABCD/DCIM/IMG_7.jpg" to "/external_primary/Pictures/Trips/IMG_7.jpg"),
            env.storage.copies.single(),
        )
        assertEquals(listOf(listOf(env.rows.getValue(card))), env.retired)
        assertTrue(env.hints.isEmpty())
        assertEquals(0, result.moved)
        assertEquals(1, result.awaitingDelete.size)
    }

    @Test fun copyAlwaysUsesTheVerifiedCopyAndKeepsTheOriginals() = runBlocking {
        val env = Env()
        val keys = listOf(env.item(1), env.item(2))
        val result = env.operations.transfer(TransferPurpose.Copy, keys, dest)
        assertTrue(env.storage.moves.isEmpty())
        assertEquals(2, env.storage.copies.single().size)
        assertTrue(env.retired.isEmpty())
        assertEquals(TransferResult(TransferPurpose.Copy, 2, copied = 2), result)
        assertEquals(1, env.refreshes)
    }

    @Test fun anItemAlreadyInTheDestinationIsSkippedAndAMissingRowFails() = runBlocking {
        val env = Env()
        val here = env.item(1, path = "Pictures/Trips/")
        val ghost = MediaKey("external_primary", 99)
        val result = env.operations.transfer(TransferPurpose.Move, listOf(here, ghost), dest)
        assertTrue(env.storage.moves.isEmpty())
        assertEquals(1, result.skipped)
        assertEquals(1, result.failed)
    }

    @Test fun inactiveModeTouchesNothing() = runBlocking {
        val env = Env(active = false)
        val key = env.item(1)
        val result = env.operations.transfer(TransferPurpose.Move, listOf(key), dest)
        assertTrue(result.inactive)
        assertTrue(env.storage.moves.isEmpty() && env.storage.copies.isEmpty())
        assertTrue(env.hints.isEmpty() && env.retired.isEmpty())
        assertFalse(env.operations.renameSibling(key, "new.jpg"))
        assertTrue(env.storage.renames.isEmpty())
        assertEquals(AdvancedResult.NotGranted, env.operations.moveFolder(FolderTarget("external_primary", "A/"), dest).result)
        assertTrue(env.storage.directoryCalls.isEmpty())
    }

    @Test fun progressStartsAtZeroAndEndsAtTheTotal() = runBlocking {
        val env = Env()
        val seen = ArrayList<Pair<Int, Int>>()
        env.operations.transfer(TransferPurpose.Move, listOf(env.item(1), env.item(2)), dest) { done, total -> seen += done to total }
        assertEquals(0 to 2, seen.first())
        assertEquals(2 to 2, seen.last())
    }

    @Test fun emptySourceFoldersAreRemovedOncePerDistinctParentAfterAMove() = runBlocking {
        val env = Env(deleteEmpty = true)
        val keys = listOf(env.item(1), env.item(2), env.item(3, path = "Download/Old/"))
        env.storage.deleteResult = { if (it.endsWith("Old")) AdvancedResult.Failed("Directory not empty") else AdvancedResult.Done }
        env.operations.transfer(TransferPurpose.Move, keys, dest)
        assertEquals(
            listOf("/external_primary/DCIM/Camera", "/external_primary/Download/Old"),
            env.storage.deletedDirectories,
        )
    }

    @Test fun emptyFoldersAreKeptWhenTheOptionIsOffOrNothingWasRenamed() = runBlocking {
        val off = Env(deleteEmpty = false)
        off.operations.transfer(TransferPurpose.Move, listOf(off.item(1)), dest)
        assertTrue(off.storage.deletedDirectories.isEmpty())

        val copyOnly = Env(deleteEmpty = true)
        copyOnly.operations.transfer(TransferPurpose.Copy, listOf(copyOnly.item(1)), dest)
        assertTrue(copyOnly.storage.deletedDirectories.isEmpty())

        val inactive = Env(deleteEmpty = true, active = false)
        inactive.operations.deleteEmptyParents(listOf("external_primary" to "DCIM/Camera/"))
        assertTrue(inactive.storage.deletedDirectories.isEmpty())
    }

    @Test fun trashOrDeleteCleanupNeverTouchesAVolumeRootAndIgnoresRefusals() = runBlocking {
        val env = Env(deleteEmpty = true)
        env.storage.deleteResult = { AdvancedResult.Failed("Directory not empty") }
        env.operations.deleteEmptyParents(listOf("external_primary" to "", "external_primary" to "DCIM/", "external_primary" to "DCIM/"))
        assertEquals(listOf("/external_primary/DCIM"), env.storage.deletedDirectories)
    }

    @Test fun parentsAreReadFromTheIndexBeforeTheRowsGoAway() = runBlocking {
        val env = Env()
        val key = env.item(1)
        assertEquals(listOf("external_primary" to "DCIM/Camera/"), env.operations.parentsOf(listOf(key)))
    }

    @Test fun renameSiblingRenamesNextToTheFileAndHintsTheRow() = runBlocking {
        val env = Env()
        val key = env.item(1)
        assertTrue(env.operations.renameSibling(key, "Sunset.jpg"))
        assertEquals(
            listOf("/external_primary/DCIM/Camera/IMG_1.jpg" to "/external_primary/DCIM/Camera/Sunset.jpg"),
            env.storage.renames,
        )
        assertEquals(listOf(key), env.hints)
    }

    @Test fun renameSiblingKeepsTheExtensionWhenTheNewNameHasNone() = runBlocking {
        val env = Env()
        val key = env.item(1)
        assertTrue(env.operations.renameSibling(key, "Sunset"))
        assertEquals(
            listOf("/external_primary/DCIM/Camera/IMG_1.jpg" to "/external_primary/DCIM/Camera/Sunset.jpg"),
            env.storage.renames,
        )
    }

    @Test fun renameSiblingRefusesDotPrefixAndExtensionChanges() = runBlocking {
        val env = Env()
        val key = env.item(1)
        assertFalse(env.operations.renameSibling(key, ".Sunset.jpg"))
        assertFalse(env.operations.renameSibling(key, "Sunset.txt"))
        assertFalse(env.operations.renameSibling(key, ".jpg"))
        assertTrue(env.storage.renames.isEmpty())
    }

    @Test fun renameSiblingReportsFalseOnARefusalSoTheWriteRequestTakesOver() = runBlocking {
        val env = Env()
        val key = env.item(1)
        env.storage.renameResult = AdvancedResult.Failed("Target exists")
        assertFalse(env.operations.renameSibling(key, "Sunset.jpg"))
        assertTrue(env.hints.isEmpty())
        assertFalse(env.operations.renameSibling(MediaKey("external_primary", 404), "x.jpg"))
    }

    @Test fun folderMoveCallsMoveDirectoryAndReconcilesNewThenOldPrefix() = runBlocking {
        val env = Env()
        val result = env.operations.moveFolder(FolderTarget("external_primary", "DCIM/Exports/"), FolderTarget("external_primary", "Pictures/"))
        assertEquals(AdvancedResult.Done, result.result)
        assertEquals(listOf("move /external_primary/DCIM/Exports -> /external_primary/Pictures"), env.storage.directoryCalls)
        assertEquals(
            listOf("external_primary" to "Pictures/Exports/", "external_primary" to "DCIM/Exports/"),
            env.reconciled,
        )
        assertEquals(1, env.refreshes)
    }

    @Test fun folderRenameReconcilesBothPrefixesAndAFailureReconcilesNothing() = runBlocking {
        val env = Env()
        val result = env.operations.renameFolder(FolderTarget("external_primary", "DCIM/Old/"), "New")
        assertEquals(AdvancedResult.Done, result.result)
        assertEquals(listOf("external_primary" to "DCIM/New/", "external_primary" to "DCIM/Old/"), env.reconciled)

        val failing = Env()
        failing.storage.directoryResult = AdvancedResult.Failed("Target exists")
        val failed = failing.operations.renameFolder(FolderTarget("external_primary", "DCIM/Old/"), "New")
        assertTrue(failed.result is AdvancedResult.Failed)
        assertTrue(failing.reconciled.isEmpty())
        assertNull(failed.reconciled.firstOrNull())
    }

    @Test fun aFolderOperationOnAVolumeRootIsRefused() = runBlocking {
        val env = Env()
        assertTrue(env.operations.renameFolder(FolderTarget("external_primary", null), "X").result is AdvancedResult.Failed)
        assertTrue(env.operations.moveFolder(FolderTarget("external_primary", ""), dest).result is AdvancedResult.Failed)
        assertTrue(env.storage.directoryCalls.isEmpty())
    }

    @Test fun anItemWhoseRowChangedOnDiskIsNeitherMovedCopiedNorRetired() = runBlocking {
        val env = Env()
        val stale = env.item(1)
        val card = env.item(7, volume = "1234-ABCD", path = "DCIM/")
        env.changedOnDisk += stale
        env.changedOnDisk += card
        val result = env.operations.transfer(TransferPurpose.Move, listOf(stale, card), dest)
        assertTrue(env.storage.moves.isEmpty() && env.storage.copies.isEmpty())
        assertTrue(env.retired.isEmpty())
        assertEquals(2, result.failed)
    }

    @Test fun anOriginalIsOnlyRetiredWhenItsCopyMatchesTheItemsOwnBytes() = runBlocking {
        val env = Env()
        val good = env.item(7, volume = "1234-ABCD", path = "DCIM/")
        val bad = env.item(8, volume = "1234-ABCD", path = "DCIM/")
        env.corruptCopies += bad
        val result = env.operations.transfer(TransferPurpose.Move, listOf(good, bad), dest)
        assertEquals(listOf(listOf(env.rows.getValue(good))), env.retired)
        assertEquals(1, result.failed)
        assertEquals(listOf(env.rows.getValue(good)), result.awaitingDelete)
    }

    @Test fun renameSiblingRefusesARowThatChangedOnDisk() = runBlocking {
        val env = Env()
        val key = env.item(1)
        env.changedOnDisk += key
        assertFalse(env.operations.renameSibling(key, "New.jpg"))
        assertTrue(env.storage.renames.isEmpty())
    }

    @Test fun folderRenameAndMoveHandTheNewPathToTheRuleRelocation() = runBlocking {
        val env = Env()
        env.operations.renameFolder(FolderTarget("external_primary", "DCIM/Old/"), "New")
        env.operations.moveFolder(FolderTarget("external_primary", "DCIM/Exports/"), dest)
        assertEquals(
            listOf(
                listOf("external_primary", "DCIM/Old/", "external_primary", "DCIM/New/"),
                listOf("external_primary", "DCIM/Exports/", "external_primary", "Pictures/Trips/Exports/"),
            ),
            env.relocations,
        )
        val failing = Env()
        failing.storage.directoryResult = AdvancedResult.Failed("x")
        failing.operations.renameFolder(FolderTarget("external_primary", "DCIM/Old/"), "New")
        assertTrue(failing.relocations.isEmpty())
    }

    @Test fun `the outcome sums a transfer up as one message`() {
        val p = TransferPurpose.Move
        assertEquals(TransferOutcome.Inactive, TransferResult(p, 2, inactive = true).outcome())
        assertEquals(TransferOutcome.NotGranted, TransferResult(p, 2, moved = 1, failed = 1, notGranted = true).outcome())
        assertEquals(TransferOutcome.Nothing, TransferResult(p, 1, skipped = 1).outcome())
        assertEquals(TransferOutcome.Done, TransferResult(p, 2, moved = 2).outcome())
        assertEquals(TransferOutcome.Partial, TransferResult(p, 2, moved = 1, failed = 1).outcome())
        assertEquals(TransferOutcome.Failed, TransferResult(p, 2, failed = 2).outcome())
    }

    @Test fun aTakenNameGetsACounterInsteadOfFailingOnBothPaths() = runBlocking {
        val env = Env()
        env.storage.existing += "/external_primary/Pictures/Trips/IMG_1.jpg"
        env.storage.existing += "/external_primary/Pictures/Trips/IMG_1 (1).jpg"
        val key = env.item(1)
        val result = env.operations.transfer(TransferPurpose.Move, listOf(key), dest)
        assertEquals(
            listOf("/external_primary/DCIM/Camera/IMG_1.jpg" to "/external_primary/Pictures/Trips/IMG_1 (2).jpg"),
            env.storage.moves.single(),
        )
        assertEquals(1, result.moved)
    }

    @Test fun twoSourcesWithTheSameNameNeverMeetAtOneTarget() = runBlocking {
        val env = Env()
        val a = env.item(1, path = "DCIM/A/", name = "IMG.jpg")
        val b = env.item(2, path = "DCIM/B/", name = "IMG.jpg")
        env.operations.transfer(TransferPurpose.Copy, listOf(a, b), dest)
        assertEquals(
            listOf(
                "/external_primary/DCIM/A/IMG.jpg" to "/external_primary/Pictures/Trips/IMG.jpg",
                "/external_primary/DCIM/B/IMG.jpg" to "/external_primary/Pictures/Trips/IMG (1).jpg",
            ),
            env.storage.copies.single(),
        )
    }

    @Test fun copyingIntoItsOwnFolderMakesANumberedDuplicate() = runBlocking {
        val env = Env()
        val here = env.item(1, path = "Pictures/Trips/")
        env.storage.existing += "/external_primary/Pictures/Trips/IMG_1.jpg"
        val result = env.operations.transfer(TransferPurpose.Copy, listOf(here), dest)
        assertEquals(
            listOf("/external_primary/Pictures/Trips/IMG_1.jpg" to "/external_primary/Pictures/Trips/IMG_1 (1).jpg"),
            env.storage.copies.single(),
        )
        assertEquals(1, result.copied)
    }

    @Test fun theEngineDeletesTheOriginalOfAVerifiedCopyWithoutASystemDialog() = runBlocking {
        val env = Env()
        env.storage.deleteFilesResult = { AdvancedResult.Done }
        val card = env.item(7, volume = "1234-ABCD", path = "DCIM/")
        val result = env.operations.transfer(TransferPurpose.Move, listOf(card), dest)
        assertEquals(listOf("/1234-ABCD/DCIM/IMG_7.jpg"), env.storage.deletedFiles)
        assertTrue(env.retired.isEmpty())
        assertEquals(listOf(card), env.hints)
        assertEquals(1, result.relocated)
        assertTrue(result.awaitingDelete.isEmpty())
        assertEquals(TransferOutcome.Done, result.outcome())
        assertEquals(1, env.refreshes)
    }

    @Test fun anOriginalTheEngineRefusesToDeleteGoesToTheConfirmedDelete() = runBlocking {
        val env = Env()
        env.storage.deleteFilesResult = { if (it.endsWith("IMG_8.jpg")) AdvancedResult.Failed("busy") else AdvancedResult.Done }
        val a = env.item(7, volume = "1234-ABCD", path = "DCIM/")
        val b = env.item(8, volume = "1234-ABCD", path = "DCIM/")
        val result = env.operations.transfer(TransferPurpose.Move, listOf(a, b), dest)
        assertEquals(1, result.relocated)
        assertEquals(listOf(env.rows.getValue(b)), result.awaitingDelete)
        assertEquals(listOf(listOf(env.rows.getValue(b))), env.retired)
    }

    @Test fun anUnverifiedCopyIsNeverDeletedByTheEngine() = runBlocking {
        val env = Env()
        env.storage.deleteFilesResult = { AdvancedResult.Done }
        val card = env.item(7, volume = "1234-ABCD", path = "DCIM/")
        env.corruptCopies += card
        val result = env.operations.transfer(TransferPurpose.Move, listOf(card), dest)
        assertTrue(env.storage.deletedFiles.isEmpty())
        assertEquals(1, result.failed)
    }

    @Test fun copyFolderTakesAFreeNameAndReadsTheNewSubtree() = runBlocking {
        val env = Env()
        env.storage.existing += "/external_primary/Pictures/Trips/Day1"
        val result = env.operations.copyFolder(FolderTarget("external_primary", "DCIM/Day1/"), dest)
        assertEquals(
            listOf("copy /external_primary/DCIM/Day1 -> /external_primary/Pictures/Trips as Day1 (1)"),
            env.storage.directoryCalls,
        )
        assertEquals(AdvancedResult.Done, result.result)
        assertEquals(listOf("external_primary" to "Pictures/Trips/Day1 (1)/"), env.reconciled)
        assertEquals(1, env.refreshes)
    }

    @Test fun aFailedFolderCopyReconcilesNothingAndTheVolumeRootIsRefused() = runBlocking {
        val env = Env()
        env.storage.directoryResult = AdvancedResult.Failed("copy failed")
        assertTrue(env.operations.copyFolder(FolderTarget("external_primary", "DCIM/Day1/"), dest).result is AdvancedResult.Failed)
        assertTrue(env.reconciled.isEmpty())
        assertTrue(env.operations.copyFolder(FolderTarget("external_primary", null), dest).result is AdvancedResult.Failed)
        env.active = false
        assertEquals(AdvancedResult.NotGranted, env.operations.copyFolder(FolderTarget("external_primary", "DCIM/Day1/"), dest).result)
    }

    @Test fun deleteFolderRemovesTheTreeAndReReadsItEvenWhenItFailedHalfway() = runBlocking {
        val env = Env()
        val done = env.operations.deleteFolder(FolderTarget("external_primary", "DCIM/Day1/"))
        assertEquals(AdvancedResult.Done, done.result)
        assertEquals(listOf("delete /external_primary/DCIM/Day1"), env.storage.directoryCalls)
        assertEquals(listOf("external_primary" to "DCIM/Day1/"), env.reconciled)
        env.storage.directoryResult = AdvancedResult.Failed("busy")
        assertTrue(env.operations.deleteFolder(FolderTarget("external_primary", "DCIM/Day1/")).result is AdvancedResult.Failed)
        assertEquals(2, env.reconciled.size)
        assertTrue(env.operations.deleteFolder(FolderTarget("external_primary", "")).result is AdvancedResult.Failed)
    }

    @Test fun withCounterKeepsTheExtension() {
        assertEquals("IMG_1 (2).jpg", withCounter("IMG_1.jpg", 2))
        assertEquals("notes (1)", withCounter("notes", 1))
        assertEquals(".hidden (1)", withCounter(".hidden", 1))
        assertEquals("a.b (3).png", withCounter("a.b.png", 3))
    }
}
