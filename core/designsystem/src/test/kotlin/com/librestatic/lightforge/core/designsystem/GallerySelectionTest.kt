package com.librestatic.lightforge.core.designsystem

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class GallerySelectionTest {
    @Test
    fun allInlineActionsFitOnAWideBar() {
        assertEquals(5, selectionBarInlineCount(1200.dp, inlineCandidates = 5, hasMenuOnlyActions = true))
        assertEquals(4, selectionBarInlineCount(1200.dp, inlineCandidates = 4, hasMenuOnlyActions = false))
    }

    @Test
    fun phoneBarKeepsASlotForOverflow() {
        // 393 dp phone minus the bar's 12 dp side margins: four slots after close + count.
        assertEquals(3, selectionBarInlineCount(369.dp, inlineCandidates = 5, hasMenuOnlyActions = true))
        assertEquals(4, selectionBarInlineCount(369.dp, inlineCandidates = 4, hasMenuOnlyActions = false))
        assertEquals(3, selectionBarInlineCount(369.dp, inlineCandidates = 5, hasMenuOnlyActions = false))
    }

    @Test
    fun barNeverReportsNegativeOrExtraSlots() {
        assertEquals(0, selectionBarInlineCount(100.dp, inlineCandidates = 5, hasMenuOnlyActions = true))
        assertEquals(0, selectionBarInlineCount(1200.dp, inlineCandidates = 0, hasMenuOnlyActions = true))
    }

    @Test
    fun widthIsCappedAtTheBarMaximum() {
        assertEquals(
            selectionBarInlineCount(GallerySelectionBarDefaults.MaxWidth, 20, true),
            selectionBarInlineCount(4000.dp, 20, true),
        )
    }

    @Test
    fun clickRulesFollowDesktopConventions() {
        val plain = MediaClickModifiers()
        assertEquals(MediaTileClick.Open, mediaTileClick(selectionMode = false, plain))
        assertEquals(MediaTileClick.Toggle, mediaTileClick(selectionMode = true, plain))
        assertEquals(MediaTileClick.Toggle, mediaTileClick(false, MediaClickModifiers(toggle = true)))
        assertEquals(MediaTileClick.ExtendRange, mediaTileClick(false, MediaClickModifiers(extend = true)))
        assertEquals(MediaTileClick.ExtendRange, mediaTileClick(true, MediaClickModifiers(extend = true, toggle = true)))
    }

    @Test
    fun rangeSkipsHeadersAndUnloadedRowsInEitherDirection() {
        // Index 0 and 4 are headers, index 2 is not loaded yet.
        val rows = listOf("h", "a", null, "b", "h", "c")
        val pick = { row: String -> row.takeIf { it != "h" } }
        assertEquals(listOf("a", "b", "c"), selectionRange(1, 5, rows::getOrNull, pick))
        assertEquals(listOf("a", "b", "c"), selectionRange(5, 0, rows::getOrNull, pick))
        assertEquals(listOf("b"), selectionRange(3, 3, rows::getOrNull, pick))
        assertEquals(emptyList<String>(), selectionRange(-1, 3, rows::getOrNull, pick))
    }
}
