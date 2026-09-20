package com.ugallery.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.preferences.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.feature.collections.CollectionLayoutDialog
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Isolated preference files only; never uses the application's singleton DataStore. */
@RunWith(AndroidJUnit4::class)
class CollectionLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val order = listOf("documents", "virtual-albums", "memories")
    private val hidden = setOf("memories")

    @Test fun layoutUpdateExportImportAndResetSurviveDataStoreReopen() = runBlocking {
        fixture { file ->
            val json = opened(file) { repo ->
                repo.update { it.copy(playback = it.playback.copy(loopVideos = true),
                    library = it.library.copy(sort = LibrarySort.Size, collectionOrder = order, hiddenCollections = hidden)) }
                val before = repo.settings.first()
                assertEquals(order, before.library.collectionOrder)
                assertEquals(hidden, before.library.hiddenCollections)
                repo.exportJson().also {
                    assertEquals(order.joinToString(","), it.getJSONObject("library").getString("collectionOrder"))
                    assertEquals("memories", it.getJSONObject("library").getString("hiddenCollections"))
                }
            }
            opened(file) { repo ->
                assertEquals(order, repo.settings.first().library.collectionOrder)
                assertEquals(hidden, repo.settings.first().library.hiddenCollections)
                val before = repo.settings.first()
                repo.update { it.copy(library = it.library.copy(collectionOrder = emptyList(), hiddenCollections = emptySet())) }
                assertEquals(before.copy(library = before.library.copy(collectionOrder = emptyList(), hiddenCollections = emptySet())), repo.settings.first())
            }
            opened(file) { repo ->
                assertTrue(repo.settings.first().library.collectionOrder.isEmpty())
                assertTrue(repo.settings.first().library.hiddenCollections.isEmpty())
                repo.importJson(JSONObject(json.toString()))
            }
            opened(file) { repo ->
                val restored = repo.settings.first()
                assertEquals(order, restored.library.collectionOrder)
                assertEquals(hidden, restored.library.hiddenCollections)
                assertEquals(LibrarySort.Size, restored.library.sort)
                assertTrue(restored.playback.loopVideos)
            }
            println("COLLECTION_LAYOUT update/export/reset/import/reopen persisted order and hidden global keys; other settings retained")
        }
    }

    @Test fun selectivePortablePresentationRestoresLayoutWithoutChangingOtherGroups() = runBlocking {
        fixture { file ->
            opened(file) { repo ->
                repo.update { it.copy(playback = it.playback.copy(loopVideos = true),
                    security = SecuritySettings(true, true, 15), gestures = it.gestures.copy(onboardingShown = true),
                    library = it.library.copy(folderSelectionMode = FolderSelectionMode.OnlyIncluded,
                        folderRules = mapOf(FolderSelectionTarget.Bucket("fixture-volume", 55) to true))) }
                val before = repo.settings.first()
                val bytes = """{"schemaVersion":5,"library":{"collectionOrder":"documents,virtual-albums,memories","hiddenCollections":"memories"}}""".toByteArray()
                val review = repo.review(bytes, UUID.randomUUID().toString())
                assertEquals(setOf(PortablePreferenceGroup.Presentation), review.availableGroups)
                repo.apply(bytes, review, setOf(PortablePreferenceGroup.Presentation))
                assertEquals(before.copy(library = before.library.copy(collectionOrder = order, hiddenCollections = hidden)), repo.settings.first())
            }
            opened(file) { repo ->
                assertEquals(order, repo.settings.first().library.collectionOrder)
                assertEquals(hidden, repo.settings.first().library.hiddenCollections)
                assertTrue(repo.settings.first().playback.loopVideos)
                for (invalid in listOf("albums,albums", "Albums", "albums,", "a ", (1..65).joinToString(",") { "item-$it" })) {
                    val before = repo.settings.first()
                    val bytes = JSONObject().put("schemaVersion", 5).put("library", JSONObject().put("collectionOrder", invalid)).toString().toByteArray()
                    assertTrue("Malformed portable order accepted: $invalid", runCatching { repo.review(bytes, UUID.randomUUID().toString()) }.isFailure)
                    assertEquals(before, repo.settings.first())
                }
            }
            println("COLLECTION_LAYOUT portable Presentation reviewed/applied/reopened; unrelated groups unchanged; invalid order rejected without mutation")
        }
    }
    @Test fun realDialogMovesHidesShowsResetsAndCancelsDraftBeforeExplicitSave() {
        val defaults = listOf("documents", "people", "archive", "trash", "virtual-albums", "physical-albums",
            "dogs", "cats", "memories", "all-memories", "create-album", "private-album",
            "memory-controls", "smart-albums", "photo-stacks", "pdf-studio", "collage", "local-analysis")
        val first = defaults.first()
        var visible by mutableStateOf(true)
        var saved: Pair<List<String>, Set<String>>? = null
        var cancellations = 0
        compose.setContent {
            UGalleryTheme(darkTheme = false, dynamicColor = false) {
                if (visible) CollectionLayoutDialog(order = defaults, hidden = emptySet(),
                    labels = defaults.associateWith { it }, available = defaults.toSet(), working = false, failed = false,
                    onSave = { order, hidden -> saved = order to hidden },
                    onDismiss = { cancellations++; visible = false })
            }
        }
        fun show(tag: String) {
            compose.onNodeWithTag("collection-layout-list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        fun tap(tag: String) { show(tag); compose.onNodeWithTag(tag).performTouchInput { click() } }
        show("collection-layout-up-$first")
        compose.onNodeWithTag("collection-layout-up-$first").assertIsNotEnabled()
        tap("collection-layout-down-$first")
        show("collection-layout-up-$first")
        compose.onNodeWithTag("collection-layout-up-$first").assertIsEnabled()
        tap("collection-layout-up-$first")
        compose.onNodeWithTag("collection-layout-up-$first").assertIsNotEnabled()
        tap("collection-layout-toggle-$first")
        compose.onNodeWithTag("collection-layout-toggle-$first").assertIsOff()
        tap("collection-layout-show-all")
        show("collection-layout-toggle-$first")
        compose.onNodeWithTag("collection-layout-toggle-$first").assertIsOn()
        tap("collection-layout-down-$first")
        tap("collection-layout-toggle-$first")
        tap("collection-layout-reset")
        show("collection-layout-up-$first")
        compose.onNodeWithTag("collection-layout-up-$first").assertIsNotEnabled()
        compose.onNodeWithTag("collection-layout-toggle-$first").assertIsOn()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("collection-layout-cancel").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, cancellations); assertNull(saved); visible = true }
        show("collection-layout-up-$first")
        compose.onNodeWithTag("collection-layout-up-$first").assertIsNotEnabled()
        tap("collection-layout-down-$first")
        tap("collection-layout-toggle-$first")
        compose.onNodeWithTag("collection-layout-save").performTouchInput { click() }
        val expected = defaults.toMutableList().apply { removeAt(0); add(1, first) }
        compose.runOnIdle { assertEquals(expected to setOf(first), saved); assertEquals(1, cancellations) }
        println("COLLECTION_LAYOUT_DIALOG real touch up/down/hide/show-all/reset/cancel/save; draft emits only explicit Save; no storage writes")
    }

    private suspend fun <T> opened(file: File, block: suspend (GallerySettingsRepository) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Exercise the real runtime factory without promoting an implementation dependency
            // into the test APK (the target app already packages DataStore).
            val factory = Class.forName("androidx.datastore.preferences.core.PreferenceDataStoreFactory")
            val create = factory.methods.single { it.name == "create" && it.parameterCount == 4 &&
                it.parameterTypes.last().name == "kotlin.jvm.functions.Function0" }
            val store = create.invoke(factory.getField("INSTANCE").get(null), null, emptyList<Any>(), scope, { file })
            val constructor = GallerySettingsRepository::class.java.constructors.single {
                it.parameterCount == 1 && it.parameterTypes.single().name == "androidx.datastore.core.DataStore"
            }
            return block(constructor.newInstance(store) as GallerySettingsRepository)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    private suspend fun fixture(block: suspend (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "collection-layout-" + UUID.randomUUID())
        check(root.mkdir())
        try { block(File(root, "fixture.preferences_pb")) }
        finally { check(root.deleteRecursively()); check(!root.exists()) }
    }
}
