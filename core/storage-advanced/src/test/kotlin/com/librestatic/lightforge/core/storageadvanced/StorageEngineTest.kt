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
        assertTrue(e.unhideWithTree(folder.path, "Pics", { true }) is AdvancedResult.Failed)
        assertEquals(AdvancedResult.Done, e.unhideWithTree(folder.path, "Pics/Sub", { it.delete() }))
        assertFalse(File(folder, ".nomedia").exists())
        assertTrue(e.hiddenByApp().isEmpty())
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
}
