package com.librestatic.lightforge.feature.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.search.MediaSearchHit
import com.librestatic.lightforge.core.search.SearchRankingDebug
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchPaginationDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun approachingLastThreeResultsRequestsExactlyOneAdditionalPage() {
        val results = searchHits(50)
        var requests = 0
        compose.setContent {
            SearchUnderTest(
                hits = results,
                loading = false,
                terminal = false,
                onLoadMore = { requests += 1 },
            )
        }

        compose.onNodeWithTag(SEARCH_RESULTS_GRID_TEST_TAG)
            .performScrollToIndex(results.size - 3)
        compose.waitUntil(timeoutMillis = 5_000) { requests == 1 }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun loadingShowsCenteredFullWidthPaginationIndicatorAndBlocksRequests() {
        val results = searchHits(50)
        var requests = 0
        compose.setContent {
            SearchUnderTest(
                hits = results,
                loading = true,
                terminal = false,
                onLoadMore = { requests += 1 },
            )
        }

        compose.onNodeWithTag(SEARCH_RESULTS_GRID_TEST_TAG)
            .performScrollToIndex(results.size)
        compose.onNodeWithTag(SEARCH_LOADING_INDICATOR_TEST_TAG).assertIsDisplayed()
        assertLoadingIndicatorIsCentered()
        val gridBounds = compose.onNodeWithTag(SEARCH_RESULTS_GRID_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val rowBounds = compose.onNodeWithTag(SEARCH_LOADING_ROW_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot

        compose.runOnIdle {
            assertEquals(0, requests)
            assertEquals(gridBounds.width, rowBounds.width, 0.5f)
        }
    }

    @Test
    fun initialLoadingShowsCenteredOfficialIndicator() {
        compose.setContent {
            SearchUnderTest(
                hits = emptyList(),
                loading = true,
                terminal = false,
            )
        }

        compose.onNodeWithTag(SEARCH_LOADING_INDICATOR_TEST_TAG).assertIsDisplayed()
        assertLoadingIndicatorIsCentered()
    }

    @Test
    fun terminalGridHasNoFooterAndDoesNotRequestMore() {
        val results = searchHits(50)
        var requests = 0
        var loading by mutableStateOf(false)
        compose.setContent {
            SearchUnderTest(
                hits = results,
                loading = loading,
                terminal = true,
                onLoadMore = { requests += 1 },
            )
        }

        compose.onNodeWithTag(SEARCH_RESULTS_GRID_TEST_TAG)
            .performScrollToIndex(results.lastIndex)
        compose.waitForIdle()

        compose.onNodeWithTag(SEARCH_LOADING_ROW_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithText("Load more").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, requests) }

        compose.runOnIdle { loading = true }
        compose.waitForIdle()
        compose.onNodeWithTag(SEARCH_LOADING_ROW_TEST_TAG).assertDoesNotExist()
    }

    private fun assertLoadingIndicatorIsCentered() {
        val rowCenter = compose.onNodeWithTag(SEARCH_LOADING_ROW_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot.center.x
        val indicatorCenter = compose.onNodeWithTag(SEARCH_LOADING_INDICATOR_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot.center.x
        compose.runOnIdle { assertEquals(rowCenter, indicatorCenter, 0.5f) }
    }
}

@Composable
private fun SearchUnderTest(
    hits: List<MediaSearchHit>,
    loading: Boolean,
    terminal: Boolean,
    onLoadMore: () -> Unit = {},
) {
    MaterialTheme {
        SearchContent(
            query = "dogs",
            hits = hits,
            loading = loading,
            terminal = terminal,
            partialIndex = false,
            error = false,
            detectedContentEnabled = false,
            thumbnailLoader = null,
            onQueryChange = {},
            onSearch = {},
            onPresetSearch = {},
            onLoadMore = onLoadMore,
            onHit = {},
            onEnableDetectedContent = {},
            onPauseDetectedContent = {},
            onDeleteDetectedContent = {},
        )
    }
}

private fun searchHits(count: Int): List<MediaSearchHit> = List(count) { index ->
    MediaSearchHit(
        key = MediaKey("external_primary", index.toLong()),
        kind = MediaKind.Image,
        displayName = "result-$index.jpg",
        timelineSortMillis = index.toLong(),
        generationModified = 1L,
        favorite = false,
        debug = SearchRankingDebug("dogs", "test", 1.0, emptyList()),
    )
}
