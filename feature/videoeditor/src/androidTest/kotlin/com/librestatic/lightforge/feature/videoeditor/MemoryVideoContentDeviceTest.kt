package com.librestatic.lightforge.feature.videoeditor

import android.content.ContentUris
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import java.security.MessageDigest
import android.graphics.Bitmap
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
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.editing.video.MemoryVideoSource
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@UnstableApi
class MemoryVideoContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun compactLightDraftAndRestore() = draft(false, 1f, 360)

    @Test fun compactDarkLargeTextDraftAndRestore() = draft(true, 1.6f, 360)

    @Test fun expandedDynamicDraftAndRestore() = draft(false, 1f, 840)

    @Test
    fun fourSecondSelectionExportsFourSecondVideoWithoutChangingOriginal() {
        val file = image(Color.RED)
        fun hash() = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        // This isolated fixture starts exactly one export. Never delete prior publications.
        fun ownedPublications(): Set<Uri> = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(context.packageName, "Movies/Lightforge/Memories/", "Lightforge-Memory-%.mp4"),
            null,
        )!!.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
            }
        }
        val before = ownedPublications()
        val originalHash = hash()
        var published: Uri? = null
        var showing by mutableStateOf(true)
        try {
            compose.setContent {
                LightforgeTheme {
                    if (showing) MemoryVideoContent("Four-second video",
                        listOf(MemoryVideoSource(Uri.fromFile(file))), {})
                }
            }
            click("memory-video-seconds-4")
            compose.onNodeWithTag("memory-video-seconds-4").assertIsSelected()
            click("memory-video-export")
            compose.waitUntil(90_000) {
                compose.onAllNodesWithTag("memory-video-saved").fetchSemanticsNodes().isNotEmpty()
            }
            val added = ownedPublications() - before
            assertEquals("One verified publication expected", 1, added.size)
            val uri = added.single()
            published = uri
            context.contentResolver.query(uri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE),
                null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals("video/mp4", it.getString(1))
            }
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(context, uri)
                val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Expected four-second video, got $duration ms", duration in 3_950L..4_250L)
                for (timeUs in listOf(400_000L, 3_600_000L)) {
                    val frame = metadata.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
                    try {
                        val color = frame.getPixel(frame.width / 2, frame.height / 2)
                        assertTrue(Color.red(color) > 180 && Color.green(color) < 70 && Color.blue(color) < 70)
                    } finally { frame.recycle() }
                }
            } finally { metadata.release() }
            assertArrayEquals(originalHash, hash())
        } finally {
            compose.runOnIdle { showing = false }
            published?.let { context.contentResolver.delete(it, null, null) }
            file.delete()
        }
    }

    private fun draft(dark: Boolean, fontScale: Float, width: Int) {
        val restoration = StateRestorationTester(compose)
        val files = listOf(image(Color.RED), image(Color.GREEN), image(Color.BLUE))
        val sources = files.map { MemoryVideoSource(Uri.fromFile(it)) }
        try {
            restoration.setContent {
                LightforgeTheme(darkTheme = dark, dynamicColor = true) {
                    val color = MaterialTheme.colorScheme
                    listOf(
                            color.onBackground to color.background,
                            color.onSurface to color.surfaceContainer,
                            color.onSecondaryContainer to color.secondaryContainer,
                            color.onErrorContainer to color.errorContainer,
                        )
                        .forEach { (fg, bg) ->
                            val ratio =
                                (maxOf(fg.luminance(), bg.luminance()) + 0.05f) /
                                    (minOf(fg.luminance(), bg.luminance()) + 0.05f)
                            assertTrue("contrast $ratio", ratio >= 4.5f)
                        }
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale)
                    ) {
                        Box(Modifier.widthIn(max = width.dp)) {
                            MemoryVideoContent("Local memory", sources, {})
                        }
                    }
                }
            }
            compose.onNodeWithTag("memory-video-screen").assertExists()
            click("memory-video-next")
            compose
                .onNodeWithTag("memory-video-position")
                .assertTextEquals(context.getString(R.string.memory_video_position, 2, 3))
            click("memory-video-earlier")
            compose
                .onNodeWithTag("memory-video-position")
                .assertTextEquals(context.getString(R.string.memory_video_position, 1, 3))
            click("memory-video-seconds-2")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("memory-video-seconds-2").performScrollTo().assertIsSelected()
            compose
                .onNodeWithTag("memory-video-position")
                .performScrollTo()
                .assertTextEquals(context.getString(R.string.memory_video_position, 1, 3))
            click("memory-video-later")
            compose
                .onNodeWithTag("memory-video-position")
                .assertTextEquals(context.getString(R.string.memory_video_position, 2, 3))
            compose.onNodeWithTag("memory-video-export").performScrollTo().assertIsEnabled()
            screenshot("memory-video-$dark-$fontScale-$width-settings.png")
            compose.onNodeWithTag("memory-video-position").performScrollTo()
            screenshot("memory-video-$dark-$fontScale-$width-preview.png")
            assertTrue(files.all { it.isFile })
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test
    fun emptyAndOversizedMemoriesCannotExport() {
        var sources by mutableStateOf<List<MemoryVideoSource>>(emptyList())
        compose.setContent { LightforgeTheme { MemoryVideoContent("Empty memory", sources, {}) } }
        compose.onNodeWithTag("memory-video-export").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            sources = List(121) { MemoryVideoSource(Uri.parse("content://missing/photo/$it")) }
        }
        compose.onNodeWithTag("memory-video-export").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun leavingDuringExportCancelsAndCleansOwnedStaging() {
        val file = image(Color.RED)
        var showing by mutableStateOf(true)
        try {
            compose.setContent {
                LightforgeTheme {
                    if (showing)
                        MemoryVideoContent(
                            "Cancel video",
                            List(20) { MemoryVideoSource(Uri.fromFile(file)) },
                            {},
                        )
                }
            }
            click("memory-video-export")
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithTag("memory-video-progress")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.runOnIdle { showing = false }
            compose.waitUntil(10_000) {
                context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("memory-video-") }
            }
            assertTrue(file.isFile)
        } finally {
            file.delete()
        }
    }

    private fun screenshot(name: String) {
        val dir = File(context.filesDir, "memory-video-evidence").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun restoredDraftDoesNotReuseIndicesFromADifferentSnapshot() {
        val files = listOf(image(Color.RED), image(Color.GREEN), image(Color.BLUE))
        var sources = files.map { MemoryVideoSource(Uri.fromFile(it)) }
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { LightforgeTheme { MemoryVideoContent("Snapshot", sources, {}) } }
            click("memory-video-later")
            sources = sources.takeLast(1)
            restoration.emulateSavedInstanceStateRestore()
            compose
                .onNodeWithTag("memory-video-position")
                .performScrollTo()
                .assertTextEquals(context.getString(R.string.memory_video_position, 1, 1))
            compose.onNodeWithTag("memory-video-export").performScrollTo().assertIsEnabled()
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test
    fun musicPickerRequestsLocalAudioOnly() {
        val intent = MemoryVideoLocalAudioContract().createIntent(context, arrayOf("audio/*"))
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.getBooleanExtra(android.content.Intent.EXTRA_LOCAL_ONLY, false))
        assertArrayEquals(
            arrayOf("audio/*"),
            intent.getStringArrayExtra(android.content.Intent.EXTRA_MIME_TYPES),
        )
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun image(color: Int): File {
        val file = File(context.cacheDir, "memory-ui-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        return file
    }
}
