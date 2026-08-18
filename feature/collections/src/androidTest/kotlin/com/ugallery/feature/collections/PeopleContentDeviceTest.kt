package com.ugallery.feature.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.model.MediaKey
import org.junit.Assert.assertEquals
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
        compose.onNode(hasText(context.getString(R.string.people_progress, 12))).assertIsDisplayed()
        compose.onNode(hasText("Alex")).performClick()
        compose.onNode(hasText(context.getString(R.string.me_set_from_person))).performClick()
        assertEquals(1, setMeCount)
        compose.onNode(hasText(context.getString(R.string.me_summary, 1, 4))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.people_delete_all))).performClick()
        compose.onNode(hasText(context.getString(R.string.people_delete_body))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.people_delete_confirm))).performClick()
        assertEquals(1, deleteCount)
    }
}
