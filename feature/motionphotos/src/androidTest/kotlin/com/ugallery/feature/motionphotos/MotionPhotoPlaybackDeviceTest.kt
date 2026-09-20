package com.ugallery.feature.motionphotos

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real decoder/player and a short owned Motion fixture. No publication, keyframe write or renderer mock. */
class MotionPhotoPlaybackDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun naturalEndShowsLastFrameReplayRestartsAndLaterManualSelectionWins() {
        val source = MotionPhotoFixtures.create(context)
        val originalHash = MotionPhotoSession.hash(source)
        val durationUs = MotionPhotoFixtures.videoDurationUs(context)
        assertTrue("This bounded fixture must last about three seconds", durationUs in 2_500_000L..4_000_000L)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "Motion playback fixture path=${source.absolutePath} sha256=$originalHash durationUs=$durationUs\n")
        })
        val baseline = outputs()
        var visible by mutableStateOf(true)
        var passed = false
        try {
            compose.setContent {
                UGalleryTheme {
                    if (visible) MotionPhotoContent(
                        input = MotionPhotoFixtures.input(source), keyFrameTimeUs = null,
                        onSetKeyFrame = { _, _ -> error("Playback test must not save a keyframe") },
                        onBack = {},
                        onOpen = { _, _, _ -> error("Playback test must not dispatch outputs") },
                        onShare = { _, _, _ -> error("Playback test must not dispatch outputs") },
                    )
                }
            }
            awaitTag("motion-frame-5")
            click("motion-frame-0")
            click("motion-play"); awaitPlaying(true)
            compose.waitUntil(8_000) { !isPlaying() }
            assertEndFrame(durationUs)

            click("motion-play"); awaitPlaying(true)
            compose.waitUntil(2_000) { positionMillis() in 0..(durationUs / 3000) }
            val replayStart = positionMillis()
            compose.waitUntil(2_000) { isPlaying() && positionMillis() >= replayStart + 500 }
            assertTrue("Replay must advance from the beginning, not replay a trailing fragment", positionMillis() < durationUs / 1000)
            // Pause uses the actual Player, not a synthetic LifecycleOwner.
            click("motion-play"); awaitPlaying(false)
            val paused = positionMillis()
            Thread.sleep(250)
            assertEquals(paused, positionMillis())
            click("motion-play"); awaitPlaying(true)
            compose.waitUntil(8_000) { !isPlaying() }
            assertEndFrame(durationUs)

            // Selecting after EOS must override the end callback and restart threshold.
            val manualUs = (durationUs - 1) * 2 / 5
            click("motion-frame-2")
            compose.onNodeWithTag("motion-frame-2").assertIsSelected()
            compose.onNodeWithTag("motion-time").assertTextEquals(context.getString(R.string.motion_time, manualUs / 1000, durationUs / 1000))
            Thread.sleep(250)
            compose.onNodeWithTag("motion-frame-2").assertIsSelected()
            click("motion-play"); awaitPlaying(true)
            // Starting from zero would take at least 1.3 seconds for this fixture; do not
            // accept a false restart merely because it eventually reaches the selected frame.
            compose.waitUntil(1_000) { isPlaying() && positionMillis() >= manualUs / 1000 + 300 }
            assertTrue("Manual selection after EOS must not restart at zero", positionMillis() >= manualUs / 1000)
            click("motion-play"); awaitPlaying(false)
            assertEquals(originalHash, MotionPhotoSession.hash(source)); assertEquals(baseline, outputs())
            passed = true
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            if (passed) {
                assertEquals(originalHash, MotionPhotoSession.hash(source)); assertEquals(baseline, outputs())
                assertTrue(source.delete() && !source.exists())
            }
        }
    }

    private fun assertEndFrame(durationUs: Long) {
        val expected = context.getString(R.string.motion_time, (durationUs - 1) / 1000, durationUs / 1000)
        var observed = text("motion-time")
        try {
            compose.waitUntil(2_000) {
                observed = text("motion-time")
                observed == expected
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Natural end expected '$expected'; last motion-time='$observed'", failure)
        }
        compose.onNodeWithTag("motion-frame-5").assertIsSelected()
        compose.onNodeWithTag("motion-play").assertTextContains(context.getString(R.string.motion_play))
    }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) {
        awaitTag(tag); compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
    }
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ") { it.text }
    private fun isPlaying() = text("motion-play").contains(context.getString(R.string.motion_pause))
    private fun awaitPlaying(expected: Boolean) = compose.waitUntil(5_000) { isPlaying() == expected }
    private fun positionMillis(): Long = Regex("[0-9]+").find(text("motion-time"))!!.value.toLong()

    private fun outputs(): List<List<String?>> = listOf("images" to "Pictures/UGallery/Motion/", "video" to "Movies/UGallery/Motion/").flatMap { (collection, path) ->
        val columns = arrayOf("_id", "owner_package_name", "_display_name", "relative_path", "mime_type", "generation_added", "generation_modified", "_size", "is_pending", "is_trashed")
        context.contentResolver.query(Uri.parse("content://media/external/$collection/media?includePending=1"), columns,
            "owner_package_name=? AND relative_path=?", arrayOf(context.packageName, path), "_id ASC")!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(listOf(collection) + columns.indices.map { cursor.getString(it) }) }
        }
    }
}
