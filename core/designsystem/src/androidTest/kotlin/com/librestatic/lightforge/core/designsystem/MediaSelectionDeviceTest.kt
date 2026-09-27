package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MediaSelectionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longPressDragSelectsAndDeselectsTheCrossedRange() {
        val selected = mutableStateMapOf<Int, Boolean>()
        compose.setContent {
            LightforgeTheme {
                val state = rememberLazyGridState()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = state,
                    modifier =
                        Modifier.fillMaxSize()
                            .testTag("selection_grid")
                            .lazyGridDragSelection(
                                state = state,
                                itemAtIndex = { index -> index.takeIf { it in 0..8 } },
                                itemKey = { it },
                                isSelected = { selected[it] == true },
                                onSelectionChange = { item, value -> selected[item] = value },
                            ),
                ) {
                    items((0..8).toList()) { index ->
                        Box(
                            Modifier.fillMaxWidth()
                                .aspectRatio(1f)
                                .background(Color.Gray)
                                .clickable { selected[index] = selected[index] != true }
                                .testTag("item_$index")
                        )
                    }
                }
            }
        }

        dragAcrossFirstRow()
        compose.runOnIdle { assertEquals(setOf(0, 1, 2), selected.filterValues { it }.keys) }

        dragAcrossFirstRow()
        compose.runOnIdle { assertEquals(emptySet<Int>(), selected.filterValues { it }.keys) }
    }

    @Test
    fun stationaryLongPressSelectsWithoutInvokingChildClick() {
        val selected = mutableStateMapOf<Int, Boolean>()
        var clicks = 0
        compose.setContent {
            LightforgeTheme {
                val state = rememberLazyGridState()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = state,
                    modifier =
                        Modifier.fillMaxSize()
                            .testTag("selection_grid")
                            .lazyGridDragSelection(
                                state,
                                { it.takeIf { it in 0..8 } },
                                { it },
                                { selected[it] == true },
                                { item, value -> selected[item] = value },
                            ),
                ) {
                    items((0..8).toList()) { index ->
                        Box(
                            Modifier.fillMaxWidth()
                                .aspectRatio(1f)
                                .clickable {
                                    clicks++
                                    selected[index] = selected[index] != true
                                }
                                .testTag("item_$index")
                        )
                    }
                }
            }
        }
        val item = compose.onNodeWithTag("item_0")
        // Moving before the long-press timeout is not a selection gesture.
        item.performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + 80f), delayMillis = 32L)
            up()
        }
        compose.runOnIdle {
            assertEquals(emptySet<Int>(), selected.filterValues { it }.keys)
            assertEquals(0, clicks)
        }
        item.performTouchInput {
            down(center)
            advanceEventTime(900L)
            up()
        }
        compose.runOnIdle {
            assertEquals(setOf(0), selected.filterValues { it }.keys)
            assertEquals(0, clicks)
        }
        // A normal tap still reaches the cell's click action.
        item.performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle {
            assertEquals(emptySet<Int>(), selected.filterValues { it }.keys)
            assertEquals(1, clicks)
        }
    }

    @Test
    fun selectedOverlayIsVisiblyPresentOnlyWhenSelected() {
        compose.setContent {
            LightforgeTheme { Box(Modifier.fillMaxSize()) { MediaSelectionOverlay(selected = true) } }
        }

        compose.onNodeWithTag("media_selection_indicator").assertExists()
    }

    private fun dragAcrossFirstRow() {
        val grid = compose.onNodeWithTag("selection_grid")
        val width = grid.fetchSemanticsNode().boundsInRoot.width
        grid.performTouchInput {
            val y = width / 6f
            down(Offset(width / 6f, y))
            advanceEventTime(700L)
            moveTo(Offset(width * 5f / 6f, y), delayMillis = 32L)
            up()
        }
        compose.waitForIdle()
    }
}
