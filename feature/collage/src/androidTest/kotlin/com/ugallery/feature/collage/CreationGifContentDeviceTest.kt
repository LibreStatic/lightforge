package com.ugallery.feature.collage

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Movie
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CreationGifContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun compactLightCompleteWorkflow() = workflow(false, 1f, 360)
    @Test fun compactDarkLargeTextCompleteWorkflow() = workflow(true, 1.6f, 360)
    @Test fun expandedDynamicCompleteWorkflow() = workflow(false, 1f, 840)
    @Suppress("DEPRECATION")
    private fun workflow(dark: Boolean, font: Float, width: Int) {
        val files = listOf(Color.RED, Color.GREEN, Color.BLUE).map { color ->
            File.createTempFile("creation-ui-", ".png", context.cacheDir).also { file ->
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
        val session = UUID.randomUUID().toString()
        var output: Uri? = null
        var expected: CreationGifPublicationReceipt? = null
        var succeeded = false
        var visible by mutableStateOf(true)
        var controller: CreationGifViewModel? = null
        val originals = files.map { it.readBytes().toList() }
        val journal = CreationGifPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "gif-publications"))
        val restore = StateRestorationTester(compose)
        try {
            restore.setContent {
                UGalleryTheme(darkTheme = dark, dynamicColor = true) {
                    val colors = MaterialTheme.colorScheme
                    listOf(colors.onBackground to colors.background, colors.onSurface to colors.surfaceContainer,
                        colors.onSecondaryContainer to colors.secondaryContainer, colors.onErrorContainer to colors.errorContainer).forEach { (fg, bg) ->
                        assertTrue((maxOf(fg.luminance(), bg.luminance()) + .05f) / (minOf(fg.luminance(), bg.luminance()) + .05f) >= 4.5f)
                    }
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                        Box(Modifier.widthIn(max = width.dp)) {
                            if (visible) {
                                val own: CreationGifViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "creation-gif-$session")
                                SideEffect { controller = own }
                                CreationGifContent(session, "GIF", files.map { CreationGifSource(Uri.fromFile(it)) }, {}, onExported = { output = it })
                            }
                        }
                    }
                }
            }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("creation-gif-preview").fetchSemanticsNodes().isNotEmpty() }
            click("creation-gif-seconds-1")
            click("creation-gif-play")
            compose.waitUntil(10000) { compose.onNodeWithTag("creation-gif-position").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text != "Frame 1 of 3" }
            click("creation-gif-play")
            // Reset to first frame using visible position, then reorder RED later and remove GREEN.
            while (compose.onNodeWithTag("creation-gif-position").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text != "Frame 1 of 3") click("creation-gif-previous")
            click("creation-gif-later") // GREEN, RED, BLUE; current RED.
            click("creation-gif-previous")
            click("creation-gif-remove") // RED, BLUE.
            compose.onNodeWithTag("creation-gif-position").assertTextEquals("Frame 1 of 2")
            restore.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("creation-gif-position").assertTextEquals("Frame 1 of 2")
            compose.onNodeWithTag("creation-gif-seconds-1").assertIsSelected()
            click("creation-gif-export")
            compose.waitUntil(45000) { output != null }
            expected = checkNotNull(journal.read(session))
            check(expected!!.destination?.uri == output.toString())
            compose.onNodeWithTag("creation-gif-saved").performScrollTo().assertExists()
            val bytes = context.contentResolver.openInputStream(output!!)!!.use { it.readBytes() }
            val movie = Movie.decodeByteArray(bytes, 0, bytes.size)!!
            assertEquals(2000, movie.duration())
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            movie.setTime(500); movie.draw(android.graphics.Canvas(bitmap), 0f, 0f); assertEquals(Color.RED, bitmap.getPixel(256, 256))
            movie.setTime(1500); movie.draw(android.graphics.Canvas(bitmap), 0f, 0f); assertEquals(Color.BLUE, bitmap.getPixel(256, 256)); bitmap.recycle()
            val evidence = File(context.filesDir, "creation-gif-ui").apply { mkdirs() }
            val screen = compose.onRoot().captureToImage().asAndroidBitmap()
            File(evidence, "$dark-$font-$width.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(originals, files.map { it.readBytes().toList() })
            succeeded = true
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { visible = false }
            val drained = drainGifFixtureControllers(listOfNotNull(controller))
            if (succeeded && drained) cleanupGifFixtureOrRetain("workflow session=$session output=$output sources=$files") {
                val receipt = checkNotNull(expected)
                check(journal.read(session) == receipt)
                deleteGifFixtureOutput(context, receipt)
                check(journal.retire(receipt)); check(journal.read(session) == null)
                assertEquals(originals, files.map { it.readBytes().toList() })
                files.forEach { check(it.delete()) }
            } else {
                android.util.Log.e("GIF_FIXTURE_RETAINED", "Workflow session=$session output=$output sources=$files")
                check(!succeeded || drained)
            }
        }
    }
    @Test fun changedSnapshotShowsErrorAndBackWorks() {
        val file = File.createTempFile("creation-identity-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val session = UUID.randomUUID().toString()
        var sources by mutableStateOf(List(2) { CreationGifSource(Uri.fromFile(file)) })
        var back = false
        try {
            compose.setContent { UGalleryTheme { CreationGifContent(session, "GIF", sources, { back = true }) } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("creation-gif-preview").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { sources = List(2) { CreationGifSource(Uri.fromFile(File(file.parentFile, "missing-source.png"))) } }
            compose.onNodeWithTag("creation-gif-error").performScrollTo().assertExists()
            compose.onNodeWithTag("creation-gif-export").assertIsNotEnabled()
            compose.onNodeWithContentDescription(context.getString(R.string.creation_gif_back)).performClick()
            compose.waitUntil(15000) { back }
            assertTrue(back)
        } finally { file.delete() }
    }
    @Test fun backDuringExportCancelsAndCleansStaging() {
        val file = File.createTempFile("creation-back-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(71)
        bitmap.setPixels(IntArray(256 * 256) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }, 0, 256, 0, 0, 256, 256)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val session = UUID.randomUUID().toString()
        val sources = List(60) { File.createTempFile("creation-back-$session-", ".png", context.cacheDir).apply { writeBytes(file.readBytes()) } }
        var back = false
        var output: Uri? = null
        var succeeded = false
        var visible by mutableStateOf(true)
        var controller: CreationGifViewModel? = null
        val sourceHashes = sources.map { it.inputStream().use { stream -> CreationGifExporter.sha256(stream) } }
        val journal = CreationGifPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "gif-publications"))
        try {
            compose.setContent { UGalleryTheme {
                if (visible) {
                    val own: CreationGifViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "creation-gif-$session")
                    SideEffect { controller = own }
                    CreationGifContent(session, "GIF", sources.map { CreationGifSource(Uri.fromFile(it)) }, { back = true }, onExported = { output = it })
                }
            } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("creation-gif-preview").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("creation-gif-export").performScrollTo().performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.creation_gif_back)).performClick()
            compose.onNodeWithText(context.getString(R.string.creation_gif_leave)).assertExists()
            compose.onAllNodesWithText(context.getString(R.string.creation_gif_cancel)).onLast().performClick()
            compose.waitUntil(15000) { back }
            assertNull(output)
            assertEquals(sourceHashes, sources.map { it.inputStream().use { stream -> CreationGifExporter.sha256(stream) } })
            succeeded = true
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { visible = false }
            val drained = drainGifFixtureControllers(listOfNotNull(controller))
            if (succeeded && drained && journal.read(session) == null) cleanupGifFixtureOrRetain("cancel session=$session files=${sources + file}") {
                assertEquals(sourceHashes, sources.map { it.inputStream().use { stream -> CreationGifExporter.sha256(stream) } })
                sources.forEach { check(it.delete()) }; check(file.delete())
            } else {
                android.util.Log.e("GIF_FIXTURE_RETAINED", "Cancellation fixture session=$session output=$output files=${sources + file}")
                check(!succeeded || drained)
            }
        }
    }
    @Test fun publishedReceiptWithoutOriginalsRevalidatesHandoffsAndRetainsMissingOutput() {
        val session = UUID.randomUUID().toString()
        val files = listOf(Color.RED, Color.BLUE).map { color ->
            File.createTempFile("gif-recovery-$session-", ".png", context.cacheDir).also { file ->
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
        }
        val originals = files.map { it.readBytes().toList() }
        val held = files.map { File(it.parentFile, it.name + ".source-held") }
        val sources = files.map { CreationGifSource(Uri.fromFile(it)) }
        val journal = CreationGifPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "gif-publications"))
        var expected: CreationGifPublicationReceipt? = null
        var output: Uri? = null
        var visible by mutableStateOf(true)
        var controller: CreationGifViewModel? = null
        var normalBack = 0
        var recoveryBack = 0
        var succeeded = false
        var deleted = false
        val opened = mutableListOf<Uri>()
        val shared = mutableListOf<Uri>()
        val notifications = mutableListOf<Uri>()
        try {
            output = runBlocking { CreationGifExporter(context).export(session, CreationGifRequest(sources, 4), listOf(1, 0)) }
            expected = checkNotNull(journal.read(session))
            check(expected!!.destination?.uri == output.toString())
            files.zip(held).forEach { (source, target) -> check(source.renameTo(target)) }
            compose.setContent {
                UGalleryTheme {
                    if (visible) {
                        val own: CreationGifViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "creation-gif-$session")
                        SideEffect { controller = own }
                        CreationGifContent(session, "GIF", sources, { normalBack++ }, sourcesAvailable = false,
                            onBackKeepingRecovery = { recoveryBack++ }, onExported = notifications::add,
                            onOpen = opened::add, onShare = shared::add)
                    }
                }
            }
            compose.waitUntil(45000) { notifications.size == 1 }
            assertEquals(listOf(output), notifications)
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("creation-gif-seconds-4").fetchSemanticsNodes().any {
                    it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Selected) == true
                }
            }
            compose.onNodeWithTag("creation-gif-seconds-4").performScrollTo().assertIsSelected().assertIsNotEnabled()
            compose.onNodeWithTag("creation-gif-export").performScrollTo().assertIsNotEnabled()
            compose.waitUntil(45000) { compose.onAllNodesWithTag("creation-gif-preview").fetchSemanticsNodes().isNotEmpty() }
            val firstFrame = compose.onNodeWithTag("creation-gif-preview").performScrollTo().captureToImage().asAndroidBitmap()
            assertEquals(Color.BLUE, firstFrame.getPixel(firstFrame.width / 2, firstFrame.height / 2))
            firstFrame.recycle()
            assertTrue(opened.isEmpty()); assertTrue(shared.isEmpty())
            click("creation-gif-open")
            compose.waitUntil(45000) { opened.size == 1 }
            click("creation-gif-share")
            compose.waitUntil(45000) { shared.size == 1 }
            assertEquals(listOf(output), opened); assertEquals(listOf(output), shared)
            val receipt = checkNotNull(expected)
            check(journal.read(session) == receipt)
            assertEquals(originals, held.map { it.readBytes().toList() })
            deleteGifFixtureOutput(context, receipt)
            deleted = true
            click("creation-gif-open") // Stale preview must not deliver an absent result.
            compose.waitUntil(45000) { compose.onAllNodesWithTag("creation-gif-open").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("creation-gif-share").assertDoesNotExist()
            compose.onNodeWithTag("creation-gif-export").performScrollTo().assertIsNotEnabled()
            assertEquals(1, opened.size); assertEquals(1, shared.size); assertEquals(1, notifications.size)
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(15000) { recoveryBack == 1 }
            assertEquals(0, normalBack)
            check(journal.read(session) == receipt)
            succeeded = true
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { visible = false }
            val drained = drainGifFixtureControllers(listOfNotNull(controller))
            if (succeeded && drained) cleanupGifFixtureOrRetain("session=$session output=$output sources=$files held=$held") {
                check(deleted)
                assertGifFixtureOutputAbsent(context, checkNotNull(output))
                val receipt = checkNotNull(expected)
                check(journal.read(session) == receipt)
                check(journal.retire(receipt)) // Test-only marker cleanup after exact owned output absence.
                check(journal.read(session) == null)
                check(files.none { it.exists() })
                assertEquals(originals, held.map { it.readBytes().toList() })
                held.forEach { check(it.delete()) }
            } else {
                android.util.Log.e("GIF_FIXTURE_RETAINED", "session=$session output=$output deleted=$deleted sources=$files held=$held")
                check(!succeeded || drained)
            }
        }
    }

    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle() }
}

