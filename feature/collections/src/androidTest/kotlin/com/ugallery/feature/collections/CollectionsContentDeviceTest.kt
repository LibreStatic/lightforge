package com.ugallery.feature.collections

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.model.AlbumSummary
import kotlinx.coroutines.flow.flowOf
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

        compose.setContent {
            val physicalAlbums = flowOf(PagingData.empty<AlbumSummary>()).collectAsLazyPagingItems()
            val virtualAlbums = flowOf(PagingData.empty<AlbumSummary>()).collectAsLazyPagingItems()
            UGalleryTheme(darkTheme = false) {
                CollectionsContent(
                    physicalAlbums = physicalAlbums,
                    virtualAlbums = virtualAlbums,
                    trashCount = 0,
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
                    onPetCollectionClick = {},
                    privateAlbumLabel = privateAlbum,
                    onPrivateAlbumClick = {},
                    modifier = Modifier.width(360.dp).height(1_200.dp),
                )
            }
        }

        val first = compose.onNode(hasText(context.getString(R.string.collections_local_analysis)))
            .getUnclippedBoundsInRoot()
        val second = compose.onNode(hasText(privateAlbum)).getUnclippedBoundsInRoot()
        assertTrue("Descriptive cards should occupy separate rows", second.top >= first.bottom)
        assertTrue(
            "Descriptive cards should use the same full width",
            kotlin.math.abs(first.left.value - second.left.value) < 1f,
        )
        assertTrue(
            "Descriptive cards should use the same full width",
            kotlin.math.abs(first.right.value - second.right.value) < 1f,
        )

        val dogs = compose.onNode(hasText(context.getString(R.string.collections_dogs))).getUnclippedBoundsInRoot()
        val cats = compose.onNode(hasText(context.getString(R.string.collections_cats))).getUnclippedBoundsInRoot()
        assertTrue(
            "Automatic collections should share a row on a 360dp cover screen",
            kotlin.math.abs(dogs.top.value - cats.top.value) < 1f,
        )
        assertTrue("Second automatic collection should be placed beside the first", cats.left > dogs.left)
        assertTrue(
            "Automatic collections in the same row should have equal heights",
            kotlin.math.abs(
                (dogs.bottom.value - dogs.top.value) - (cats.bottom.value - cats.top.value),
            ) < 1f,
        )
        assertTrue(
            "Short automatic collections should not retain the former fixed height",
            dogs.bottom.value - dogs.top.value < 216f,
        )
    }
}
