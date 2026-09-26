package com.ugallery.feature.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.model.MediaKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeopleContentDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun peopleAndMeControlsAreExplicitAndNonCommercial() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var consent by mutableStateOf(false)
        var selected by mutableStateOf<PersonCardUi?>(null)
        var setMeCount by mutableIntStateOf(0)
        var deleteCount by mutableIntStateOf(0)
        val person = PersonCardUi(
            clusterId = "person-a",
            displayName = "Alex",
            memberCount = 4,
            coverKey = MediaKey("external_primary", 42),
        )
        val members = listOf(PersonMemberCardUi(MediaKey("external_primary", 42), 0))

        compose.setContent {
            UGalleryTheme(darkTheme = false) {
                PeopleContent(
                    state = PeopleUiState(
                        consentGranted = consent,
                        running = consent,
                        analysisStage = PeopleAnalysisStage.FaceDetection,
                        completedItems = 12,
                        people = listOf(person),
                        selectedPerson = selected,
                        selectedMembers = if (selected == null) emptyList() else members,
                        me = if (setMeCount == 0) null else LocalMeUiState(1, true, 4, members),
                    ),
                    thumbnailLoader = null,
                    onBack = {},
                    onEnable = { consent = true },
                    onPause = {},
                    onResume = {},
                    onAnalyzeAll = {},
                    onDeleteAll = { deleteCount++ },
                    onPersonClick = { selected = person },
                    onRenamePerson = { _, _ -> },
                    onHidePerson = {},
                    onSetSelectedAsMe = { setMeCount++ },
                    onResetMe = { setMeCount = 0 },
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                )
            }
        }

        compose.onNode(hasText(context.getString(R.string.people_noncommercial_notice))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.people_enable))).performClick()
        compose.onNode(hasText(context.resources.getQuantityString(R.plurals.people_progress, 12, 12))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.people_status_detecting_faces))).assertIsDisplayed()
        compose.onNode(hasText("Alex")).performClick()
        compose.onNode(hasText(context.getString(R.string.me_set_from_person))).performClick()
        assertEquals(1, setMeCount)
        compose.onNode(hasText(context.getString(
            R.string.me_summary,
            context.resources.getQuantityString(R.plurals.me_summary_references, 1, 1),
            context.resources.getQuantityString(R.plurals.me_summary_matches, 4, 4),
        ))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.people_delete_all))).performClick()
        compose.onNode(hasText(context.getString(R.string.people_delete_body))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.people_delete_confirm))).performClick()
        assertEquals(1, deleteCount)
    }

    @Test
    fun compactCoverScreenUsesTwoPersonColumns() {
        val people = listOf(
            PersonCardUi("person-a", "Alex", 4, null),
            PersonCardUi("person-b", "Sam", 3, null),
        )
        compose.setContent {
            UGalleryTheme(darkTheme = false) {
                PeopleContent(
                    state = PeopleUiState(consentGranted = true, people = people),
                    thumbnailLoader = null,
                    onBack = {}, onEnable = {}, onPause = {}, onResume = {}, onAnalyzeAll = {},
                    onDeleteAll = {}, onPersonClick = {}, onRenamePerson = { _, _ -> },
                    onHidePerson = {}, onSetSelectedAsMe = {}, onResetMe = {},
                    modifier = Modifier.width(360.dp).height(1_200.dp),
                )
            }
        }

        val first = compose.onNodeWithTag("person_person-a").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("person_person-b").getUnclippedBoundsInRoot()
        assertTrue("Person cards should share a row on a 360dp cover screen", kotlin.math.abs(first.top.value - second.top.value) < 1f)
        assertTrue("Second card should be placed beside the first", second.left > first.left)
    }
}
