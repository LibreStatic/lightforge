package com.ugallery.app

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.ugallery.feature.motionphotos.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real root entry and global UI; prepared Ready/partial fixtures, not a second renderer test. */
@Suppress("DEPRECATION")
class PublicationRecoveriesAppDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val journal get() = MotionPhotoPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "motion-publications"))
    private data class Fixture(val id: String, val uri: Uri, val base: MotionPhotoPublicationDestination,
        val expectedBytes: ByteArray, val directory: File, val missingSource: File)

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun outputBytes(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun metadata(uri: Uri): MotionPhotoPublicationDestination? = resolver.query(uri, arrayOf(
        "owner_package_name", "_display_name", "relative_path", "mime_type", "generation_added",
        "generation_modified", "_size", "is_pending", "is_trashed"), null, null, null)!!.use {
        if (!it.moveToFirst()) return@use null
        MotionPhotoPublicationDestination(uri.toString(), it.getString(0), it.getString(1), it.getString(2), it.getString(3),
            it.getLong(4), it.getLong(5), it.getLong(6), it.getInt(7) != 0, it.getInt(8) != 0).also { _ -> check(!it.moveToNext()) }
    }
    private fun persist(f: Fixture, label: String, value: JSONObject) {
        val file = File(f.directory, "$label.json"); check(!file.exists())
        file.outputStream().use { it.write(value.toString().toByteArray()); it.fd.sync() }
    }
    private fun prepare(ready: Boolean): Fixture {
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        val id = UUID.randomUUID().toString(); val token = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "publication-recovery-ui-$id").apply { check(mkdir()) }
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = try { java.io.ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray() }
            finally { bitmap.recycle() }
        val original = File(directory, "original.jpg").apply { writeBytes(bytes) }
        val source = Uri.fromFile(original).toString()
        val intent = MotionPhotoPublicationReceipt(id, token, source, null, null, "$source@null/null", hash(bytes),
            MotionPhotoPublicationKind.Frame, 1_500_000L, 3_000_000L, hash(bytes), bytes.size.toLong())
        journal.begin(intent)
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put("_display_name", "UGallery-Motion-$token.jpg"); put("mime_type", "image/jpeg")
            put("relative_path", "Pictures/UGallery/Motion/"); put("is_pending", 1)
        }))
        val base = checkNotNull(metadata(uri)); check(base.ownerPackage == context.packageName)
        val inserted = intent.copy(destination = base, phase = MotionPhotoPublicationPhase.Inserted)
        journal.advance(intent, inserted)
        val written = if (ready) bytes else bytes.copyOfRange(0, 19)
        resolver.openFileDescriptor(uri, "w")!!.use { descriptor ->
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(written); it.fd.sync() }
        }
        check(outputBytes(uri).contentEquals(written))
        if (ready) journal.advance(inserted, inserted.copy(destination = checkNotNull(metadata(uri)), phase = MotionPhotoPublicationPhase.Ready))
        check(original.readBytes().contentEquals(bytes)); check(original.delete() && !original.exists())
        val f = Fixture(id, uri, base, written, directory, original)
        persist(f, "prepared", JSONObject().put("id", id).put("uri", uri).put("generationAdded", base.generationAdded)
            .put("phase", if (ready) "Ready" else "Inserted").put("sourceGone", true).put("writtenBytes", written.size)
            .put("writtenSha256", hash(written)).put("renderSha256", hash(bytes)))
        return f
    }
    private fun waitTag(tag: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun click(tag: String) {
        waitTag(tag)
        val node = compose.onNodeWithTag(tag)
        try { node.performScrollTo() } catch (_: AssertionError) { node.assertIsDisplayed() }
        node.assertIsEnabled().performClick()
    }
    private fun open(f: Fixture) {
        val label = context.getString(com.ugallery.feature.photos.R.string.photos_create)
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription(label).onFirst().performClick()
        click("publication-recoveries-entry")
        waitTag("publication-recoveries-screen")
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("publication-recoveries-refresh") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val row = "publication-recovery-row-motion-${f.id}"
        compose.onNodeWithTag("publication-recoveries-list").performScrollToNode(hasTestTag(row))
        click(row)
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(row) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("publication-recoveries-list").performScrollToNode(hasTestTag("publication-recoveries-detail"))
    }
    private fun confirmation(action: String) {
        click(action); waitTag("publication-recoveries-confirmation"); click("publication-recoveries-confirm")
    }
    private fun resumedActivityRecords(dump: String): Map<String, String> {
        check(Regex("(?m)^ACTIVITY MANAGER ACTIVITIES \\(dumpsys activity activities\\)\\s*$").containsMatchIn(dump)) {
            "Unrecognized activity dump; resumed receiver is unverified"
        }
        val record = Regex("^\\s*(?:mResumedActivity|ResumedActivity):\\s*ActivityRecord\\{[^\\s}]+\\s+u0\\s+([^\\s}]+)\\s+t[1-9][0-9]*(?:\\s[^}]*)?\\}\\s*$")
        return dump.lineSequence().mapNotNull { line ->
            record.matchEntire(line)?.let { it.groupValues[1] to line.trim() }
        }.toMap()
    }
    private fun awaitResumed(expectedComponents: Set<String>): String {
        val device = UiDevice.getInstance(instrumentation)
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        var last = ""
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val records = resumedActivityRecords(device.executeShellCommand("dumpsys activity activities"))
            last = records.values.joinToString("\n")
            if (records.keys.any { it in expectedComponents }) return last
            android.os.SystemClock.sleep(100)
        }
        error("Expected resumed activity, observed: $last")
    }
    private fun assertOriginalAnchor(f: Fixture, current: MotionPhotoPublicationDestination) {
        assertEquals(f.uri.toString(), current.uri); assertEquals(f.base.ownerPackage, current.ownerPackage)
        assertEquals(f.base.generationAdded, current.generationAdded); assertEquals(f.base.displayName, current.displayName)
        assertEquals(f.base.relativePath, current.relativePath); assertEquals(f.base.mimeType, current.mimeType)
        assertFalse(current.trashed); assertTrue(current.generationModified >= f.base.generationModified)
    }
    private fun deleteVerifiedOutput(f: Fixture) {
        val current = checkNotNull(metadata(f.uri)); assertOriginalAnchor(f, current)
        assertArrayEquals(f.expectedBytes, outputBytes(f.uri)); assertEquals(current, metadata(f.uri))
        persist(f, "predelete", JSONObject().put("uri", f.uri).put("generationAdded", current.generationAdded)
            .put("generationModified", current.generationModified).put("sha256", hash(f.expectedBytes)).put("status", "VERIFIED_BEFORE_DELETE"))
        assertEquals(1, resolver.delete(f.uri,
            "owner_package_name=? AND _display_name=? AND relative_path=? AND mime_type=? AND generation_added=? AND generation_modified=? AND COALESCE(_size,0)=CAST(? AS INTEGER) AND is_pending=? AND is_trashed=0",
            arrayOf(current.ownerPackage, current.displayName, current.relativePath, current.mimeType,
                current.generationAdded.toString(), current.generationModified.toString(), current.sizeBytes.toString(), if (current.pending) "1" else "0")))
        assertNull(metadata(f.uri))
    }

    @Test fun globalReadyCompletionPreviewHandoffsAndForgetKeepThePublishedCopyWithoutOriginal(): Unit = runBlocking {
        val f = prepare(ready = true)
        open(f)
        confirmation("publication-recoveries-complete")
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("publication-recoveries-open") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val receipt = checkNotNull(journal.read(f.id)); assertEquals(MotionPhotoPublicationPhase.Published, receipt.phase)
        assertFalse(f.missingSource.exists()); assertArrayEquals(f.expectedBytes, outputBytes(f.uri))
        waitTag("publication-recoveries-preview")
        val preview = compose.onNodeWithTag("publication-recoveries-preview")
        try { preview.performScrollTo() } catch (_: AssertionError) { preview.assertIsDisplayed() }
        val pixels = preview.captureToImage().toPixelMap(); val color = pixels[pixels.width / 2, pixels.height / 2].toArgb()
        assertTrue(Color.blue(color) >= 247 && Color.red(color) <= 8 && Color.green(color) <= 8)
        val observed = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CHOOSER) observed.set(Intent(intent))
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            for ((tag, action) in listOf("publication-recoveries-open" to Intent.ACTION_VIEW, "publication-recoveries-share" to Intent.ACTION_SEND)) {
                observed.set(null); click(tag)
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                while (observed.get() == null && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(50)
                val chooser = checkNotNull(observed.get()); val target = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                assertEquals(action, target.action); assertEquals("image/jpeg", target.type)
                assertEquals(f.uri, if (action == Intent.ACTION_VIEW) target.data else target.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                for (request in listOf(target, chooser)) {
                    assertTrue(request.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    assertEquals(1, request.clipData?.itemCount); assertEquals(f.uri, request.clipData?.getItemAt(0)?.uri)
                }
                val resumed = awaitResumed(setOf("android/com.android.internal.app.ChooserActivity",
                    "android/com.android.internal.app.ResolverActivity",
                    "com.android.intentresolver/.ChooserActivityLauncher"))
                persist(f, action.substringAfterLast('.'), JSONObject().put("uri", f.uri).put("action", action)
                    .put("readGrant", true).put("clipUri", f.uri).put("resumedChooser", resumed))
                UiDevice.getInstance(instrumentation).pressBack()
                awaitResumed(setOf(context.packageName + "/" + MainActivity::class.java.name))
                waitTag("publication-recoveries-screen")
            }
        } finally { instrumentation.removeMonitor(monitor) }
        confirmation("publication-recoveries-forget")
        compose.waitUntil(30_000) { !journal.hasEntry(f.id) }
        assertArrayEquals(f.expectedBytes, outputBytes(f.uri)); assertFalse(f.missingSource.exists())
        deleteVerifiedOutput(f)
        persist(f, "result", JSONObject().put("status", "PASS").put("sourceGone", true).put("trackingAbsent", true).put("outputKeptAfterForget", true).put("fixtureCleanupComplete", true))
    }

    @Test fun globalPartialPendingRemovalNeverPublishesOrRequiresOriginal(): Unit = runBlocking {
        val f = prepare(ready = false)
        open(f)
        waitTag("publication-recoveries-remove-pending")
        assertEquals(MotionPhotoPublicationPhase.Inserted, journal.read(f.id)?.phase)
        assertArrayEquals(f.expectedBytes, outputBytes(f.uri)); assertTrue(checkNotNull(metadata(f.uri)).pending)
        confirmation("publication-recoveries-remove-pending")
        compose.waitUntil(30_000) { !journal.hasEntry(f.id) }
        assertNull(metadata(f.uri)); assertFalse(f.missingSource.exists())
        persist(f, "result", JSONObject().put("status", "PASS").put("partialBytes", f.expectedBytes.size).put("neverPublished", true)
            .put("sourceGone", true).put("trackingAbsent", true).put("fixtureCleanupComplete", true))
    }
}
