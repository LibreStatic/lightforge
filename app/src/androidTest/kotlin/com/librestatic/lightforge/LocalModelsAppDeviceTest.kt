package com.librestatic.lightforge

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.feature.semanticsearch.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern

/** A signed local pack, the production scanner/index worker, real queries, and Settings lifecycle. */
class LocalModelsAppDeviceTest {
    @Test fun signedLocalPackActivatesIndexesSearchesPersistsAndDeletesWithoutChangingPhotos() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        check(InstrumentationRegistry.getArguments().getString("modelFixture") == "lightforge-local-models")
        val descriptor = SemanticModelCatalog.models.single { it.id == "tinyclip-balanced" }
        val profile = SemanticModelSelector.profile(context)
        assertNotEquals("Real device RAM/ABI must satisfy the existing production gate",
            SemanticModelCompatibility.TechnicallyUnsupported, SemanticModelSelector.compatibility(descriptor, profile))
        val device = UiDevice.getInstance(instrumentation)
        val resolver = context.contentResolver
        val fixture = File(context.filesDir, "model-fixtures")
        val archive = File(fixture, "tinyclip-balanced-1.0.0.ugmodel")
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(descriptor.packageSha256, sha(archive.readBytes()))
        val name = "local-models-${UUID.randomUUID()}"
        val evidence = File(context.filesDir, name).apply { mkdirs() }
        val sources = mutableListOf<Uri>()
        val originalHashes = mutableListOf<String>()
        val displayNames = mutableListOf<String>()
        val prefs = context.getSharedPreferences("semantic-model-settings", 0)
        fun capture(step: String) {
            device.takeScreenshot(File(evidence, "$step.png"))
            device.dumpWindowHierarchy(File(evidence, "$step.xml"))
        }
        fun await(description: String, timeout: Long = 30_000, ready: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < deadline) {
                if (runCatching(ready).getOrDefault(false)) return
                SystemClock.sleep(150)
            }
            capture("timeout-before-close")
            error("Timed out: $description; semantic preferences=${prefs.all}")
        }
        fun find(selector: BySelector, direction: Direction = Direction.DOWN): UiObject2 {
            val deadline = SystemClock.elapsedRealtime() + 25_000
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it }
                    device.findObjects(By.scrollable(true)).maxByOrNull {
                        it.visibleBounds.width().toLong() * it.visibleBounds.height()
                    }?.scroll(direction, .6f)
                } catch (_: StaleObjectException) { }
                device.waitForIdle()
            }
            capture("missing-control-before-close")
            error("Missing local-model control $selector")
        }
        fun click(selector: BySelector, direction: Direction = Direction.DOWN) {
            var clicked = false
            repeat(3) {
                if (!clicked) try { find(selector.enabled(true), direction).click(); clicked = true }
                catch (stale: StaleObjectException) { if (it == 2) throw stale }
            }
            device.waitForIdle()
        }
        fun label(id: Int) = By.text(context.getString(id))
        fun openModels() {
            click(By.desc(context.getString(com.librestatic.lightforge.feature.photos.R.string.open_settings)))
            await("Settings route, before scrolling") { device.hasObject(label(com.librestatic.lightforge.feature.settings.R.string.settings_title)) }
            click(label(com.librestatic.lightforge.feature.settings.R.string.settings_page_ai))
            await("Local analysis subpage, before scrolling") { device.hasObject(label(com.librestatic.lightforge.feature.settings.R.string.settings_page_ai)) }
        }
        fun closeSettings() {
            device.pressBack()
            await("Settings root") { device.hasObject(label(com.librestatic.lightforge.feature.settings.R.string.settings_title)) }
            device.pressBack()
            await("Photos root") { device.hasObject(By.desc(context.getString(com.librestatic.lightforge.feature.photos.R.string.open_settings))) }
        }
        fun navigateRoot(vararg labelIds: Int) {
            val selector = By.text(Pattern.compile(labelIds.joinToString("|") { Pattern.quote(context.getString(it)) }))
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    // Expanded navigation is a narrow left rail; compact navigation is the bottom dock.
                    // Search also has a Photos chip, so a matching label alone is not a navigation target.
                    val rail = device.findObjects(By.scrollable(true)).firstOrNull {
                        val bounds = it.visibleBounds
                        bounds.left == 0 && bounds.width() < device.displayWidth / 3
                    }
                    val candidates = if (rail != null) rail.findObjects(selector.enabled(true)) else
                        device.findObjects(selector.enabled(true)).filter { it.visibleBounds.top > device.displayHeight * 4 / 5 }
                    candidates.firstOrNull { !it.visibleBounds.isEmpty }?.let { node ->
                        node.click(); device.waitForIdle(); return
                    }
                    rail?.scroll(Direction.UP, .8f)
                } catch (_: StaleObjectException) { }
                SystemClock.sleep(150)
            }
            capture("missing-navigation-before-close")
            error("Missing real root navigation $selector")
        }
        fun search(scenario: ActivityScenario<MainActivity>, query: String, expectedName: String) {
            // Compact dock says Search; expanded rail says Ask and routes to the same RootTab.Search.
            if (!device.hasObject(By.clazz("android.widget.EditText"))) navigateRoot(R.string.nav_search, R.string.nav_ask)
            await("Search input") { device.hasObject(By.clazz("android.widget.EditText")) }
            click(By.clazz("android.widget.EditText"))
            await("Search input has real focus") { device.hasObject(By.clazz("android.widget.EditText").focused(true)) }
            lateinit var searchViewModel: GalleryViewModel
            scenario.onActivity { searchViewModel = ViewModelProvider(it)[GalleryViewModel::class.java] }
            var typed = false
            repeat(3) { if (!typed) try {
                requireNotNull(device.findObject(By.clazz("android.widget.EditText").focused(true))).text = query
                typed = true
            } catch (stale: StaleObjectException) { if (it == 2) throw stale } }
            await("Focused exact text propagated to the production query state") {
                device.hasObject(By.clazz("android.widget.EditText").focused(true).text(query)) &&
                    searchViewModel.search.value.query == query
            }
            val language = if (query.startsWith("una")) "spanish" else "english"
            capture("before-submit-$language") // Real focused node and exact text, before any key event.
            device.pressEnter() // Normal IME action; the observer below never calls or edits the ViewModel.
            await("Production search callback started or completed") {
                val state = searchViewModel.search.value
                state.query == query && (state.loading || state.terminal || state.hits.isNotEmpty() || state.error)
            }
            val submitted = searchViewModel.search.value
            File(evidence, "submitted-$language.json").writeText(JSONObject().put("query", submitted.query)
                .put("loading", submitted.loading).put("terminal", submitted.terminal)
                .put("hits", submitted.hits.size).put("error", submitted.error).toString(2))
            assertFalse("Production search returned an error", submitted.error)
            // Queries update through the real Compose text state; this is not a keyword-named fixture.
            await("Real semantic result $expectedName", 60_000) { device.hasObject(By.desc(expectedName)) }
            capture(if (query.startsWith("una")) "search-spanish" else "search-english")
            if (device.hasObject(By.pkg("com.google.android.inputmethod.latin")) || device.hasObject(By.pkg("com.android.inputmethod.latin"))) device.pressBack()
        }
        fun removeOwnedBalancedFixture(step: String) {
            check(context.packageName == "com.librestatic.lightforge.pdfacceptance" && descriptor.id == "tinyclip-balanced")
            val cleanupDatabase = GalleryDatabaseFactory.open(context)
            try {
                SemanticModelManager(context, cleanupDatabase).use { manager ->
                    manager.deleteModel(descriptor.id)
                    await("Owned Balanced fixture removed: $step") {
                        prefs.getString("active_model", null) != descriptor.id &&
                            prefs.getString("pending_model", null) != descriptor.id &&
                            !File(context.filesDir, "semantic-models/${descriptor.id}-${descriptor.version}").exists() &&
                            runBlocking { cleanupDatabase.semanticDao().indexes().none { it.modelId == descriptor.id } }
                    }
                }
            } finally { cleanupDatabase.close() }
            File(evidence, "$step.json").writeText(JSONObject().put("removedOwnedModel", descriptor.id)
                .put("package", context.packageName).put("status", "PASS").toString(2))
        }
        var primaryFailure: Throwable? = null
        try {
            // This acceptance-only pack can remain active after a captured failure in an earlier run.
            // Reset only that model through its public lifecycle, never the gallery or other packages.
            removeOwnedBalancedFixture("setup-cleanup")
            SemanticModelPackages.installVerified(context, descriptor.id, archive)
            val corpus = listOf("cat.jpg" to "2533197401eebe9410ea4d063f86c43fbd2666f3e8165a38aca155c0d09c21be",
                "burger.jpg" to "97c15bbbf3cf3615063b1031c85d669de55839f59262bbe145d15ca75b36ecbf",
                "penguins_large.jpg" to "3a7a74bf946b3e2b53a3953516a552df854b2854c91b3372d2d6343497ca2160")
            corpus.forEachIndexed { index, (file, expectedSha) ->
                val bytes = File(fixture, file).readBytes()
                assertEquals(expectedSha, sha(bytes))
                val displayName = "$name-$index.jpg"
                val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$name")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
                sources += uri
                requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
                assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null))
                displayNames += displayName
                originalHashes += expectedSha
            }
            val database = GalleryDatabaseFactory.open(context)
            try {
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                    try {
                        val keys = sources.map { "external_primary:${it.lastPathSegment}" }
                        await("Production scanner sees owned corpus", 90_000) { runBlocking { database.semanticDao().mediaForEncodedKeys(keys).size == 3 } }
                        openModels()
                        click(By.res("semantic_model_use_${descriptor.id}"))
                        if (SemanticModelSelector.compatibility(descriptor, profile) == SemanticModelCompatibility.Supported) {
                            click(By.res("semantic_model_override_confirm"))
                        }
                        await("Real native full-library index active", 300_000) {
                            val id = prefs.getString("active_index", null)
                            id != null && prefs.getString("active_model", null) == descriptor.id &&
                                runBlocking { database.semanticDao().index(id)?.status == "active" &&
                                    database.semanticDao().embeddingPage(id, 0, 20_000).count { "${it.volumeName}:${it.mediaStoreId}" in keys && it.quantizedVector.any { byte -> byte != 0.toByte() } } == 3 }
                        }
                        val indexId = requireNotNull(prefs.getString("active_index", null))
                        capture("activated")
                        closeSettings()
                        search(scenario, "una hamburguesa", displayNames[1])
                        scenario.recreate()
                        await("Recreated Search input") { device.hasObject(By.clazz("android.widget.EditText")) }
                        assertEquals(indexId, prefs.getString("active_index", null))
                        search(scenario, "a photo of a hamburger", displayNames[1])
                        // Assert semantic provenance and rank independently of keyword/OCR fusion.
                        SemanticModelManager(context, database).use { manager ->
                            SemanticSearchEngine(context, database, manager).use { engine ->
                                val hits = runBlocking { engine.search("a photo of a hamburger") }
                                val own = hits.filter { it.displayName in displayNames }
                                assertEquals(displayNames[1], own.first().displayName)
                                assertTrue(own.first().debug.matchedProperties.contains("semanticEmbedding"))
                            }
                        }
                        navigateRoot(R.string.nav_photos)
                        openModels()
                        click(By.res("semantic_model_delete_${descriptor.id}"))
                        click(By.res("semantic_model_delete_confirm"))
                        await("Deleted active index, native package and preference") {
                            prefs.getString("active_index", null) == null &&
                                !File(context.filesDir, "semantic-models/${descriptor.id}-${descriptor.version}").exists() &&
                                runBlocking { database.semanticDao().indexes().none { it.modelId == descriptor.id } }
                        }
                        sources.forEachIndexed { index, uri -> assertEquals(originalHashes[index], sha(requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() })) }
                        assertEquals(descriptor.packageSha256, sha(archive.readBytes()))
                        capture("deleted-originals-intact")
                        File(evidence, "result.json").writeText(JSONObject().put("status", "PASS")
                            .put("model", descriptor.id).put("packageSha256", descriptor.packageSha256)
                            .put("ramMiB", profile.totalRamMb).put("abis", JSONArray(profile.supportedAbis))
                            .put("activeIndexBeforeDelete", indexId).put("originalHashes", JSONArray(originalHashes))
                            .put("realQueries", JSONArray(listOf("una hamburguesa", "a photo of a hamburger")))
                            .put("recreationPersisted", true).put("deleted", true).toString(2))
                    } catch (failure: Throwable) { capture("failure-before-scenario-close"); throw failure }
                }
            } finally { database.close() }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun clean(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                }
            }
            sources.forEach { uri -> clean { resolver.delete(uri, null, null) } }
            clean { removeOwnedBalancedFixture("final-cleanup") }
            cleanupFailure?.let { failure ->
                val primary = primaryFailure
                if (primary != null) primary.addSuppressed(failure) else throw failure
            }
        }
    }
}
