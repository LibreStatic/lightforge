package com.librestatic.lightforge.core.storageadvanced

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Measures the ADR-046 open checks on a real device (API 30+): MediaStore keeps `media_type=0` rows for
 * `.nomedia` content, FUSE renames keep the `_ID`, and unhiding works without the permission.
 * Observations are written to logcat under tag [TAG].
 */
class AdvancedStorageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val resolver: ContentResolver = context.contentResolver
    private val storage = FileAdvancedStorage(context)
    private val pictures = File(Environment.getExternalStorageDirectory(), "Pictures")
    private val probe = File(pictures, "LfHiddenProbe")
    private val renamed = File(pictures, "LfHiddenProbeRenamed")
    private val moved = File(pictures, "LfHiddenProbeParent")

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).readBytes()
        }
    }

    /**
     * Changing MANAGE_EXTERNAL_STORAGE kills the app process (ActivityManager: "MANAGE_EXTERNAL_STORAGE
     * changed"), so a change from inside the run ends the run. Pre-grant from the host once per install:
     * `adb shell appops set <test package> MANAGE_EXTERNAL_STORAGE allow`. This only changes the mode when needed.
     */
    private fun setAllFiles(allow: Boolean) {
        if (storage.isGranted() == allow) return
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE ${if (allow) "allow" else "deny"}")
        // The appop change propagates asynchronously.
        repeat(50) {
            if (storage.isGranted() == allow) return
            Thread.sleep(100)
        }
    }

    private fun cleanup() {
        listOf(probe, renamed, moved).forEach { it.deleteRecursively() }
        runCatching {
            resolver.delete(
                MediaStore.Files.getContentUri("external"),
                "${MediaStore.Files.FileColumns.DATA} LIKE ?",
                arrayOf("${pictures.path}/LfHiddenProbe%"),
            )
        }
    }

    private val phase: String? = InstrumentationRegistry.getArguments().getString("phase")

    @Before fun setUp() {
        if (phase == "verify") return
        setAllFiles(true)
        cleanup()
        probe.mkdirs()
    }

    @After fun tearDown() {
        // Changing the MANAGE_EXTERNAL_STORAGE appop kills this process, so the verify phase never
        // re-grants; the host does that afterwards (appops set ... allow) and runs an unphased cleanup pass.
        if (phase == "prepare" || phase == "verify") return
        setAllFiles(true)
        runBlocking { storage.unhideAllRecorded() }
        cleanup()
    }

    private fun writePng(dir: File, name: String): File {
        val file = File(dir, name)
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun scanAndWait(vararg paths: String) {
        val latch = java.util.concurrent.CountDownLatch(paths.size)
        android.media.MediaScannerConnection.scanFile(context, paths, null) { _, _ -> latch.countDown() }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun idOf(collection: Uri, path: String): Long? =
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA}=?",
            arrayOf(path),
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun relativePathOf(id: Long): String? =
        resolver.query(
            ContentUriFiles.external,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns._ID}=?",
            arrayOf(id.toString()),
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun mediaTypeOf(id: Long): Int? =
        resolver.query(
            ContentUriFiles.external,
            arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE),
            "${MediaStore.MediaColumns._ID}=?",
            arrayOf(id.toString()),
            null,
        )?.use { if (it.moveToFirst()) it.getInt(0) else null }

    private fun log(message: String) {
        Log.i(TAG, message)
        println("$TAG: $message")
    }

    @Test fun hideRefusesAStandardFolderAndLeavesNoMarker() = runBlocking {
        assumeTrue(storage.isGranted())
        val pictures = File(Environment.getExternalStorageDirectory(), "Pictures")
        assertTrue(storage.hideFolder(pictures.path) is AdvancedResult.Failed)
        assertFalse(File(pictures, ".nomedia").exists())
    }

    @Test fun hideUnhideAndRenamesKeepMediaStoreIds() = runBlocking {
        assumeTrue(storage.isGranted())
        val png = writePng(probe, "probe.png")
        scanAndWait(png.path)
        val imageId = requireNotNull(idOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, png.path)) { "not indexed" }
        log("indexed as image _ID=$imageId")

        // Hide: the image row goes away, a media_type=0 row with the same _ID appears.
        assertEquals(AdvancedResult.Done, storage.hideFolder(probe.path))
        val afterHideImages = idOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, png.path)
        val afterHideFileId = idOf(ContentUriFiles.external, png.path)
        val hidden = storage.queryHiddenMedia().firstOrNull { it.displayName == "probe.png" && it.relativePath.contains("LfHiddenProbe") }
        log(
            "after hide: images=$afterHideImages files=$afterHideFileId mediaType=${afterHideFileId?.let(::mediaTypeOf)} " +
                "queryHiddenMedia=${hidden?.mediaStoreId}",
        )
        assertEquals(null, afterHideImages)
        assertEquals(imageId, afterHideFileId)
        assertEquals(imageId, hidden?.mediaStoreId)

        // Unhide: media_type=1 again with the same _ID.
        assertEquals(AdvancedResult.Done, storage.unhideFolder(probe.path))
        val afterUnhide = idOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, png.path)
        log("after unhide: images=$afterUnhide mediaType=${afterUnhide?.let(::mediaTypeOf)}")
        assertEquals(imageId, afterUnhide)
        assertEquals(1, mediaTypeOf(imageId))

        // File rename keeps the _ID and updates RELATIVE_PATH.
        val target = File(renamed, "probe.png")
        assertEquals(AdvancedResult.Done, storage.renameInPlace(png.path, target.path))
        val afterFileRename = idOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, target.path)
        log("after file rename: _ID=$afterFileRename relativePath=${afterFileRename?.let(::relativePathOf)}")
        assertEquals(imageId, afterFileRename)
        assertTrue(relativePathOf(imageId).orEmpty().contains("LfHiddenProbeRenamed"))

        // Directory rename keeps the _ID of the files inside.
        assertEquals(AdvancedResult.Done, storage.createDirectory(pictures.path, "LfHiddenProbeParent"))
        assertEquals(AdvancedResult.Done, storage.moveDirectory(renamed.path, moved.path))
        val afterDirMove = idOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, File(moved, "LfHiddenProbeRenamed/probe.png").path)
        log("after directory move: _ID=$afterDirMove relativePath=${afterDirMove?.let(::relativePathOf)}")
        assertEquals(imageId, afterDirMove)
        assertTrue(relativePathOf(imageId).orEmpty().contains("LfHiddenProbeParent/LfHiddenProbeRenamed"))
        assertEquals(AdvancedResult.Done, storage.renameDirectory(File(moved, "LfHiddenProbeRenamed").path, "Final"))
        val afterDirRename = idOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, File(moved, "Final/probe.png").path)
        log("after directory rename: _ID=$afterDirRename relativePath=${afterDirRename?.let(::relativePathOf)}")
        assertEquals(imageId, afterDirRename)
        assertTrue(relativePathOf(imageId).orEmpty().contains("LfHiddenProbeParent/Final"))
    }

    /**
     * Revoking All files access kills the app process, so the denied state cannot be reached inside
     * one run. Two phases, driven from the host (both skipped in a plain `connectedAndroidTest`):
     * `-e phase prepare`, then `appops set <pkg> MANAGE_EXTERNAL_STORAGE deny`, then `-e phase verify`.
     */
    @Test fun deniedPhase1Prepare() = runBlocking {
        assumeTrue(phase == "prepare")
        assumeTrue(storage.isGranted())
        val png = writePng(probe, "probe.png")
        scanAndWait(png.path)
        assertEquals(AdvancedResult.Done, storage.hideFolder(probe.path))
        assertTrue(storage.queryHiddenMedia().any { it.displayName == "probe.png" })
    }

    @Test fun deniedPhase2Verify() = runBlocking {
        assumeTrue(phase == "verify")
        assertFalse(storage.isGranted())
        assertTrue(storage.queryHiddenMedia().isEmpty())
        assertEquals(AdvancedResult.NotGranted, storage.hideFolder(probe.path))
        val markerBefore = File(probe, ".nomedia").exists()
        val result = storage.unhideFolder(probe.path)
        log("unhide after deny: $result markerBefore=$markerBefore markerAfter=${File(probe, ".nomedia").exists()}")
        assertTrue(
            "unexpected $result",
            result == AdvancedResult.Done || result is AdvancedResult.NeedsTreeGrant,
        )
        if (result == AdvancedResult.Done) assertFalse(File(probe, ".nomedia").exists())
    }

    private object ContentUriFiles {
        val external: Uri = MediaStore.Files.getContentUri("external")
    }

    private companion object {
        const val TAG = "AdvancedStorageProbe"
    }
}
