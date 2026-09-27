package com.librestatic.lightforge.feature.collage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CreationCollageContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun compactLightPreviewReorderExportCallbacksAndNewDraft() = workflow(false, 1f, 360)
    @Test fun compactDarkTwoHundredPercentKeepsEveryCriticalActionReachable() = workflow(true, 2f, 360)
    @Test fun expandedDynamicPreviewAndExportUseMatchingSemanticRoles() = workflow(false, 1f, 840)

    private fun workflow(dark: Boolean, font: Float, width: Int) {
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        androidx.test.espresso.IdlingPolicies.setIdlingResourceTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        val files = listOf(Color.RED, Color.GREEN, Color.BLUE).map { color ->
            File.createTempFile("creation-collage-ui-", ".png", context.cacheDir).also { file ->
                val bitmap = Bitmap.createBitmap(90, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
        val originals = files.map { it.readBytes().toList() }
        val sources = files.map { CreationCollageSource(Uri.fromFile(it)) }
        val session = mutableStateOf(UUID.randomUUID().toString())
        val firstSession = session.value
        var succeeded = false
        var expectedReceipt: CreationCollagePublicationReceipt? = null
        var visible by mutableStateOf(true)
        val ownedControllers = mutableMapOf<String, CreationCollageState>()
        val outputs = mutableListOf<Uri>(); val opened = mutableListOf<Uri>(); val shared = mutableListOf<Uri>()
        val restore = StateRestorationTester(compose)
        try {
            restore.setContent {
                if (visible) LightforgeTheme(darkTheme = dark, dynamicColor = width == 840) {
                    val colors = MaterialTheme.colorScheme
                    listOf(colors.onBackground to colors.background, colors.onSurface to colors.surfaceContainer,
                        colors.onSecondaryContainer to colors.secondaryContainer, colors.onErrorContainer to colors.errorContainer).forEach { (fg, bg) ->
                        assertTrue((maxOf(fg.luminance(), bg.luminance()) + .05f) / (minOf(fg.luminance(), bg.luminance()) + .05f) >= 4.5f)
                    }
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                        Box(Modifier.widthIn(max = width.dp)) {
                            val own: CreationCollageState = androidx.lifecycle.viewmodel.compose.viewModel(key = "creation-collage-${session.value}")
                            SideEffect { ownedControllers[session.value] = own }
                            CreationCollageContent(session.value, sources, {}, opened::add, shared::add, outputs::add)
                        }
                    }
                }
            }
            awaitPreview()
            click("creation-collage-template-Strip3"); awaitPreview()
            click("creation-collage-later"); awaitPreview()
            compose.onNodeWithTag("creation-collage-slot-0").assertTextEquals(context.getString(R.string.creation_collage_photo, 2))
            compose.onNodeWithTag("creation-collage-slot-1").assertTextEquals(context.getString(R.string.creation_collage_photo, 1))
            restore.emulateSavedInstanceStateRestore(); awaitPreview()
            compose.onNodeWithTag("creation-collage-template-Strip3").assertIsSelected()
            compose.onNodeWithTag("creation-collage-slot-0").assertTextEquals(context.getString(R.string.creation_collage_photo, 2))
            compose.onNodeWithTag("creation-collage-preview").performScrollTo()
            val previewBounds = compose.onNodeWithTag("creation-collage-preview").fetchSemanticsNode().boundsInRoot
            assertEquals("Preview preserves the square export geometry", previewBounds.width, previewBounds.height, 1f)
            val preview = compose.onNodeWithTag("creation-collage-preview").captureToImage().asAndroidBitmap()
            listOf(Color.GREEN, Color.RED, Color.BLUE).forEachIndexed { index, color ->
                assertEquals(color, preview.getPixel((preview.width * (index + .5f) / 3f).toInt(), preview.height / 2))
            }
            click("creation-collage-export")
            compose.waitUntil(45000) { outputs.size == 1 }
            expectedReceipt = checkNotNull(CreationCollagePublicationJournal(
                File(context.noBackupFilesDir.canonicalFile, "collage-publications")).read(firstSession))
            check(expectedReceipt!!.destination?.uri == outputs.single().toString())
            click("creation-collage-open")
            compose.waitUntil(45000) { opened.size == outputs.size }
            click("creation-collage-share")
            compose.waitUntil(45000) { shared.size == outputs.size }
            assertEquals(outputs, opened); assertEquals(outputs, shared)
            val result = context.contentResolver.openInputStream(outputs.single())!!.use { BitmapFactory.decodeStream(it) }
            listOf(Color.GREEN, Color.RED, Color.BLUE).forEachIndexed { index, color ->
                assertEquals(color, result.getPixel((result.width * (index + .5f) / 3f).toInt(), result.height / 2))
            }
            result.recycle()
            restore.emulateSavedInstanceStateRestore()
            compose.waitUntil(45000) { compose.onAllNodesWithTag("creation-collage-saved").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("creation-collage-saved").performScrollTo().assertExists()
            compose.runOnIdle { assertEquals(1, outputs.size) }
            compose.runOnIdle { session.value = UUID.randomUUID().toString() }
            awaitPreview()
            compose.onNodeWithTag("creation-collage-saved").assertDoesNotExist()
            compose.onNodeWithTag("creation-collage-template-Grid3").assertIsSelected()
            compose.onNodeWithTag("creation-collage-slot-0").assertTextEquals(context.getString(R.string.creation_collage_photo, 1))
            assertEquals(originals, files.map { it.readBytes().toList() })
            succeeded = true
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                visible = false
                ownedControllers.values.forEach { it.detach() }
            }
            val drained = drainCollageFixtureControllers(ownedControllers.values)
            if (succeeded && drained) cleanupCollageFixtureOrRetain("workflow session=$firstSession outputs=$outputs sources=$files") {
                val receipt = checkNotNull(expectedReceipt)
                val journal = CreationCollagePublicationJournal(File(context.noBackupFilesDir.canonicalFile, "collage-publications"))
                check(journal.read(firstSession) == receipt)
                deleteCollageFixtureOutput(context, receipt)
                check(journal.retire(receipt))
                check(journal.read(firstSession) == null)
                check(files.map { it.readBytes().toList() } == originals)
                files.forEach { check(it.delete()) }
            } else {
                android.util.Log.e("COLLAGE_FIXTURE_RETAINED", "workflow session=$firstSession outputs=$outputs sources=$files drained=$drained")
                check(!succeeded || drained) { "Owned collage work did not drain; fixture retained" }
            }
        }
    }

    @Test fun receiptRestoresWithoutOriginalsAndMissingOutputBlocksHandoffAndRetainsRecovery() {
        val session = UUID.randomUUID().toString()
        val files = listOf(Color.RED, Color.BLUE).map { color ->
            File.createTempFile("collage-receipt-ui-", ".png", context.cacheDir).also { file ->
                Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(color)
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
        val originals = files.map { it.readBytes().toList() }
        val hidden = files.map { File(it.parentFile, it.name + ".source-held") }
        val sources = files.map { CreationCollageSource(Uri.fromFile(it)) }
        val exporter = CreationCollageExporter(context)
        val journal = CreationCollagePublicationJournal(File(context.noBackupFilesDir.canonicalFile, "collage-publications"))
        var output: Uri? = null
        var expectedReceipt: CreationCollagePublicationReceipt? = null
        var succeeded = false
        var outputWasDeleted = false
        var ownedController: CreationCollageState? = null
        var normalBack = 0
        var keepingBack = 0
        val opened = mutableListOf<Uri>()
        val shared = mutableListOf<Uri>()
        val notifications = mutableListOf<Uri>()
        var visible by mutableStateOf(true)
        try {
            output = runBlocking {
                val prepared = exporter.prepare(sources)
                try {
                    val render = exporter.render(prepared, CreationCollageLayout.initial(2).move(0, 1))
                    exporter.publish(session, render)
                } finally {
                    prepared.close()
                }
            }
            expectedReceipt = checkNotNull(journal.read(session))
            check(expectedReceipt!!.destination?.uri == output.toString())
            files.zip(hidden).forEach { (source, held) -> check(source.renameTo(held)) }
            compose.setContent {
                LightforgeTheme {
                    if (visible) {
                        val own: CreationCollageState = androidx.lifecycle.viewmodel.compose.viewModel(key = "creation-collage-$session")
                        SideEffect { ownedController = own }
                        CreationCollageContent(session, sources,
                            onBack = { normalBack++ }, onOpen = opened::add, onShare = shared::add,
                            onExported = notifications::add, sourcesAvailable = false,
                            onBackKeepingRecovery = { keepingBack++ })
                    }
                }
            }
            compose.waitUntil(45000) { notifications.size == 1 }
            assertEquals(listOf(output), notifications)
            compose.onNodeWithTag("creation-collage-slot-0").performScrollTo()
                .assertTextEquals(context.getString(R.string.creation_collage_photo, 2)).assertIsNotEnabled()
            compose.onNodeWithTag("creation-collage-export").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("creation-collage-zoom").performScrollTo().assertIsNotEnabled()
            click("creation-collage-open")
            compose.waitUntil(45000) { opened.size == 1 }
            click("creation-collage-share")
            compose.waitUntil(45000) { shared.size == 1 }
            assertEquals(listOf(output), opened); assertEquals(listOf(output), shared)
            assertEquals(originals, hidden.map { it.readBytes().toList() })
            val receipt = checkNotNull(expectedReceipt)
            check(journal.read(session) == receipt)
            deleteCollageFixtureOutput(context, receipt) // Planned absence, exact metadata/hash CAS and independent requery.
            outputWasDeleted = true
            click("creation-collage-open") // Existing preview is deliberately stale; callback must be revalidated.
            compose.waitUntil(45000) { compose.onAllNodesWithTag("creation-collage-publication-uncertain").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("creation-collage-open").assertDoesNotExist()
            compose.onNodeWithTag("creation-collage-share").assertDoesNotExist()
            compose.onNodeWithTag("creation-collage-export").performScrollTo().assertIsNotEnabled()
            assertEquals(1, opened.size); assertEquals(1, shared.size); assertEquals(1, notifications.size)
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(10000) { keepingBack == 1 }
            assertEquals(0, normalBack)
            assertEquals(receipt, journal.read(session))
            succeeded = true
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                visible = false
                ownedController?.detach()
            }
            val drained = drainCollageFixtureControllers(listOfNotNull(ownedController))
            if (succeeded && drained) cleanupCollageFixtureOrRetain("receipt session=$session output=$output plannedDeleted=$outputWasDeleted sources=$files held=$hidden") {
                check(outputWasDeleted)
                val receipt = checkNotNull(expectedReceipt)
                assertCollageFixtureOutputAbsent(context, Uri.parse(checkNotNull(receipt.destination).uri))
                check(journal.read(session) == receipt)
                check(journal.retire(receipt)) // Test-only exact marker retirement after the planned absence was proved.
                check(journal.read(session) == null)
                check(files.none { it.exists() })
                check(hidden.map { it.readBytes().toList() } == originals)
                hidden.forEach { check(it.delete()) }
            } else {
                android.util.Log.e("COLLAGE_FIXTURE_RETAINED", "receipt session=$session output=$output plannedDeleted=$outputWasDeleted sources=$files held=$hidden drained=$drained")
                check(!succeeded || drained) { "Owned collage work did not drain; fixture retained" }
            }
        }
    }

    private fun awaitPreview() {
        compose.waitUntil(45000) { compose.onAllNodesWithTag("creation-collage-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun click(tag: String) {
        compose.waitUntil(45000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
    }
}

/** Real Activity recreation while the provider deliberately holds publication at source validation. */
class CreationCollageLifecycleDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun configurationDuringPublicationKeepsOneResultAndRouteExitDropsBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "creation-collage-provider-${UUID.randomUUID()}").apply { mkdirs() }
        val uris = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
            val file = File(directory, "$index.png")
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(color)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            CreationCollageTestProvider.register(context, file)
        }
        CreationCollageLifecycleActivity.sources = uris.map(::CreationCollageSource)
        CreationCollageLifecycleActivity.session = UUID.randomUUID().toString()
        CreationCollageLifecycleActivity.results.clear()
        var gate: CreationCollageTestProvider.Companion.ReadGate? = null
        var succeeded = false
        var expectedReceipt: CreationCollagePublicationReceipt? = null
        var ownedController: CreationCollageState? = null
        try {
            ActivityScenario.launch(CreationCollageLifecycleActivity::class.java).use { scenario ->
                compose.waitUntil(45000) { compose.onAllNodesWithTag("creation-collage-preview").fetchSemanticsNodes().isNotEmpty() }
                lateinit var retained: CreationCollageState
                scenario.onActivity { retained = it.controller(); ownedController = retained }
                gate = CreationCollageTestProvider.pauseNextRead(uris.first())
                compose.onNodeWithTag("creation-collage-export").performScrollTo().performClick()
                assertTrue("Publication reached held source read", gate!!.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(retained.state.value.publishing)
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertSame("Configuration retains controller", retained, activity.controller())
                    assertTrue("Publication continues across configuration", retained.state.value.publishing)
                    assertNotNull(retained.state.value.preview)
                }
                gate!!.release.countDown()
                compose.waitUntil(45000) { CreationCollageLifecycleActivity.results.size == 1 }
                expectedReceipt = checkNotNull(CreationCollagePublicationJournal(
                    File(context.noBackupFilesDir.canonicalFile, "collage-publications")).read(CreationCollageLifecycleActivity.session))
                check(expectedReceipt!!.destination?.uri == CreationCollageLifecycleActivity.results.single().toString())
                compose.onNodeWithTag("creation-collage-saved").performScrollTo().assertExists()
                scenario.recreate()
                compose.waitUntil(45000) { compose.onAllNodesWithTag("creation-collage-saved").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("creation-collage-saved").performScrollTo().assertExists()
                assertEquals(1, CreationCollageLifecycleActivity.results.size)
                scenario.onActivity { it.showCollage.value = false }
                compose.waitForIdle()
                assertNull("Real route exit releases bitmap", retained.state.value.preview)
                assertFalse(retained.state.value.busy)
                assertEquals(CreationCollageLifecycleActivity.results.single().toString(), retained.state.value.result)
                scenario.onActivity { it.showCollage.value = true }
                compose.waitUntil(45000) { compose.onAllNodesWithTag("creation-collage-preview").fetchSemanticsNodes().isNotEmpty() }
                assertEquals("Saved result rebind does not notify twice", 1, CreationCollageLifecycleActivity.results.size)
            }
            succeeded = true
        } finally {
            gate?.release?.countDown()
            val drained = drainCollageFixtureControllers(listOfNotNull(ownedController))
            if (succeeded && drained) cleanupCollageFixtureOrRetain("lifecycle session=${CreationCollageLifecycleActivity.session} outputs=${CreationCollageLifecycleActivity.results} sources=$directory") {
                val receipt = checkNotNull(expectedReceipt)
                val journal = CreationCollagePublicationJournal(File(context.noBackupFilesDir.canonicalFile, "collage-publications"))
                check(journal.read(CreationCollageLifecycleActivity.session) == receipt)
                deleteCollageFixtureOutput(context, receipt)
                check(journal.retire(receipt))
                check(journal.read(CreationCollageLifecycleActivity.session) == null)
                CreationCollageLifecycleActivity.results.clear()
                uris.forEach(CreationCollageTestProvider::remove)
                CreationCollageLifecycleActivity.sources = emptyList()
                listOf(File(directory, "0.png"), File(directory, "1.png")).forEach { check(it.delete()) }
                check(directory.delete())
            } else {
                android.util.Log.e("COLLAGE_FIXTURE_RETAINED", "lifecycle session=${CreationCollageLifecycleActivity.session} outputs=${CreationCollageLifecycleActivity.results} sources=$directory drained=$drained")
                check(!succeeded || drained) { "Owned collage work did not drain; fixture retained" }
            }
        }
    }
}

class CreationCollageLifecycleActivity : ComponentActivity() {
    val showCollage = mutableStateOf(true)
    fun controller() = ViewModelProvider(this)["creation-collage-$session", CreationCollageState::class.java]
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LightforgeTheme { if (showCollage.value) CreationCollageContent(session, sources, {}, {}, {}, results::add) } }
    }
    companion object {
        @Volatile var sources: List<CreationCollageSource> = emptyList()
        @Volatile var session = ""
        val results = java.util.concurrent.CopyOnWriteArrayList<Uri>()
    }
}

private fun drainCollageFixtureControllers(controllers: Collection<CreationCollageState>): Boolean = try {
    runBlocking {
        withContext(Dispatchers.Main) {
            controllers.forEach { it.detach(); it.cancelAndWait() }
        }
    }
    true
} catch (failure: Exception) {
    android.util.Log.e("COLLAGE_FIXTURE_RETAINED", "Owned UI work drain failed", failure)
    false
}

private fun assertCollageFixtureOutputAbsent(context: android.content.Context, uri: Uri) {
    checkNotNull(context.contentResolver.query(uri, arrayOf("_id"), null, null, null)).use {
        check(!it.moveToFirst()) { "Owned fixture output is still present: $uri" }
    }
}

private fun deleteCollageFixtureOutput(context: android.content.Context, expected: CreationCollagePublicationReceipt) {
    val destination = checkNotNull(expected.destination)
    check(expected.phase == CreationCollagePublicationPhase.Published)
    check(destination.ownerPackage == context.packageName && !destination.pending && !destination.trashed)
    val uri = Uri.parse(destination.uri)
    val resolver = context.contentResolver
    val columns = arrayOf("owner_package_name", "_display_name", "relative_path", "mime_type",
        "generation_added", "generation_modified", "_size", "is_pending", "is_trashed")
    val expectedFields = listOf(destination.ownerPackage, destination.displayName, destination.relativePath,
        destination.mimeType, destination.generationAdded.toString(), destination.generationModified.toString(),
        destination.sizeBytes.toString(), "0", "0")
    fun metadata(): List<String> = checkNotNull(resolver.query(uri, columns, null, null, null)).use { cursor ->
        check(cursor.moveToFirst())
        List(columns.size) { cursor.getString(it).orEmpty() }.also { check(!cursor.moveToNext()) }
    }
    check(metadata() == expectedFields)
    check(checkNotNull(resolver.openInputStream(uri)).use { CreationCollageExporter.digest(it) } == expected.renderSha256)
    check(metadata() == expectedFields)
    check(resolver.delete(uri, columns.joinToString(" AND ") { "$it=?" }, expectedFields.toTypedArray()) == 1)
    assertCollageFixtureOutputAbsent(context, uri)
}

private inline fun cleanupCollageFixtureOrRetain(identity: String, block: () -> Unit) {
    try { block() }
    catch (failure: Throwable) {
        android.util.Log.e("COLLAGE_FIXTURE_RETAINED", "Cleanup failed; remaining fixture retained: $identity", failure)
        throw failure
    }
}
