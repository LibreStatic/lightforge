package com.librestatic.lightforge.feature.collections

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.AlbumSummary
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionsContentDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compactCoverScreenUsesTwoCollectionColumns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val privateAlbum = "Private album"
        var openedPetQuery: String? = null

        compose.setContent {
            val physicalAlbums = flowOf(PagingData.empty<AlbumSummary>()).collectAsLazyPagingItems()
            val virtualAlbums = flowOf(PagingData.empty<AlbumSummary>()).collectAsLazyPagingItems()
            LightforgeTheme(darkTheme = false) {
                CollectionsContent(
                    physicalAlbums = physicalAlbums,
                    virtualAlbums = virtualAlbums,
                    trashCount = 0,
                    archiveCount = 0,
                    onArchiveClick = {},
                    momentSummaries = emptyList(),
                    onMomentClick = {},
                    onAlbumClick = {},
                    onCreateAlbum = {},
                    onTrashClick = {},
                    onLocalAnalysisClick = {},
                    peopleEnabled = true,
                    peopleCount = 0,
                    onPeopleClick = {},
                    petCollectionsEnabled = true,
                    dogCount = 19,
                    catCount = 15,
                    onPetCollectionClick = { openedPetQuery = it },
                    privateAlbumLabel = privateAlbum,
                    onPrivateAlbumClick = {},
                    modifier = Modifier.width(360.dp).height(1_200.dp),
                )
            }
        }

        // Actions and library entries are compact shortcuts that wrap instead of full-width cards.
        val shortcuts = compose.onNodeWithTag("collections-shortcuts")
        shortcuts.performScrollToNode(hasText(context.getString(R.string.collections_local_analysis)))
        val analysis = compose.onNode(hasText(context.getString(R.string.collections_local_analysis)))
            .getUnclippedBoundsInRoot()
        shortcuts.performScrollToNode(hasText(privateAlbum))
        val second = compose.onNode(hasText(privateAlbum)).getUnclippedBoundsInRoot()
        assertTrue(
            "Shortcuts should be compact chips, not full-width cards",
            analysis.right.value - analysis.left.value < 300f && second.right.value - second.left.value < 300f,
        )

        compose.onNode(hasScrollAction() and !hasTestTag("collections-shortcuts")).performScrollToNode(hasText(context.getString(R.string.collections_dogs)))
        val dogs = compose.onNode(hasText(context.getString(R.string.collections_dogs))).getUnclippedBoundsInRoot()
        val cats = compose.onNode(hasText(context.getString(R.string.collections_cats))).getUnclippedBoundsInRoot()
        assertTrue(
            "Automatic collections should share a row on a 360dp cover screen",
            kotlin.math.abs(dogs.top.value - cats.top.value) < 1f,
        )
        assertTrue("Second automatic collection should be placed beside the first", cats.left > dogs.left)
        assertTrue(
            "Collection tiles should be square like the Photos grid",
            kotlin.math.abs((dogs.bottom.value - dogs.top.value) - (dogs.right.value - dogs.left.value)) < 2f,
        )

        compose.onNode(hasText(context.getString(R.string.collections_dogs))).performClick()
        assertEquals(
            "Automatic collections should open Search with their localized visible title",
            context.getString(R.string.collections_dogs),
            openedPetQuery,
        )
    }
}
