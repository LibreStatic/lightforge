package com.ugallery.feature.motionphotos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
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
import com.ugallery.core.designsystem.UGalleryTheme
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MotionPhotoContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun compactLightCompleteWorkflow() = workflow(false, 1f, 360)

    @Test fun compactDarkLargeTextCompleteWorkflow() = workflow(true, 1.6f, 360)

    @Test fun expandedDynamicCompleteWorkflow() = workflow(false, 1f, 840)

    private fun workflow(dark: Boolean, scale: Float, width: Int) {
        val source = MotionPhotoFixtures.create(context)
        val hash = MotionPhotoSession.hash(source)
        val fixtureDuration = MotionPhotoFixtures.videoDurationUs(context)
        val expectedKeyTime = (fixtureDuration - 1) * 4 / 5
        val ownedOutputs = mutableListOf<MotionFixtureOutput>()
        val handoffs = mutableListOf<Triple<String, Uri, MotionPhotoPublicationKind>>()
        var closed = false
        var succeeded = false
        var visible by mutableStateOf(true)
        var keyFrame by mutableStateOf<Long?>(null)
        var durableJpeg: ByteArray? = null
        val restore = StateRestorationTester(compose)
        try {
            restore.setContent {
                UGalleryTheme(darkTheme = dark, dynamicColor = true) {
                    val c = MaterialTheme.colorScheme
                    listOf(
                            c.onBackground to c.background,
                            c.onSurface to c.surfaceContainer,
                            c.onPrimaryContainer to c.primaryContainer,
                            c.onSecondaryContainer to c.secondaryContainer,
                            c.onErrorContainer to c.errorContainer,
                        )
                        .forEach { (fg, bg) ->
                            assertTrue(
                                (maxOf(fg.luminance(), bg.luminance()) + 0.05f) /
                                    (minOf(fg.luminance(), bg.luminance()) + 0.05f) >= 4.5f
                            )
                        }
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, scale)
                    ) {
                        Box(Modifier.widthIn(max = width.dp)) {
                            if (visible) MotionPhotoContent(
                                MotionPhotoFixtures.input(source),
                                keyFrame,
                                onSetKeyFrame = { time, file ->
                                    assertTrue(file.isFile)
                                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)!!
                                    bitmap.recycle()
                                    durableJpeg = file.readBytes()
                                    keyFrame = time
                                    true
                                },
                                onBack = { closed = true },
                                onOpen = { id, uri, kind -> handoffs += Triple(id, uri, kind) },
                                onResetKeyFrame = {
                                    keyFrame = null
                                    durableJpeg = null
                                    true
                                },
                            )
                        }
                    }
                }
            }
            awaitTag("motion-frame-5")
            click("motion-frame-0")
            click("motion-play")
            compose.waitUntil(10_000) {
                compose
                    .onAllNodes(hasText(context.getString(R.string.motion_pause)))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.waitUntil(10_000) {
                val text =
                    compose
                        .onNodeWithTag("motion-time")
                        .fetchSemanticsNode()
                        .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                        .first()
                        .text
                text != context.getString(R.string.motion_time, 0L, fixtureDuration / 1000)
            }
            click("motion-play")
            click("motion-frame-4")
            click("motion-set-key-frame")
            awaitTag("motion-key-saved")
            assertEquals(expectedKeyTime, keyFrame)
            assertTrue(durableJpeg!!.isNotEmpty())
            restore.emulateSavedInstanceStateRestore()
            awaitTag("motion-frame-5")
            compose
                .onNodeWithTag("motion-key-frame")
                .performScrollTo()
                .assertTextEquals(
                    context.getString(R.string.motion_selected_key, expectedKeyTime / 1000)
                )
            click("motion-reset-key-frame")
            compose.waitUntil(10_000) { keyFrame == null }
            click("motion-export-jpeg")
            awaitTag("motion-exported")
            click("motion-open")
            compose.waitUntil(30_000) { handoffs.size == 1 }
            ownedOutputs += captureMotionFixture(context, source, hash, handoffs.single(), expectedKeyTime, fixtureDuration)
            click("motion-create-another")
            awaitTag("motion-frame-5")
            check(MotionPhotoPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "motion-publications"))
                .read(ownedOutputs.first().result.publicationId) == null)
            click("motion-export-mp4")
            awaitTag("motion-exported")
            click("motion-open")
            compose.waitUntil(30_000) { handoffs.size == 2 }
            ownedOutputs += captureMotionFixture(context, source, hash, handoffs.last(), null, fixtureDuration)
            assertNotEquals(ownedOutputs[0].result.publicationId, ownedOutputs[1].result.publicationId)
            assertEquals(hash, MotionPhotoSession.hash(source))
            compose.onNodeWithTag("motion-exported").performScrollTo()
            screenshot("motion-$dark-$scale-$width-actions.png")
            awaitTag("motion-result-preview")
            compose.onNodeWithTag("motion-result-preview").performScrollTo()
            screenshot("motion-$dark-$scale-$width-preview.png")
            compose.onNodeWithContentDescription(context.getString(R.string.motion_back)).performClick()
            compose.waitUntil(30_000) { closed }
            succeeded = true
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { visible = false }
            if (succeeded) cleanupMotionFixtureOrRetain("workflow source=$source outputs=$ownedOutputs") {
                check(hash == MotionPhotoSession.hash(source))
                val journal = MotionPhotoPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "motion-publications"))
                ownedOutputs.forEach { expected ->
                    check(journal.read(expected.result.publicationId) == null) // Both explicit closures retired only their marker.
                    deleteMotionFixtureOutput(context, expected.result)
                }
                check(source.delete())
            } else android.util.Log.e("MOTION_FIXTURE_RETAINED", "Workflow source=$source outputs=$ownedOutputs handoffs=$handoffs")
        }
    }

    @Test
    fun unavailableMotionReturnsToOriginal() {
        val source = MotionPhotoFixtures.create(context, false)
        var back = 0
        try {
            compose.setContent {
                UGalleryTheme {
                    MotionPhotoContent(
                        MotionPhotoFixtures.input(source),
                        null,
                        { _, _ -> false },
                        { back++ },
                    )
                }
            }
            awaitTag("motion-unsupported")
            compose.onNodeWithTag("motion-set-key-frame").assertDoesNotExist()
            click("motion-return-original")
            compose.waitUntil(15_000) { back == 1 }
            assertEquals(1, back)
        } finally {
            source.delete()
        }
    }

    @Test
    fun rejectedPersistenceDoesNotClaimKeyFrameSaved() {
        val source = MotionPhotoFixtures.create(context)
        try {
            compose.setContent {
                UGalleryTheme {
                    MotionPhotoContent(
                        MotionPhotoFixtures.input(source),
                        null,
                        { _, _ -> false },
                        {},
                    )
                }
            }
            awaitTag("motion-frame-5")
            click("motion-frame-2")
            click("motion-set-key-frame")
            awaitTag("motion-error")
            compose.onNodeWithTag("motion-key-saved").assertDoesNotExist()
            compose
                .onNodeWithTag("motion-key-frame")
                .performScrollTo()
                .assertTextEquals(context.getString(R.string.motion_original_key))
        } finally {
            source.delete()
        }
    }

    @Test fun frameAndClipRecoverWithoutOriginalAndAnotherResultRequiresExplicitAction() {
        val source = MotionPhotoFixtures.create(context)
        val held = File(source.parentFile, source.name + ".source-held")
        val input = MotionPhotoFixtures.input(source)
        val hash = MotionPhotoSession.hash(source)
        val duration = MotionPhotoFixtures.videoDurationUs(context)
        val selectedTime = (duration - 1) * 4 / 5
        val journal = MotionPhotoPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "motion-publications"))
        val handoffs = mutableListOf<Triple<String, Uri, MotionPhotoPublicationKind>>()
        val shares = mutableListOf<Triple<String, Uri, MotionPhotoPublicationKind>>()
        val outputs = mutableListOf<MotionFixtureOutput>()
        var available by mutableStateOf(true)
        var visible by mutableStateOf(true)
        var normalBack = 0
        var recoveryBack = 0
        var succeeded = false
        var clipDeleted = false
        val restore = StateRestorationTester(compose)
        try {
            restore.setContent {
                UGalleryTheme {
                    if (visible) MotionPhotoContent(input, null, { _, _ -> false }, { normalBack++ },
                        sourceAvailable = available, onBackKeepingRecovery = { recoveryBack++ },
                        onOpen = { id, uri, kind -> handoffs += Triple(id, uri, kind) },
                        onShare = { id, uri, kind -> shares += Triple(id, uri, kind) })
                }
            }
            awaitTag("motion-frame-5")
            click("motion-frame-4")
            click("motion-export-jpeg")
            awaitTag("motion-exported")
            click("motion-open")
            compose.waitUntil(30_000) { handoffs.size == 1 }
            outputs += captureMotionFixture(context, source, hash, handoffs.last(), selectedTime, duration)
            for (index in 0..1) {
                val expected = outputs[index]
                check(source.renameTo(held))
                compose.runOnIdle { available = false }
                restore.emulateSavedInstanceStateRestore()
                awaitTag("motion-exported")
                awaitTag("motion-result-preview")
                compose.onNodeWithTag("motion-player").assertDoesNotExist()
                compose.onNodeWithTag("motion-export-jpeg").assertDoesNotExist()
                compose.onNodeWithTag("motion-export-mp4").assertDoesNotExist()
                compose.onNodeWithTag("motion-create-another").performScrollTo().assertIsNotEnabled()
                assertEquals(index * 2 + 1, handoffs.size) // Restore and static preview cause no implicit handoff.
                click("motion-open")
                compose.waitUntil(30_000) { handoffs.size == index * 2 + 2 }
                click("motion-share")
                compose.waitUntil(30_000) { shares.size == index + 1 }
                assertEquals(Triple(expected.result.publicationId, Uri.parse(expected.result.destination!!.uri), expected.result.kind), handoffs.last())
                assertEquals(handoffs.last(), shares.last())
                assertEquals(hash, MotionPhotoSession.hash(held))
                if (index == 0) {
                    check(held.renameTo(source))
                    compose.runOnIdle { available = true }
                    click("motion-create-another")
                    awaitTag("motion-frame-5")
                    compose.onNodeWithTag("motion-result-preview").assertDoesNotExist()
                    check(journal.read(expected.result.publicationId) == null)
                    verifyMotionFixtureOutput(context, expected.result)
                    click("motion-export-mp4")
                    awaitTag("motion-exported")
                    click("motion-open")
                    compose.waitUntil(30_000) { handoffs.size == 3 }
                    outputs += captureMotionFixture(context, source, hash, handoffs.last(), null, duration)
                    assertNotEquals(expected.result.publicationId, outputs.last().result.publicationId)
                }
            }
            val clip = outputs.last()
            deleteMotionFixtureOutput(context, clip.result)
            clipDeleted = true
            click("motion-open")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("motion-open").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("motion-share").assertDoesNotExist()
            compose.onNodeWithTag("motion-create-another").assertDoesNotExist()
            assertEquals(4, handoffs.size); assertEquals(2, shares.size)
            compose.onNodeWithContentDescription(context.getString(R.string.motion_back)).performClick()
            compose.waitUntil(30_000) { recoveryBack == 1 }
            assertEquals(0, normalBack)
            check(journal.read(clip.result.publicationId) == clip.marker)
            succeeded = true
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { visible = false }
            if (succeeded) cleanupMotionFixtureOrRetain("source=$source held=$held outputs=$outputs") {
                check(clipDeleted && !source.exists())
                check(hash == MotionPhotoSession.hash(held))
                check(journal.read(outputs.first().result.publicationId) == null)
                deleteMotionFixtureOutput(context, outputs.first().result)
                val clip = outputs.last()
                assertMotionFixtureAbsent(context, Uri.parse(clip.result.destination!!.uri))
                check(journal.read(clip.result.publicationId) == clip.marker)
                check(journal.retire(clip.marker)); check(journal.read(clip.result.publicationId) == null)
                check(held.delete())
            } else android.util.Log.e("MOTION_FIXTURE_RETAINED", "source=$source held=$held deleted=$clipDeleted outputs=$outputs handoffs=$handoffs")
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun screenshot(name: String) {
        val dir = File(context.filesDir, "motion-evidence").apply { mkdirs() }
        File(dir, name).outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}

private data class MotionFixtureOutput(val result: MotionPhotoPublicationReceipt, val marker: MotionPhotoPublicationReceipt)

private fun captureMotionFixture(context: android.content.Context, source: File, hash: String,
    handoff: Triple<String, Uri, MotionPhotoPublicationKind>, selectedTimeUs: Long?, durationUs: Long): MotionFixtureOutput {
    val journal = MotionPhotoPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "motion-publications"))
    val stored = checkNotNull(journal.read(handoff.first))
    val recovered = runBlocking { MotionPhotoPublication(context).reconcile(handoff.first) }
    check(recovered.status == MotionPhotoPublicationStatus.Published)
    val result = checkNotNull(recovered.receipt)
    check(result.sourceIdentity == MotionPhotoFixtures.input(source).identity && result.sourceSha256 == hash)
    check(result.sourceUri == Uri.fromFile(source).toString() && result.generationModified == null && result.generationAdded == null)
    check(result.publicationId == handoff.first && result.destination?.uri == handoff.second.toString() && result.kind == handoff.third)
    check(result.selectedTimeUs == selectedTimeUs && result.durationUs == durationUs)
    check(stored.sameRequest(result))
    check(journal.read(handoff.first) == stored)
    return MotionFixtureOutput(result, stored)
}

