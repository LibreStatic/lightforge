package com.librestatic.lightforge.core.storageadvanced

import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.mediastore.HiddenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var volumeA: File
    private lateinit var volumeB: File
    private lateinit var registryFile: File
    private val scans = mutableListOf<List<String>>()

    @Before fun setUp() {
        volumeA = tmp.newFolder("volA").canonicalFile
        volumeB = tmp.newFolder("volB").canonicalFile
        registryFile = File(tmp.newFolder("private"), "advanced-storage/hidden-registry.json")
    }

    private var granted = true
    private var ownerDeletes = true
    private var renameResult: Boolean? = null
    private var now = 42L

    private fun engine() = StorageEngine(
        registry = HiddenRegistry(registryFile),
        volumes = { listOf(VolumeRoot("external_primary", volumeA), VolumeRoot("abcd-1234", volumeB)) },
        scan = { scans += it },
        isGranted = { granted },
        deleteOwnedMarker = { ownerDeletes && it.delete() },
        renameFile = { from, to -> renameResult ?: from.renameTo(to) },
        clock = { now },
    )

    private fun dir(name: String, root: File = volumeA) = File(root, name).apply { mkdirs() }

    @Test fun registryRoundTrip() {
        val registry = HiddenRegistry(registryFile)
        val entries = listOf(HiddenEntry("/a/b", HiddenKind.NoMediaFile, 1L), HiddenEntry("/c", HiddenKind.DotPrefix, 2L))
        registry.write(entries)
        assertEquals(entries, HiddenRegistry(registryFile).read())
    }

    @Test fun missingRegistryIsEmpty() {
        assertTrue(HiddenRegistry(registryFile).read().isEmpty())
    }

    @Test fun corruptRegistryRecoversAndKeepsCopy() {
        registryFile.parentFile!!.mkdirs()
        registryFile.writeText("{not json")
        assertTrue(HiddenRegistry(registryFile).read().isEmpty())
        assertEquals("{not json", File(registryFile.parentFile, registryFile.name + ".corrupt").readText())
    }

    @Test fun hideRecordsAndUnhideRemoves() {
        val folder = dir("Pics")
        val e = engine()
        assertEquals(AdvancedResult.Done, e.hide(folder.path))
        assertTrue(File(folder, ".nomedia").exists())
        assertEquals(listOf(HiddenEntry(folder.path, HiddenKind.NoMediaFile, 42L)), e.hiddenByApp())
        assertEquals(AdvancedResult.Done, e.hide(folder.path))
        assertEquals(AdvancedResult.Done, e.unhide(folder.path))
        assertFalse(File(folder, ".nomedia").exists())
        assertTrue(e.hiddenByApp().isEmpty())
        assertTrue(scans.isNotEmpty())
    }

    @Test fun hideRefusesStandardTopLevelFoldersAndVolumeRoots() {
        val e = engine()
        for (name in listOf("DCIM", "Pictures", "Download")) {
            val folder = dir(name)
            assertTrue(e.hide(folder.path) is AdvancedResult.Failed)
            assertFalse(File(folder, ".nomedia").exists())
        }
        assertTrue(e.hide(volumeA.path) is AdvancedResult.Failed)
        assertTrue(e.hiddenByApp().isEmpty())
        // A subfolder of a standard folder is fine.
        assertEquals(AdvancedResult.Done, e.hide(dir("Pictures/Trip").path))
    }

    @Test fun hideFailsAndForgetsTheEntryWhenThePlatformRemovesTheMarker() {
        val folder = dir("Pics")
        val e = StorageEngine(
            registry = HiddenRegistry(registryFile),
            volumes = { listOf(VolumeRoot("external_primary", volumeA)) },
            scan = { File(folder, ".nomedia").delete() },
        )
        assertTrue(e.hide(folder.path) is AdvancedResult.Failed)
        assertTrue(e.hiddenByApp().isEmpty())
    }

    @Test fun rehideAfterExternalMarkerDeletionKeepsOneEntry() {
        val folder = dir("Pics")
        val e = engine()
        assertEquals(AdvancedResult.Done, e.hide(folder.path))
        File(folder, ".nomedia").delete()
        assertEquals(AdvancedResult.Done, e.hide(folder.path))
        assertEquals(1, e.hiddenByApp().size)
    }

    @Test fun preExistingNoMediaIsNeverRecordedOrDeleted() {
        val folder = dir("Theirs")
        File(folder, ".nomedia").createNewFile()
        val e = engine()
        assertEquals(AdvancedResult.Failed(StorageEngine.ALREADY_HIDDEN_BY_OTHERS), e.hide(folder.path))
        assertTrue(e.hiddenByApp().isEmpty())
        assertTrue(e.unhide(folder.path) is AdvancedResult.Failed)
        assertTrue(File(folder, ".nomedia").exists())
    }

    @Test fun unhideAllReturnsOnlyFailures() {
        val ok = dir("ok")
        val e = engine()
        e.hide(ok.path)
        assertTrue(e.unhideAll().isEmpty())
    }

    @Test fun rejectsRelativeOutsideAndAppPrivateAreas() {
        val e = engine()
        assertTrue(e.hide("relative/path") is AdvancedResult.Failed)
        assertTrue(e.hide(tmp.root.path) is AdvancedResult.Failed)
        assertTrue(e.hide(volumeA.path) is AdvancedResult.Failed)
        assertTrue(e.hide(dir("Android/data/x").path) is AdvancedResult.Failed)
        assertTrue(e.hide(dir("Android/obb").path) is AdvancedResult.Failed)
        assertTrue(e.hide(File(dir("p"), "../../private").path) is AdvancedResult.Failed)
    }

    @Test fun folderOperationsRefuseTheAndroidTreeInAnyCase() {
        val e = engine()
        dir("Android/media/com.whatsapp")
        dir("Pictures/Trips")
        for (p in listOf("Android", "Android/media", "Android/media/com.whatsapp", "android/DATA/x", "ANDROID/media")) {
            val path = File(volumeA, p).path
            assertTrue(p, e.hide(path) is AdvancedResult.Failed)
            assertTrue(p, e.renameDirectory(path, "Other") is AdvancedResult.Failed)
            assertTrue(p, e.moveDirectory(path, File(volumeA, "Pictures").path) is AdvancedResult.Failed)
            assertTrue(p, e.deleteEmptyDirectory(path) is AdvancedResult.Failed)
        }
        assertTrue(e.moveDirectory(File(volumeA, "Pictures/Trips").path, File(volumeA, "Android/media").path) is AdvancedResult.Failed)
        assertTrue(File(volumeA, "Android/media/com.whatsapp").isDirectory)
    }

    @Test fun moveAndCopyRefuseTargetsInTheAndroidMediaTree() {
        val e = engine()
        val one = File(dir("a"), "1.jpg").apply { writeText("1") }
        val two = File(dir("a"), "2.jpg").apply { writeText("2") }
        val target = File(volumeA, "Android/media/com.whatsapp/WhatsApp/1.jpg")
        val moved = e.moveAll(listOf(one.path to target.path, two.path to File(volumeA, "android/MEDIA/x/2.jpg").path))
        assertTrue(moved.all { it is AdvancedResult.Failed })
        assertTrue(e.copyAll(listOf(one.path to target.path)).single() is AdvancedResult.Failed)
        assertTrue(one.exists() && two.exists())
        assertFalse(File(volumeA, "Android").exists())
        assertTrue(e.renameInPlace(one.path, target.path) is AdvancedResult.Failed)
    }

    @Test fun standardTopLevelFoldersCannotBeRenamedMovedOrDeleted() {
        val e = engine()
        dir("Pictures/Trips")
        for (name in listOf("DCIM", "pictures", "Download", "Podcasts", "Screenshots", "Audiobooks")) {
            val path = dir(name).path
            assertTrue(name, e.renameDirectory(path, "Other") is AdvancedResult.Failed)
            assertTrue(name, e.moveDirectory(path, File(volumeA, "Pictures/Trips").path) is AdvancedResult.Failed)
            assertTrue(name, e.deleteEmptyDirectory(path) is AdvancedResult.Failed)
            assertTrue(name, File(path).isDirectory)
        }
        // Sub-folders and ordinary top-level folders stay manageable.
        assertEquals(AdvancedResult.Done, e.renameDirectory(File(volumeA, "Pictures/Trips").path, "Trips2"))
        assertEquals(AdvancedResult.Done, e.renameDirectory(dir("Mine").path, "Mine2"))
    }

    @Test fun renameMovesFileButRefusesExistingTarget() {
        val src = File(dir("a"), "x.jpg").apply { writeText("1") }
        val dst = File(volumeA, "b/x.jpg")
        val e = engine()
        assertEquals(AdvancedResult.Done, e.renameInPlace(src.path, dst.path))
        assertTrue(dst.exists() && !src.exists())
        val other = File(volumeA, "a/y.jpg").apply { writeText("2") }
        assertTrue(e.renameInPlace(other.path, dst.path) is AdvancedResult.Failed)
        assertEquals("1", dst.readText())
        assertTrue(other.exists())
    }

    @Test fun renameRefusesCrossVolume() {
        val src = File(dir("a"), "x.jpg").apply { writeText("1") }
        val dst = File(volumeB, "x.jpg")
        assertEquals(AdvancedResult.Failed("Cross-volume move"), engine().renameInPlace(src.path, dst.path))
        assertTrue(src.exists() && !dst.exists())
    }

    @Test fun deleteEmptyDirectoryRefusesNonEmpty() {
        val full = dir("full").also { File(it, "f").writeText("x") }
        val empty = dir("empty")
        val e = engine()
        assertTrue(e.deleteEmptyDirectory(full.path) is AdvancedResult.Failed)
        assertTrue(full.exists())
        assertEquals(AdvancedResult.Done, e.deleteEmptyDirectory(empty.path))
        assertFalse(empty.exists())
    }

    @Test fun createRenameAndMoveDirectoryRewriteRegistryPrefix() {
        val e = engine()
        val parent = dir("Pictures")
        assertEquals(AdvancedResult.Done, e.createDirectory(parent.path, "Trips"))
        val trips = File(parent, "Trips")
        assertTrue(trips.isDirectory)
        assertTrue(e.createDirectory(parent.path, "Trips") is AdvancedResult.Failed)
        assertTrue(e.createDirectory(parent.path, "../x") is AdvancedResult.Failed)
        assertEquals(AdvancedResult.Done, e.createDirectory(volumeA.path, "AtRoot"))
        val inner = dir("Pictures/Trips/Paris")
        e.hide(inner.path)
        e.hide(trips.path)
        scans.clear()

        assertEquals(AdvancedResult.Done, e.renameDirectory(trips.path, "Journeys"))
        val journeys = File(parent, "Journeys")
        assertTrue(journeys.isDirectory && !trips.exists())
        assertEquals(
            setOf(journeys.path, File(journeys, "Paris").path),
            e.hiddenByApp().map { it.absolutePath }.toSet(),
        )
        assertEquals(listOf(listOf(trips.path, journeys.path)), scans)

        val archive = dir("Archive")
        scans.clear()
        assertEquals(AdvancedResult.Done, e.moveDirectory(journeys.path, archive.path))
        val moved = File(archive, "Journeys")
        assertTrue(moved.isDirectory)
        assertEquals(
            setOf(moved.path, File(moved, "Paris").path),
            e.hiddenByApp().map { it.absolutePath }.toSet(),
        )
        assertEquals(listOf(listOf(journeys.path, moved.path)), scans)
        assertTrue(e.moveDirectory(archive.path, moved.path) is AdvancedResult.Failed)
    }

    @Test fun ancestorRenameRewritesDescendantEntriesOnly() {
        val e = engine()
        val deep = dir("A/B/C")
        val sibling = dir("A2/B")
        e.hide(deep.path)
        e.hide(sibling.path)
        assertEquals(AdvancedResult.Done, e.renameDirectory(File(volumeA, "A").path, "Z"))
        assertEquals(
            setOf(File(volumeA, "Z/B/C").path, sibling.path),
            e.hiddenByApp().map { it.absolutePath }.toSet(),
        )
    }

    @Test fun failedDirectoryRenameRollsRegistryBack() {
        val e = engine()
        val folder = dir("Pics")
        e.hide(folder.path)
        val before = e.hiddenByApp()
        renameResult = false
        assertTrue(e.renameDirectory(folder.path, "Other") is AdvancedResult.Failed)
        assertEquals(before, e.hiddenByApp())
        assertTrue(folder.isDirectory)
    }

    @Test fun directoryOpsRefuseCrossVolumeRootAndExisting() {
        val e = engine()
        val a = dir("a")
        dir("b")
        assertTrue(e.moveDirectory(a.path, volumeB.path) is AdvancedResult.Failed)
        assertTrue(e.renameDirectory(a.path, "b") is AdvancedResult.Failed)
        assertTrue(e.renameDirectory(volumeA.path, "x") is AdvancedResult.Failed)
        assertTrue(e.moveDirectory(a.path, File(a, "sub").also { it.mkdirs() }.path) is AdvancedResult.Failed)
        assertTrue(a.isDirectory)
    }

    @Test fun symlinkEscapeIntoAndroidDataIsRefused() {
        val e = engine()
        val secret = dir("Android/data/app/files")
        java.nio.file.Files.createSymbolicLink(File(volumeA, "link").toPath(), secret.toPath())
        assertTrue(e.hide(File(volumeA, "link").path) is AdvancedResult.Failed)
        assertTrue(e.createDirectory(File(volumeA, "link").path, "x") is AdvancedResult.Failed)
        assertTrue(e.listDirectories(File(volumeA, "link").path).isEmpty())
        val src = File(dir("a"), "x.jpg").apply { writeText("1") }
        assertTrue(e.renameInPlace(src.path, File(volumeA, "link/x.jpg").path) is AdvancedResult.Failed)
        assertTrue(src.exists())
        assertFalse(File(secret, "x.jpg").exists())
        assertFalse(File(secret, "x").exists())
    }

    @Test fun pendingAndTrashedSourcesAreRefused() {
        val e = engine()
        val pending = File(dir("a"), ".pending-1-x.jpg").apply { writeText("1") }
        val trashed = File(dir("a"), ".trashed-1-y.jpg").apply { writeText("1") }
        assertTrue(e.renameInPlace(pending.path, File(volumeA, "b/x.jpg").path) is AdvancedResult.Failed)
        val results = e.moveAll(listOf(pending.path to File(volumeA, "b/x.jpg").path, trashed.path to File(volumeA, "b/y.jpg").path))
        assertTrue(results.all { it is AdvancedResult.Failed })
        assertTrue(pending.exists() && trashed.exists())
        assertTrue(scans.isEmpty())
    }

    @Test fun moveAllScansOnceAndReportsPerItem() {
        val e = engine()
        val one = File(dir("a"), "1.jpg").apply { writeText("1") }
        val two = File(dir("a"), "2.jpg").apply { writeText("2") }
        val missing = File(volumeA, "a/none.jpg")
        val results = e.moveAll(
            listOf(
                one.path to File(volumeA, "b/1.jpg").path,
                missing.path to File(volumeA, "b/none.jpg").path,
                two.path to File(volumeA, "b/2.jpg").path,
            ),
        )
        assertEquals(AdvancedResult.Done, results[0])
        assertTrue(results[1] is AdvancedResult.Failed)
        assertEquals(AdvancedResult.Done, results[2])
        assertEquals(1, scans.size)
        assertEquals(
            setOf(one.path, File(volumeA, "b/1.jpg").path, two.path, File(volumeA, "b/2.jpg").path),
            scans.single().toSet(),
        )
    }

    @Test fun copyAllWritesVerifiedCopiesKeepsOriginalsAndScansOnce() {
        val e = engine()
        val one = File(dir("a"), "1.jpg").apply { writeText("one"); setLastModified(1_600_000_000_000L) }
        val taken = File(dir("b"), "1.jpg").apply { writeText("other") }
        val missing = File(volumeA, "a/none.jpg")
        val results = e.copyAll(
            listOf(
                one.path to File(volumeA, "c/1.jpg").path,
                one.path to taken.path,
                missing.path to File(volumeA, "c/none.jpg").path,
                one.path to File(volumeB, "d/1.jpg").path,
            ),
        )
        assertEquals(AdvancedResult.Done, results[0])
        assertTrue((results[1] as AdvancedResult.Failed).reason.contains("exists"))
        assertTrue(results[2] is AdvancedResult.Failed)
        assertEquals(AdvancedResult.Done, results[3])
        assertEquals("one", File(volumeA, "c/1.jpg").readText())
        assertEquals(1_600_000_000_000L, File(volumeA, "c/1.jpg").lastModified())
        assertEquals("one", File(volumeB, "d/1.jpg").readText())
        assertTrue(one.exists())
        assertEquals("other", taken.readText())
        assertEquals(1, scans.size)
        assertEquals(setOf(File(volumeA, "c/1.jpg").path, File(volumeB, "d/1.jpg").path), scans.single().toSet())
        assertEquals(listOf("1.jpg"), File(volumeA, "c").list()!!.toList())
    }

    @Test fun copyAllRefusesWithoutPermissionAndDoesNotLeavePartialFiles() {
        val e = engine()
        val one = File(dir("a"), "1.jpg").apply { writeText("one") }
        val failing = StorageEngine(
            registry = HiddenRegistry(registryFile),
            volumes = { listOf(VolumeRoot("external_primary", volumeA)) },
            scan = { scans += it },
            renameFile = { _, _ -> false },
        )
        assertTrue(failing.copyAll(listOf(one.path to File(volumeA, "c/1.jpg").path)).single() is AdvancedResult.Failed)
        assertTrue(File(volumeA, "c").list()!!.isEmpty())
        granted = false
        assertEquals(
            listOf<AdvancedResult>(AdvancedResult.NotGranted),
            e.copyAll(listOf(one.path to File(volumeA, "d/1.jpg").path)),
        )
        assertFalse(File(volumeA, "d").exists())
        assertTrue(scans.isEmpty())
    }

    @Test fun renameInPlaceScansSourceAndTarget() {
        val src = File(dir("a"), "x.jpg").apply { writeText("1") }
        val dst = File(volumeA, "b/x.jpg")
        engine().renameInPlace(src.path, dst.path)
        assertEquals(listOf(listOf(src.path, dst.path)), scans)
    }

    @Test fun revokedPermissionMapsToNotGrantedPerItem() {
        val e = engine()
        val one = File(dir("a"), "1.jpg").apply { writeText("1") }
        granted = false
        assertEquals(AdvancedResult.NotGranted, e.renameInPlace(one.path, File(volumeA, "b/1.jpg").path))
        assertEquals(
            listOf<AdvancedResult>(AdvancedResult.NotGranted),
            e.moveAll(listOf(one.path to File(volumeA, "b/1.jpg").path)),
        )
        assertEquals(AdvancedResult.NotGranted, e.renameDirectory(dir("d").path, "e"))
        assertTrue(one.exists() && scans.isEmpty())
    }

    @Test fun permissionLostMidFailureIsNotGrantedNotFailed() {
        val e = engine()
        val one = File(dir("a"), "1.jpg").apply { writeText("1") }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val flaky = StorageEngine(
            registry = HiddenRegistry(registryFile),
            volumes = { listOf(VolumeRoot("external_primary", volumeA)) },
            scan = { scans += it },
            isGranted = { calls.incrementAndGet() <= 1 },
            renameFile = { _, _ -> false },
        )
        assertEquals(AdvancedResult.NotGranted, flaky.renameInPlace(one.path, File(volumeA, "b/1.jpg").path))
        assertTrue(e.hiddenByApp().isEmpty())
    }

    @Test fun unhideLeavesForeignMarkerAndDropsEntry() {
        val folder = dir("Pics")
        now = 10_000_000_000_000L
        val e = engine()
        assertEquals(AdvancedResult.Done, e.hide(folder.path))
        // The marker predates the record by far more than the mtime tolerance: not ours any more.
        assertEquals(AdvancedResult.Done, e.unhide(folder.path))
        assertTrue(File(folder, ".nomedia").exists())
        assertTrue(e.hiddenByApp().isEmpty())

        val other = dir("Other")
        now = 42L
        e.hide(other.path)
        File(other, ".nomedia").writeText("not empty")
        assertEquals(AdvancedResult.Done, e.unhide(other.path))
        assertEquals("not empty", File(other, ".nomedia").readText())
        assertTrue(e.hiddenByApp().isEmpty())
    }

    @Test fun staleEntryCountsAsResolved() {
        val folder = dir("Pics")
        val e = engine()
        e.hide(folder.path)
        File(folder, ".nomedia").delete()
        assertEquals(AdvancedResult.Done, e.unhide(folder.path))
        e.hide(folder.path)
        File(folder, ".nomedia").delete()
        assertTrue(e.unhideAll().isEmpty())
        assertTrue(e.hiddenByApp().isEmpty())
    }

    @Test fun unhideWithoutPermissionUsesOwnerDeleteThenNeedsTreeGrant() {
        val folder = dir("Pics")
        val e = engine()
        e.hide(folder.path)
        granted = false
        ownerDeletes = false
        assertEquals(AdvancedResult.NeedsTreeGrant(folder.path), e.unhide(folder.path))
        assertTrue(File(folder, ".nomedia").exists())
        assertEquals(listOf(folder.path), e.unhideAll().map { it.absolutePath })

        ownerDeletes = true
        assertEquals(AdvancedResult.Done, e.unhide(folder.path))
        assertFalse(File(folder, ".nomedia").exists())
        assertTrue(e.hiddenByApp().isEmpty())
    }

    @Test fun unhideWithTreeDeletesThroughGrantAndChecksFolder() {
        val folder = dir("Pics/Sub")
        val e = engine()
        e.hide(folder.path)
        granted = false
        assertTrue(e.unhideWithTree(folder.path, "primary:Pics", { true }) is AdvancedResult.Failed)
        assertEquals(AdvancedResult.Done, e.unhideWithTree(folder.path, "primary:Pics/Sub", { it.delete() }))
        assertFalse(File(folder, ".nomedia").exists())
        assertTrue(e.hiddenByApp().isEmpty())
    }

    @Test fun unhideWithTreeOnAnotherVolumeLeavesForeignMarkerAndRecordAlone() {
        val folder = dir("Pics/Sub")
        val foreign = File(dir("Pics/Sub", volumeB), ".nomedia").apply { writeText("x") }
        val e = engine()
        e.hide(folder.path)
        granted = false
        var called = false
        val result = e.unhideWithTree(folder.path, "ABCD-1234:Pics/Sub", { called = true; it.delete() })
        assertTrue(result is AdvancedResult.Failed)
        assertFalse(called)
        assertTrue(foreign.exists())
        assertTrue(File(folder, ".nomedia").exists())
        assertEquals(1, e.hiddenByApp().size)
    }

    @Test fun unhideWithTreeKeepsTheRecordWhenTheMarkerSurvivesTheDelete() {
        val folder = dir("Pics/Sub")
        val e = engine()
        e.hide(folder.path)
        granted = false
        assertTrue(e.unhideWithTree(folder.path, "primary:Pics/Sub", { true }) is AdvancedResult.Failed)
        assertEquals(1, e.hiddenByApp().size)
    }

    @Test fun listDirectoriesDescribesChildren() {
        val e = engine()
        dir("Pictures/Empty")
        dir("Pictures/.dots").also { File(it, "f").writeText("x") }
        dir("Pictures/Hidden").also { File(it, ".nomedia").createNewFile() }
        dir("Android/data/x")
        File(volumeA, "Pictures/file.txt").writeText("x")
        val children = e.listDirectories(File(volumeA, "Pictures").path)
        assertEquals(listOf(".dots", "Empty", "Hidden"), children.map { it.name })
        assertEquals(listOf(true, false, false), children.map { it.isDotPrefixed })
        assertTrue(children.first { it.name == "Empty" }.isEmpty)
        assertTrue(children.first { it.name == "Hidden" }.hasNoMedia)
        assertTrue(children.first { it.name == "Hidden" }.isEmpty)
        assertFalse(children.first { it.name == ".dots" }.isEmpty)
        val rootChildren = e.listDirectories(volumeA.path).map { it.name }
        assertTrue("Android" in rootChildren && "Pictures" in rootChildren)
        assertTrue(e.listDirectories(File(volumeA, "Android").path).none { it.name == "data" })
    }

    @Test fun absolutePathOfMapsVolumesAndRejectsTraversal() {
        val e = engine()
        assertEquals(File(volumeA, "Pictures/Trips/a.jpg").path, e.absolutePathOf("external_primary", "Pictures/Trips/", "a.jpg"))
        assertEquals(File(volumeB, "DCIM").path, e.absolutePathOf("abcd-1234", "DCIM/", null))
        assertEquals(volumeA.path, e.absolutePathOf("external_primary", null, null))
        assertEquals(null, e.absolutePathOf("gone", "DCIM/", null))
        assertEquals(null, e.absolutePathOf("external_primary", "../x/", null))
    }

    // Dot-prefix hides are no longer created by the app; records from earlier builds must still be undoable.
    private fun legacyDotHide(name: String): Pair<File, File> {
        val folder = dir("Pictures/$name")
        val hidden = File(volumeA, "Pictures/.$name")
        File(folder, "a.jpg").writeText("x")
        assertTrue(folder.renameTo(hidden))
        HiddenRegistry(registryFile).write(listOf(HiddenEntry(hidden.path, HiddenKind.DotPrefix, 42L)))
        return folder to hidden
    }

    @Test fun legacyDotPrefixRecordIsUndoneByUnhide() {
        val (folder, hidden) = legacyDotHide("Trip")
        val e = engine()
        assertEquals(AdvancedResult.Done, e.unhide(hidden.path))
        assertTrue(folder.isDirectory && !hidden.exists() && File(folder, "a.jpg").isFile)
        assertTrue(e.hiddenByApp().isEmpty())
    }

    @Test fun legacyDotPrefixUnhideNeedsThePermissionAndKeepsTheRecordWhenTheNameIsTaken() {
        val (_, hidden) = legacyDotHide("Trip")
        val e = engine()
        granted = false
        assertEquals(AdvancedResult.NeedsTreeGrant(hidden.parentFile!!.path), e.unhide(hidden.path))
        granted = true
        dir("Pictures/Trip")
        assertTrue(e.unhide(hidden.path) is AdvancedResult.Failed)
        assertEquals(1, e.hiddenByApp().size)
        assertTrue(hidden.isDirectory)
    }

    @Test fun legacyDotPrefixRecordIsResolvedWhenTheFolderWasRenamedByHand() {
        val (_, hidden) = legacyDotHide("Trip")
        val e = engine()
        hidden.renameTo(File(volumeA, "Pictures/Other"))
        assertEquals(AdvancedResult.Done, e.unhide(hidden.path))
        assertTrue(e.hiddenByApp().isEmpty())
        assertTrue(File(volumeA, "Pictures/Other").isDirectory)
    }

    @Test fun aFileHiddenByNameIsRecordedAndUnhideRestoresIt() {
        val e = engine()
        val file = File(dir("Pictures"), "a.jpg").apply { writeText("x") }
        val hidden = File(file.parentFile, ".a.jpg")
        assertEquals(AdvancedResult.Done, e.hideWithDotPrefix(file.path))
        assertTrue(!file.exists() && hidden.isFile)
        assertEquals(listOf(hidden.path), e.hiddenByApp().map { it.absolutePath })
        assertEquals(AdvancedResult.Done, e.unhide(hidden.path))
        assertTrue(file.isFile && !hidden.exists() && e.hiddenByApp().isEmpty())
    }

    private fun hiddenByName(): Pair<File, File> {
        val file = File(dir("Pictures/Trip"), "a.jpg").apply { writeText("x") }
        assertEquals(AdvancedResult.Done, engine().hideWithDotPrefix(file.path))
        return file to File(file.parentFile, ".a.jpg")
    }

    @Test fun aFileHiddenByNameAsksForAFolderGrantWhenThePermissionIsGone() {
        val (file, hidden) = hiddenByName()
        val e = engine()
        granted = false
        assertEquals(AdvancedResult.NeedsTreeGrant(hidden.parentFile!!.path), e.unhide(hidden.path))
        assertTrue(hidden.isFile && !file.exists())
        assertEquals(1, e.hiddenByApp().size)
        assertEquals(1, e.unhideAll().size)
    }

    @Test fun aFileHiddenByNameIsUnhiddenThroughTheFolderGrantWithoutThePermission() {
        val (file, hidden) = hiddenByName()
        val e = engine()
        granted = false
        var asked: Pair<String, String>? = null
        val result = e.unhideWithTree(hidden.path, "primary:Pictures/Trip", { false }) { id, name ->
            asked = id to name
            hidden.renameTo(File(hidden.parentFile, name))
        }
        assertEquals(AdvancedResult.Done, result)
        assertEquals("Pictures/Trip/.a.jpg" to "a.jpg", asked?.let { it.first.substringAfter(':') to it.second })
        assertTrue(file.isFile && !hidden.exists() && e.hiddenByApp().isEmpty())
    }

    @Test fun anAncestorGrantAlsoUnhidesTheFileButAnotherFolderOrVolumeDoesNot() {
        val (file, hidden) = hiddenByName()
        val e = engine()
        granted = false
        var called = false
        val rename: (String, String) -> Boolean = { _, name -> called = true; hidden.renameTo(File(hidden.parentFile, name)) }
        assertTrue(e.unhideWithTree(hidden.path, "primary:Pictures/Other", { false }, rename) is AdvancedResult.Failed)
        assertTrue(e.unhideWithTree(hidden.path, "ABCD-1234:Pictures/Trip", { false }, rename) is AdvancedResult.Failed)
        assertFalse(called)
        assertTrue(hidden.isFile && e.hiddenByApp().size == 1)
        assertEquals(AdvancedResult.Done, e.unhideWithTree(hidden.path, "primary:Pictures", { false }, rename))
        assertTrue(file.isFile && e.hiddenByApp().isEmpty())
    }

    @Test fun aFailedTreeRenameKeepsTheRecordAndTheHiddenFile() {
        val (_, hidden) = hiddenByName()
        val e = engine()
        granted = false
        assertTrue(e.unhideWithTree(hidden.path, "primary:Pictures/Trip", { false }, { _, _ -> false }) is AdvancedResult.Failed)
        assertTrue(hidden.isFile)
        assertEquals(1, e.hiddenByApp().size)
    }

    @Test fun hidingByNameRefusesFoldersHiddenNamesTakenTargetsAndMissingPermission() {
        val e = engine()
        val folder = dir("Pictures/Trip")
        assertTrue(e.hideWithDotPrefix(folder.path) is AdvancedResult.Failed)
        assertTrue(folder.isDirectory)
        val dotted = File(dir("Pictures"), ".b.jpg").apply { writeText("x") }
        assertTrue(e.hideWithDotPrefix(dotted.path) is AdvancedResult.Failed)
        val file = File(dir("Pictures"), "c.jpg").apply { writeText("x") }
        File(file.parentFile, ".c.jpg").writeText("y")
        assertTrue(e.hideWithDotPrefix(file.path) is AdvancedResult.Failed)
        assertTrue(file.isFile && e.hiddenByApp().isEmpty())
        granted = false
        assertEquals(AdvancedResult.NotGranted, e.hideWithDotPrefix(file.path))
    }

    @Test fun scanTreeHandsEveryMediaFileUnderTheFolderToTheScanner() {
        val e = engine()
        val root = dir("Scans")
        File(root, "a.jpg").writeText("x")
        File(dir("Scans/Deep/Er"), "b.MP4").writeText("x")
        File(root, "notes.txt").writeText("x")
        File(root, ".trashed-1-c.jpg").writeText("x")
        scans.clear()
        assertEquals(2, e.scanTree(root.path))
        assertEquals(
            setOf(File(root, "a.jpg").path, File(root, "Deep/Er/b.MP4").path),
            scans.flatten().toSet(),
        )
    }

    @Test fun scanTreeRefusesMissingFoldersFilesOutsideVolumesAndMissingPermission() {
        val e = engine()
        val file = File(dir("Scans"), "a.jpg").apply { writeText("x") }
        assertEquals(null, e.scanTree(file.path))
        assertEquals(null, e.scanTree(File(volumeA, "Nope").path))
        assertEquals(null, e.scanTree("/etc"))
        granted = false
        assertEquals(null, e.scanTree(file.parent!!))
    }

    @Test fun existsOnlySeesPathsInsideMountedVolumes() {
        val e = engine()
        val f = File(dir("Pictures"), "a.jpg").apply { writeText("x") }
        assertTrue(e.exists(f.path))
        assertFalse(e.exists(File(volumeA, "Pictures/none.jpg").path))
        assertFalse(e.exists(tmp.root.path))
        assertFalse(e.exists("relative"))
    }

    @Test fun deleteFilesDeletesOneScansOnceAndTreatsAMissingFileAsDone() {
        val a = File(dir("Pictures"), "a.jpg").apply { writeText("x") }
        val b = File(volumeA, "Pictures/gone.jpg")
        val folder = dir("Pictures/Sub")
        val e = engine()
        val results = e.deleteFiles(listOf(a.path, b.path, folder.path, tmp.root.path))
        assertEquals(AdvancedResult.Done, results[0])
        assertEquals(AdvancedResult.Done, results[1])
        assertTrue(results[2] is AdvancedResult.Failed)
        assertTrue(results[3] is AdvancedResult.Failed)
        assertFalse(a.exists())
        assertTrue(folder.isDirectory)
        assertEquals(listOf(listOf(a.path, b.path)), scans)
        granted = false
        assertEquals(listOf(AdvancedResult.NotGranted), e.deleteFiles(listOf(a.path)))
    }

    @Test fun copyDirectoryCopiesTheTreeVerifiedAndLeavesTheSourceAlone() {
        val source = dir("Pictures/Trip")
        File(source, "a.jpg").writeText("aaa")
        File(dir("Pictures/Trip/Day1"), "b.jpg").writeText("bbb")
        dir("Pictures/Trip/EmptySub")
        val e = engine()
        assertEquals(AdvancedResult.Done, e.copyDirectory(source.path, dir("DCIM").path, "TripCopy"))
        val copy = File(volumeA, "DCIM/TripCopy")
        assertEquals("aaa", File(copy, "a.jpg").readText())
        assertEquals("bbb", File(copy, "Day1/b.jpg").readText())
        assertTrue(File(copy, "EmptySub").isDirectory)
        assertEquals("aaa", File(source, "a.jpg").readText())
        assertTrue(File(copy, "a.jpg").path in scans.flatten())
        assertFalse(copy.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun copyDirectoryAcrossVolumesAndWithoutANameKeepsTheOriginalName() {
        val source = dir("Pictures/Trip").also { File(it, "a.jpg").writeText("aaa") }
        val e = engine()
        assertEquals(AdvancedResult.Done, e.copyDirectory(source.path, dir("Pictures", volumeB).path))
        assertEquals("aaa", File(volumeB, "Pictures/Trip/a.jpg").readText())
    }

    @Test fun copyDirectoryDoesNotCopyTheMarkerThisAppMade() {
        val source = dir("Pictures/Trip").also { File(it, "a.jpg").writeText("aaa") }
        val e = engine()
        e.hide(source.path)
        File(source, "keep.txt").writeText("k")
        assertEquals(AdvancedResult.Done, e.copyDirectory(source.path, dir("DCIM").path, "T2"))
        val copy = File(volumeA, "DCIM/T2")
        assertFalse(File(copy, ".nomedia").exists())
        assertTrue(File(copy, "a.jpg").isFile && File(copy, "keep.txt").isFile)
    }

    @Test fun copyDirectoryRefusesExistingTargetsItselfTheAndroidTreeAndRevokedAccess() {
        val source = dir("Pictures/Trip").also { File(it, "a.jpg").writeText("aaa") }
        val e = engine()
        dir("DCIM/Trip")
        assertTrue(e.copyDirectory(source.path, File(volumeA, "DCIM").path) is AdvancedResult.Failed)
        assertTrue(e.copyDirectory(source.path, source.path, "Inner") is AdvancedResult.Failed)
        assertFalse(File(source, "Inner").exists())
        assertTrue(e.copyDirectory(source.path, dir("Android/media/x").path, "T") is AdvancedResult.Failed)
        assertTrue(e.copyDirectory(source.path, File(volumeA, "DCIM").path, "bad/name") is AdvancedResult.Failed)
        granted = false
        assertEquals(AdvancedResult.NotGranted, e.copyDirectory(source.path, File(volumeA, "DCIM").path, "T3"))
        assertFalse(File(volumeA, "DCIM/T3").exists())
    }

    @Test fun deleteDirectoryRemovesANonEmptyTreeScansItsFilesAndDropsTheRecords() {
        val folder = dir("Pictures/Trip")
        File(folder, "a.jpg").writeText("a")
        File(dir("Pictures/Trip/Day1"), "b.jpg").writeText("b")
        val e = engine()
        e.hide(dir("Pictures/Trip/Day1").path)
        scans.clear()
        assertEquals(AdvancedResult.Done, e.deleteDirectory(folder.path))
        assertFalse(folder.exists())
        assertTrue(e.hiddenByApp().isEmpty())
        assertTrue(File(folder, "a.jpg").path in scans.flatten())
        assertTrue(File(folder, "Day1/b.jpg").path in scans.flatten())
    }

    @Test fun deleteDirectoryNeverFollowsASymbolicLink() {
        val outside = dir("Elsewhere").also { File(it, "keep.jpg").writeText("k") }
        val folder = dir("Pictures/Trip")
        java.nio.file.Files.createSymbolicLink(File(folder, "link").toPath(), outside.toPath())
        val e = engine()
        assertEquals(AdvancedResult.Done, e.deleteDirectory(folder.path))
        assertFalse(folder.exists())
        assertEquals("k", File(outside, "keep.jpg").readText())
    }

    @Test fun deleteDirectoryRefusesStandardFoldersRootsTheAndroidTreeAndRevokedAccess() {
        val e = engine()
        assertTrue(e.deleteDirectory(dir("DCIM").also { File(it, "a.jpg").writeText("a") }.path) is AdvancedResult.Failed)
        assertTrue(File(volumeA, "DCIM/a.jpg").exists())
        assertTrue(e.deleteDirectory(volumeA.path) is AdvancedResult.Failed)
        assertTrue(e.deleteDirectory(dir("Android/media/x").path) is AdvancedResult.Failed)
        val folder = dir("Pictures/Trip")
        granted = false
        assertEquals(AdvancedResult.NotGranted, e.deleteDirectory(folder.path))
        assertTrue(folder.isDirectory)
    }

    @Test fun inspectDirectoryCountsEveryFileNotOnlyMedia() {
        val folder = dir("Pictures/Trip")
        File(folder, "a.jpg").writeText("aa")
        File(folder, "notes.txt").writeText("n")
        File(dir("Pictures/Trip/Docs"), "thesis.pdf").writeText("pdf")
        File(dir("Pictures/Trip/Private"), ".nomedia").writeText("")
        File(File(folder, "Private"), "secret.mp4").writeText("s")
        File(folder, ".trashed-1794957354-b.jpg").writeText("t")
        val contents = engine().inspectDirectory(folder.path)!!
        assertEquals(5L, contents.files)
        assertEquals(2L, contents.folders)
        assertEquals(2L, contents.mediaFiles)
        assertEquals(2L, contents.otherFiles)
        assertEquals(1L, contents.hiddenMedia)
        assertEquals(1L, contents.trashedFiles)
        assertEquals(2L + 1 + 3 + 1 + 1, contents.bytes)
    }

    @Test fun inspectDirectoryRefusesTheAndroidTreeAndAMissingFolder() {
        val e = engine()
        assertEquals(null, e.inspectDirectory(dir("Android/media/x").path))
        assertEquals(null, e.inspectDirectory(File(volumeA, "Nope").path))
    }

    @Test fun deleteDirectoryRefusesAFolderThatHoldsTrashedItemsAndDeletesNothing() {
        val folder = dir("Pictures/AdvReviewDel")
        File(folder, "d1.jpg").writeText("1")
        File(folder, "notes.txt").writeText("n")
        File(folder, ".trashed-1794957354-d0.jpg").writeText("t")
        assertEquals(
            AdvancedResult.Failed(com.librestatic.lightforge.core.mediastore.FOLDER_HAS_TRASH),
            engine().deleteDirectory(folder.path),
        )
        assertTrue(File(folder, "d1.jpg").exists())
        assertTrue(File(folder, "notes.txt").exists())
        assertTrue(File(folder, ".trashed-1794957354-d0.jpg").exists())
        File(folder, ".pending-1-p.jpg").writeText("p")
        File(folder, ".trashed-1794957354-d0.jpg").delete()
        assertTrue(engine().deleteDirectory(folder.path) is AdvancedResult.Failed)
        assertTrue(folder.exists())
    }
}
