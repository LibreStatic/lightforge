package com.ugallery.feature.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SEARCH_LAYOUT_HOST_TEST_TAG = "search_layout_host"

@RunWith(AndroidJUnit4::class)
class SearchLayoutDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compactSearchContentKeepsTwentyDpFromBothScreenEdges() {
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .width(344.dp)
                        .height(700.dp)
                        .testTag(SEARCH_LAYOUT_HOST_TEST_TAG),
                ) {
                    SearchUnderTest()
                }
            }
        }

        val hostBounds = compose.onNodeWithTag(SEARCH_LAYOUT_HOST_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val contentBounds = compose.onNodeWithTag(SEARCH_CONTENT_COLUMN_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val expectedGutter = with(compose.density) { 20.dp.toPx() }

        assertEquals(expectedGutter, contentBounds.left - hostBounds.left, 0.5f)
        assertEquals(expectedGutter, hostBounds.right - contentBounds.right, 0.5f)
    }

    @Test
    fun expandedSearchDiscoveryUsesTheResponsiveScreenGutter() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            MaterialTheme { SearchUnderTest() }
        }

        compose.onNodeWithText(context.getString(R.string.search_hint)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(SEARCH_EXPANDED_DISCOVERY_HEADING_TEST_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }

        val rootWidth = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .maxOf { it.boundsInRoot.width }
        val expectedGutter = with(compose.density) {
            searchHorizontalGutter(rootWidth.toDp()).toPx()
        }
        val headingLeft = compose.onNodeWithTag(SEARCH_EXPANDED_DISCOVERY_HEADING_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot.left

        assertEquals(expectedGutter, headingLeft, 0.5f)
    }
}

@androidx.compose.runtime.Composable
private fun SearchUnderTest() {
    SearchContent(
        query = "",
        hits = emptyList(),
        loading = false,
        terminal = true,
        partialIndex = false,
        error = false,
        detectedContentEnabled = false,
        thumbnailLoader = null,
        onQueryChange = {},
        onSearch = {},
        onPresetSearch = {},
        onLoadMore = {},
        onHit = {},
        onEnableDetectedContent = {},
        onPauseDetectedContent = {},
        onDeleteDetectedContent = {},
    )
}
