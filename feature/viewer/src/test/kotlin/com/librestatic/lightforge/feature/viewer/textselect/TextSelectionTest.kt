package com.librestatic.lightforge.feature.viewer.textselect

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.librestatic.lightforge.core.model.RecognizedLine
import com.librestatic.lightforge.core.model.RecognizedText
import com.librestatic.lightforge.core.model.RecognizedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSelectionTest {
    // Two lines on a 100x100 image: "Hello world" at y 10..20, "second line" at y 40..50.
    private val text = RecognizedText(
        listOf(
            RecognizedLine(listOf(word("Hello", 0.10f, 0.30f, 0.10f, 0), word("world", 0.40f, 0.70f, 0.10f, 0))),
            RecognizedLine(listOf(word("second", 0.10f, 0.40f, 0.40f, 1), word("line", 0.50f, 0.70f, 0.40f, 1))),
        ),
    )
    private val geometry = TextSelectionGeometry(text, 100f, 100f)

    @Test
    fun hitTestFindsWordUnderPointOrWithinSlop() {
        assertEquals(1, geometry.hitTest(50f, 15f, 0f))
        assertEquals(0, geometry.hitTest(33f, 15f, 5f))
        assertNull(geometry.hitTest(90f, 90f, 10f))
    }

    @Test
    fun nearestSnapsToClosestLineThenWord() {
        assertEquals(3, geometry.nearest(95f, 60f))
        assertEquals(2, geometry.nearest(0f, 38f))
        assertEquals(1, geometry.nearest(60f, 0f))
    }

    @Test
    fun selectionTextJoinsWordsAndLinesInOrderRegardlessOfDirection() {
        assertEquals("world\nsecond", TextSelection(2, 1).text(text))
        assertEquals("Hello world\nsecond line", TextSelection.all(text).text(text))
        assertEquals("line", TextSelection(3, 3).text(text))
    }

    @Test
    fun layerTransformRoundTripsForEveryQuarterTurn() {
        listOf(0f, 90f, 180f, 270f, 33f).forEach { degrees ->
            val transform = PhotoLayerTransform(IntSize(1080, 2400), 2.5f, degrees, Offset(-120f, 64f))
            val local = Offset(300f, 1700f)
            val back = transform.toLocal(transform.toScreen(local))
            assertEquals(local.x, back.x, 0.01f)
            assertEquals(local.y, back.y, 0.01f)
        }
    }

    @Test
    fun layerTransformScalesAroundCenter() {
        val transform = PhotoLayerTransform(IntSize(100, 200), 2f, 0f, Offset(10f, 0f))
        assertEquals(Offset(-40f, -100f), transform.toScreen(Offset(0f, 0f)))
    }

    @Test
    fun fitCenterRectLetterboxes() {
        val rect = fitCenterRect(200f, 100f, IntSize(100, 300))
        assertEquals(0f, rect.left, 0f)
        assertEquals(125f, rect.top, 0f)
        assertEquals(100f, rect.width, 0f)
        assertEquals(50f, rect.height, 0f)
    }

    private fun word(value: String, left: Float, right: Float, top: Float, line: Int) =
        RecognizedWord(value, left, top, right, top + 0.10f, line)
}