private fun drainGifFixtureControllers(controllers: Collection<CreationGifViewModel>): Boolean = try {
    runBlocking {
        withContext(Dispatchers.Main) {
            controllers.forEach { it.cancelAndWait() }
        }
    }
    true
} catch (failure: Exception) {
    android.util.Log.e("GIF_FIXTURE_RETAINED", "Owned UI work drain failed", failure)
    false
}

private fun assertGifFixtureOutputAbsent(context: android.content.Context, uri: Uri) {
    checkNotNull(context.contentResolver.query(uri, arrayOf("_id"), null, null, null)).use {
        check(!it.moveToFirst()) { "Owned fixture output is still present: $uri" }
    }
}

private fun deleteGifFixtureOutput(context: android.content.Context, expected: CreationGifPublicationReceipt) {
    val destination = checkNotNull(expected.destination)
    check(expected.phase == CreationGifPublicationPhase.Published)
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
    check(checkNotNull(resolver.openInputStream(uri)).use { CreationGifExporter.sha256(it) } == expected.renderSha256)
    check(metadata() == expectedFields)
    check(resolver.delete(uri, columns.joinToString(" AND ") { "$it=?" }, expectedFields.toTypedArray()) == 1)
    assertGifFixtureOutputAbsent(context, uri)
}

private inline fun cleanupGifFixtureOrRetain(identity: String, block: () -> Unit) {
    try { block() }
    catch (failure: Throwable) {
        android.util.Log.e("GIF_FIXTURE_RETAINED", "Cleanup failed; remaining fixture retained: $identity", failure)
        throw failure
    }
}
