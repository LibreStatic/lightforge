package com.librestatic.lightforge.feature.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.MomentEntity
import com.librestatic.lightforge.core.database.MomentMemberEntity
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MomentContentDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun momentRouteRendersStoryControlsAndRenameSemantics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val moment = MomentEntity(
            momentId = "moment-ui",
            origin = "heuristic",
            state = "ready",
            algorithmVersion = "moments-v1",
            startMillis = 1_700_000_000_000,
            endMillis = 1_700_086_400_000,
            title = "Weekend trip",
            titleMode = "generated",
            isUserEdited = false,
            createdAtMillis = 1_700_000_000_000,
            updatedAtMillis = 1_700_000_000_000,
        )
        val members = (0 until 3).map { ordinal ->
            MomentMemberUi(
                member = MomentMemberEntity(
                    momentId = moment.momentId,
                    ordinal = ordinal,
                    volumeName = "external_primary",
                    mediaStoreId = ordinal.toLong() + 1,
                    generationModifiedAtSelection = 1,
                    origin = "heuristic",
                    score = 1f,
                ),
                key = MediaKey("external_primary", ordinal.toLong() + 1),
            )
        }

        compose.setContent {
            LightforgeTheme(darkTheme = false) {
                MomentContent(
                    moment = moment,
                    members = members,
                    thumbnailLoader = null,
                    dateLabel = "Nov 14–15, 2023",
                    stateLabel = "Generated locally",
                    onBack = {},
                    onSave = {},
                    onDelete = {},
                    onRename = {},
                    onSetCover = {},
                    onReorder = {},
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                )
            }
        }

        compose.onNode(hasText("Weekend trip")).assertIsDisplayed()
        // The story position is below the hero on compact windows; it must remain in the
        // scrollable semantics tree rather than being clipped by the route viewport.
        compose.onNode(hasText(context.getString(R.string.moment_story_position, 1, 3))).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.moment_move_previous)))
        compose.onNode(hasText(context.getString(R.string.moment_move_previous))).assertExists()
        compose.onNode(hasText(context.getString(R.string.moment_move_next))).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.moment_delete)))
        compose.onNode(hasText(context.getString(R.string.moment_delete))).assertExists()
        compose.onNode(hasText(context.getString(R.string.moment_save))).assertExists()
        compose.onNode(hasText(context.getString(R.string.moment_edit))).performClick()
        compose.onNode(hasText(context.getString(R.string.moment_edit_title))).assertIsDisplayed()
        compose.onRoot().captureToImage().also { image ->
            assertTrue(image.width > 0)
            assertTrue(image.height > 0)
        }
    }
}
