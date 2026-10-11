package com.librestatic.lightforge

import com.librestatic.lightforge.core.editing.image.grade.ColorTransferLutBuilder
import com.librestatic.lightforge.core.editing.image.grade.PhotoLut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhotoCustomLookStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store(directory: File = File(folder.root, "photo-filters")) = PhotoCustomLookStore(directory)

    private fun result() = ColorTransferLutBuilder.build(IntArray(64) { 0xFF000000.toInt() or (it * 3 shl 16) or 0x2040 })

    @Test
    fun createListRenameDeleteRoundTrip() {
        val store = store()
        val reference = result()
        val created = store.create(reference.lut, reference.stats, "  Sunset  ")
        assertTrue(Regex("custom:[0-9a-f-]{36}").matches(created.id))
        assertEquals("Sunset", created.name)
        // A new instance reads from disk, not from the cache.
        val reopened = store()
        assertEquals(listOf(created), reopened.list())
        assertEquals(reference.lut.size, reopened.lut(created.id)?.size)
        // LUTs are stored as half floats, so the round trip is only close.
        val stored = reopened.lut(created.id)!!.data
        assertTrue(reference.lut.data.indices.all { kotlin.math.abs(reference.lut.data[it] - stored[it]) < 1e-3f })

        assertTrue(reopened.rename(created.id, "Dusk \"2\""))
        assertEquals("Dusk \"2\"", store().list().single().name)
        assertEquals(created.createdAtMillis, store().list().single().createdAtMillis)

        assertTrue(reopened.delete(created.id))
        assertTrue(store().list().isEmpty())
        assertNull(reopened.lut(created.id))
    }

    @Test
    fun filesLiveAsUuidLutAndJson() {
        val store = store()
        val reference = result()
        val created = store.create(reference.lut, reference.stats, "A")
        val uuid = created.id.removePrefix("custom:")
        assertTrue(File(folder.root, "photo-filters/$uuid.lut").isFile)
        val json = File(folder.root, "photo-filters/$uuid.json").readText()
        assertTrue(json.contains("\"name\":\"A\""))
        assertTrue(json.contains("\"meanL\""))
    }

    @Test
    fun rejectsBlankNamesAndForeignIds() {
        val store = store()
        val reference = result()
        val created = store.create(reference.lut, reference.stats, "Keep")
        assertFalse(store.rename(created.id, "   "))
        assertFalse(store.rename("custom:../../etc/passwd", "x"))
        assertFalse(store.delete("builtin:warm"))
        assertNull(store.lut("custom:../x"))
        assertEquals("Keep", store.list().single().name)
    }

    @Test
    fun defaultNamesSkipTakenOnes() {
        val store = store()
        val reference = result()
        assertEquals("Filter 1", store.nextDefaultName { "Filter $it" })
        store.create(reference.lut, reference.stats, "Filter 1")
        store.create(reference.lut, reference.stats, "Filter 3")
        assertEquals("Filter 4", store.nextDefaultName { "Filter $it" })
    }

    @Test
    fun missingLutFileHidesTheEntry() {
        val store = store()
        val reference = result()
        val created = store.create(reference.lut, reference.stats, "Gone")
        File(folder.root, "photo-filters/${created.id.removePrefix("custom:")}.lut").delete()
        assertTrue(store().list().isEmpty())
        assertNotNull(PhotoLut.identity())
    }

    @Test
    fun flatJsonEscapesAndParsesBack() {
        val written = FlatJson.write(linkedMapOf("name" to "a\"b\\c\n\u0001é", "n" to 12L, "x" to 1.5f))
        val parsed = FlatJson.parse(written)
        assertEquals("a\"b\\c\n\u0001é", parsed["name"])
        assertEquals(12L, parsed["n"])
        assertEquals(1.5, (parsed["x"] as Number).toDouble(), 0.0)
    }
}
