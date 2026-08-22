package com.ugallery.feature.search

import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ugallery.core.search.SearchConcept
import com.ugallery.core.search.SearchVocabulary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SearchLocalizationDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyLocalizedSuggestionResolvesToItsCanonicalConcept() {
        val expectations = mapOf(
            R.string.search_photos to SearchConcept.Image,
            R.string.search_videos to SearchConcept.Video,
            R.string.search_me to SearchConcept.Me,
            R.string.search_people to SearchConcept.People,
            R.string.search_cats to SearchConcept.Cat,
            R.string.search_dogs to SearchConcept.Dog,
            R.string.search_coast to SearchConcept.Beach,
            R.string.search_mountain to SearchConcept.Mountain,
            R.string.search_city to SearchConcept.City,
            R.string.search_rain to SearchConcept.Rain,
            R.string.search_documents to SearchConcept.Document,
            R.string.search_screenshots to SearchConcept.Screenshot,
            R.string.search_video to SearchConcept.Video,
            R.string.search_camera to SearchConcept.Camera,
            R.string.search_landscapes to SearchConcept.Landscape,
            R.string.search_food to SearchConcept.Food,
        )
        val baseContext = ApplicationProvider.getApplicationContext<android.content.Context>()

        listOf("en", "es", "fr", "pt", "it", "de").forEach { languageTag ->
            val configuration = android.content.res.Configuration(baseContext.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(languageTag)))
            }
            val resources = baseContext.createConfigurationContext(configuration).resources
            expectations.forEach { (resource, concept) ->
                val label = resources.getString(resource)
                assertEquals("$languageTag: $label", concept, SearchVocabulary.resolve(label))
            }
        }
    }

    @Test
    fun chipsSubmitExactlyTheLocalizedLabelTheyDisplay() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val documents = context.getString(R.string.search_documents)
        val dogs = context.getString(R.string.search_dogs)
        var submitted: String? = null

        compose.setContent {
            MaterialTheme {
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
                    onPresetSearch = { submitted = it },
                    onLoadMore = {},
                    onHit = {},
                    onEnableDetectedContent = {},
                    onPauseDetectedContent = {},
                    onDeleteDetectedContent = {},
                )
            }
        }

        compose.onAllNodesWithText(documents)[0].performClick()
        compose.runOnIdle { assertEquals(documents, submitted) }
        compose.onNodeWithText(dogs).performClick()
        compose.runOnIdle { assertEquals(dogs, submitted) }
    }
}
