package com.librestatic.lightforge.core.selection

import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionSpecTest {
    @Test
    fun `set selected is idempotent in explicit and query all modes`() {
        val explicit = SelectionSpec.explicit(listOf(key(1)))
        assertSame(explicit, SelectionReducer.setSelected(explicit, key(1), true))
        assertSame(explicit, SelectionReducer.setSelected(explicit, key(2), false))

        val queryAll = SelectionSpec.queryAll(MediaQuery(), listOf(key(3)))
        assertSame(queryAll, SelectionReducer.setSelected(queryAll, key(2), true))
        assertSame(queryAll, SelectionReducer.setSelected(queryAll, key(3), false))

        assertFalse(SelectionReducer.isSelected(SelectionReducer.setSelected(queryAll, key(2), false), key(2)))
        assertTrue(SelectionReducer.isSelected(SelectionReducer.setSelected(queryAll, key(3), true), key(3)))
    }

    @Test
    fun `select all over 100k retains only query and exclusions`() {
        val query = MediaQuery(scope = MediaQuery.Scope.PhysicalAlbum("external_primary", 7))
        var selection = SelectionReducer.selectAll(query)

        selection = SelectionReducer.toggle(selection, key(10))
        selection = SelectionReducer.toggle(selection, key(20))

        assertTrue(selection is SelectionSpec.QueryAll)
        selection as SelectionSpec.QueryAll
        assertEquals(query, selection.querySnapshot)
        assertEquals(2, selection.exclusions.size)
        assertEquals(99_998L, SelectionReducer.count(selection, 100_000))
        assertFalse(SelectionReducer.isSelected(selection, key(10)))
        assertTrue(SelectionReducer.isSelected(selection, key(11)))
    }

    @Test
    fun `query all snapshot is not replaced when visible filters change`() {
        val original = MediaQuery(favoriteOnly = false)
        val changed = MediaQuery(favoriteOnly = true)
        val selection = SelectionReducer.selectAll(original) as SelectionSpec.QueryAll

        assertEquals(original, selection.querySnapshot)
        assertFalse(selection.querySnapshot == changed)
        assertFalse(SelectionReducer.isSelected(selection, key(1), belongsToQuerySnapshot = false))
    }

    @Test
    fun `saved state round trips explicit and query all modes`() {
        val explicit = SelectionSpec.explicit(listOf(key(1), key(2)))
        assertEquals(explicit, SelectionStateCodec.restore(SelectionStateCodec.save(explicit)))

        val queryAll = SelectionSpec.queryAll(
            MediaQuery(scope = MediaQuery.Scope.Search("summer")),
            listOf(key(3)),
        )
        assertEquals(queryAll, SelectionStateCodec.restore(SelectionStateCodec.save(queryAll)))
    }

    @Test
    fun `query all survives process-style serialization with constant state size`() {
        val selection = SelectionSpec.queryAll(
            MediaQuery(scope = MediaQuery.Scope.VirtualAlbum(42)),
            listOf(key(8), key(9)),
        )
        val encoded = ByteArrayOutputStream().also { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(SelectionStateCodec.save(selection)) }
        }.toByteArray()
        val restoredState = ObjectInputStream(ByteArrayInputStream(encoded)).use {
            it.readObject() as SelectionSavedState
        }

        assertTrue("Select-all saved state must stay small", encoded.size < 4_096)
        assertEquals(selection, SelectionStateCodec.restore(restoredState))
    }

    @Test
    fun `query all chunking stays bounded and applies exclusions`() = runBlocking {
        val all = (0L until 100_000L).map(::key)
        var largestRequest = 0
        var calls = 0
        val source = SelectionKeySource { _, after, limit ->
            calls++
            largestRequest = maxOf(largestRequest, limit)
            val start = after?.mediaStoreId?.plus(1)?.toInt() ?: 0
            all.subList(start.coerceAtMost(all.size), (start + limit).coerceAtMost(all.size))
        }
        val chunks = mutableListOf<Int>()
        var emitted = 0L
        SelectionChunker(source).forEachChunk(
            SelectionSpec.queryAll(MediaQuery(), listOf(key(0), key(50_000), key(99_999))),
            chunkSize = 256,
        ) { chunk ->
            chunks += chunk.size
            emitted += chunk.size
            assertTrue(key(0) !in chunk && key(50_000) !in chunk && key(99_999) !in chunk)
        }

        assertEquals(99_997L, emitted)
        assertTrue(chunks.all { it in 1..256 })
        assertEquals(256, largestRequest)
        assertTrue(calls > 390)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `chunk source cannot repeat a keyset page`() = runBlocking {
        val source = SelectionKeySource { _, _, _ -> listOf(key(1)) }
        SelectionChunker(source).forEachChunk(SelectionSpec.queryAll(MediaQuery()), 10) {}
    }

    /**
     * `Explicit.keys` is typed `Set<MediaKey>`, but callers that need tap order (e.g. Lightforge's
     * PDF Studio "Create PDF" numbered badges — see ProductionGalleryApp's `pdfSelectionOrder`)
     * rely on it actually being tap-order-preserving underneath, since `explicit()`/`toggle()` go
     * through `toSet()`/`toMutableSet()`, which the Kotlin stdlib backs with a LinkedHashSet. This
     * pins that behavior down as a deliberate contract: if a future change swaps in a plain
     * `HashSet` (or anything else that reorders), this test catches it before a caller silently
     * starts showing wrong page-order numbers.
     */
    @Test
    fun `explicit selection keeps tap order, not insertion-hash order`() {
        val tapped = listOf(key(5), key(1), key(9), key(3))
        assertEquals(tapped, SelectionSpec.explicit(tapped).keys.toList())
    }

    @Test
    fun `toggle appends newly selected keys at the end and preserves the rest`() {
        var selection: SelectionSpec = SelectionSpec.explicit(listOf(key(1), key(2)))
        selection = SelectionReducer.toggle(selection, key(3))
        assertEquals(listOf(key(1), key(2), key(3)), (selection as SelectionSpec.Explicit).keys.toList())

        // Deselecting and reselecting moves that key to the end, matching what the user would
        // expect from tapping it again: it becomes the new last page, not reinserted in place.
        selection = SelectionReducer.toggle(selection, key(1))
        selection = SelectionReducer.toggle(selection, key(1))
        assertEquals(listOf(key(2), key(3), key(1)), (selection as SelectionSpec.Explicit).keys.toList())
    }

    private fun key(id: Long) = MediaKey("external_primary", id)
}
