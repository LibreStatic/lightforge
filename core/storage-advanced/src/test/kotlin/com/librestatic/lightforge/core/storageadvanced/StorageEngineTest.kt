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

    private fun engine() = StorageEngine(
        HiddenRegistry(registryFile), { listOf(volumeA, volumeB) }, { scans += it }, { 42L },
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
}