private fun motionFixtureMetadata(context: android.content.Context, uri: Uri): List<String> =
    checkNotNull(context.contentResolver.query(uri, motionFixtureColumns, null, null, null)).use { cursor ->
        check(cursor.moveToFirst())
        List(motionFixtureColumns.size) { cursor.getString(it).orEmpty() }.also { check(!cursor.moveToNext()) }
    }
private val motionFixtureColumns = arrayOf("owner_package_name", "_display_name", "relative_path", "mime_type",
    "generation_added", "generation_modified", "_size", "is_pending", "is_trashed")
private fun motionFixtureExpected(expected: MotionPhotoPublicationReceipt): List<String> {
    val d = checkNotNull(expected.destination)
    return listOf(d.ownerPackage, d.displayName, d.relativePath, d.mimeType, d.generationAdded.toString(),
        d.generationModified.toString(), d.sizeBytes.toString(), "0", "0")
}
private fun verifyMotionFixtureOutput(context: android.content.Context, expected: MotionPhotoPublicationReceipt) {
    val d = checkNotNull(expected.destination)
    check(expected.phase == MotionPhotoPublicationPhase.Published && d.ownerPackage == context.packageName && !d.pending && !d.trashed)
    val uri = Uri.parse(d.uri)
    check(motionFixtureMetadata(context, uri) == motionFixtureExpected(expected))
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    checkNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n; check(total <= expected.renderSizeBytes)
            digest.update(buffer, 0, n)
        }
        check(total == expected.renderSizeBytes)
    }
    check(digest.digest().joinToString("") { "%02x".format(it) } == expected.renderSha256)
    check(motionFixtureMetadata(context, uri) == motionFixtureExpected(expected))
}
private fun assertMotionFixtureAbsent(context: android.content.Context, uri: Uri) {
    checkNotNull(context.contentResolver.query(uri, arrayOf("_id"), null, null, null)).use { check(!it.moveToFirst()) }
}
private fun deleteMotionFixtureOutput(context: android.content.Context, expected: MotionPhotoPublicationReceipt) {
    verifyMotionFixtureOutput(context, expected)
    val uri = Uri.parse(expected.destination!!.uri)
    check(context.contentResolver.delete(uri, motionFixtureColumns.joinToString(" AND ") { "$it=?" },
        motionFixtureExpected(expected).toTypedArray()) == 1)
    assertMotionFixtureAbsent(context, uri)
}
private inline fun cleanupMotionFixtureOrRetain(identity: String, action: () -> Unit) {
    try { action() }
    catch (failure: Throwable) {
        android.util.Log.e("MOTION_FIXTURE_RETAINED", "Cleanup failed; remaining fixture retained: $identity", failure)
        throw failure
    }
}
