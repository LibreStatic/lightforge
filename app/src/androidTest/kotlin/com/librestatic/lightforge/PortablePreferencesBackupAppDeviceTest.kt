package com.librestatic.lightforge

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.core.model.*
import com.librestatic.lightforge.core.preferences.*
import com.librestatic.lightforge.feature.settings.BackupManifest
import com.librestatic.lightforge.feature.settings.LocalBackupArchive
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * A real DocumentsUI archive review; no injected provider, route callback or settings apply call.
 */
class PortablePreferencesBackupAppDeviceTest {
    @Test
    fun realArchiveSelectivePreferencesSurviveRecreationAndReturnToSameReview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        val repository = GallerySettingsRepository(context)
        val owner = "portable-preferences-app-${UUID.randomUUID()}"
        val evidence = File(context.filesDir, owner).apply { mkdirs() }
        val archiveName = "$owner.lightforge.zip"
        val archive = File(context.cacheDir, archiveName)
        var ownedUri: Uri? = null
        var beforeApply: GallerySettings? = null
        var appliedColumn: Int? = null
        var testFailure: Throwable? = null
        fun hash(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        fun capture(label: String) {
            device.takeScreenshot(File(evidence, "$label.png"))
            device.dumpWindowHierarchy(File(evidence, "$label.xml"))
        }
        fun await(label: String, timeout: Long = 20000, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) {
                if (condition()) return
                SystemClock.sleep(100)
            }
            capture("timeout-$label")
            error("Timed out: $label")
        }
        fun nodes(): List<AccessibilityNodeInfo> {
            val list = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                list += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
            return list
        }
        fun scroll(backward: Boolean): Boolean {
            val node =
                nodes()
                    .filter { it.isVisibleToUser && it.isScrollable }
                    .maxByOrNull {
                        val bounds = Rect()
                        it.getBoundsInScreen(bounds)
                        bounds.width() * bounds.height()
                    } ?: return false
            return node.performAction(
                if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            )
        }
        fun action(text: String? = null, tag: String? = null, description: String? = null) {
            var backwards = false
            await("action-${tag ?: text ?: description}") {
                val candidates =
                    nodes().filter { node ->
                        node.isVisibleToUser &&
                            node.isEnabled &&
                            (if (tag != null) node.viewIdResourceName == tag
                            else if (description != null)
                                node.contentDescription?.toString() == description
                            else node.text?.toString() == text)
                    }
                for (candidate in candidates) {
                    var node: AccessibilityNodeInfo? = candidate
                    var depth = 0
                    while (node != null && !node.isClickable && depth++ < 8) node = node.parent
                    if (
                        node?.isClickable == true &&
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) {
                        device.waitForIdle()
                        return@await true
                    }
                }
                if (!scroll(backwards)) backwards = !backwards
                device.waitForIdle()
                false
            }
        }
        fun label(id: Int) = context.getString(id)
        fun textAction(id: Int) = action(text = label(id))
        fun visible(selector: BySelector) = device.hasObject(selector)
        fun reveal(selector: BySelector, label: String) {
            var backwards = false
            await(label, 60000) {
                val found = device.findObject(selector)
                if (found != null && !found.visibleBounds.isEmpty) return@await true
                if (!scroll(backwards)) backwards = !backwards
                device.waitForIdle()
                false
            }
        }
        fun receiptCount(): Long {
            val database = GalleryDatabaseFactory.open(context)
            try {
                return database.openHelper.readableDatabase
                    .query("SELECT COUNT(*) FROM gallery_restore_receipts")
                    .use {
                        check(it.moveToFirst())
                        it.getLong(0)
                    }
            } finally {
                database.close()
            }
        }
        try {
            val initial = runBlocking { repository.settings.first() }
            val targetColumn = if (initial.thumbnails.gridColumns == 7) 8 else 7
            val preferences =
                runBlocking { repository.exportJson() }
                    .apply {
                        // Keep the existing snapshot JSON schema, with one present Presentation
                        // field.
                        remove("library")
                        put("thumbnails", JSONObject().put("gridColumns", targetColumn))
                        getJSONObject("playback").put("loopVideos", !initial.playback.loopVideos)
                        getJSONObject("gestures").put("pinchZoom", !initial.gestures.pinchZoom)
                        getJSONObject("security")
                            .put("appLockEnabled", !initial.security.appLockEnabled)
                        getJSONObject("operations")
                            .put(
                                "skipAppDeleteConfirmation",
                                !initial.operations.skipAppDeleteConfirmation,
                            )
                        getJSONObject("analysis")
                            .put(
                                "fullAnalysisMinimumBatteryPercent",
                                if (initial.analysis.fullAnalysisMinimumBatteryPercent == 50) 20
                                else 50,
                            )
                    }
                    .toString()
            val image =
                Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.rgb(90, 120, 170))
                }
            val original =
                try {
                    ByteArrayOutputStream().use { out ->
                        check(image.compress(Bitmap.CompressFormat.PNG, 100, out))
                        out.toByteArray()
                    }
                } finally {
                    image.recycle()
                }
            val sourceId = UUID.randomUUID().toString()
            val entry =
                BackupManifest.Entry(
                    BackupManifest.path(0),
                    "$owner.png",
                    "image/png",
                    original.size.toLong(),
                    hash(original),
                    sourceId,
                )
            val snapshot =
                PortableOrganizationSnapshot(
                        snapshotId = UUID.randomUUID().toString(),
                        originNamespace = UUID.randomUUID().toString(),
                        createdAtMillis = 1,
                        scope = PortableOrganizationScope(1, 1, 0),
                        sources =
                            listOf(
                                PortableSourceFacts(
                                    sourceId,
                                    entry.sha256,
                                    entry.bytes,
                                    PortableMediaKind.Image,
                                    0,
                                    null,
                                    false,
                                    null,
                                )
                            ),
                        preferencesJsonForReview = preferences,
                    )
                    .validate()
            val sidecar = PortableOrganizationCodec.encode(snapshot)
            val manifest =
                BackupManifest(
                    listOf(entry),
                    BackupManifest.Organization(1, sidecar.size.toLong(), hash(sidecar)),
                )
            ZipOutputStream(archive.outputStream()).use { zip ->
                listOf(
                        entry.path to original,
                        BackupManifest.ORGANIZATION_PATH to sidecar,
                        BackupManifest.PATH to manifest.encode(),
                    )
                    .forEach { (path, bytes) ->
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
            }
            assertEquals(manifest, LocalBackupArchive.inspect(archive))
            val archiveHash = hash(archive.readBytes())
            val uri =
                resolver
                    .insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, archiveName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$owner/")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    )!!
                    .also { ownedUri = it }
            resolver.openOutputStream(uri, "w")!!.use { out ->
                archive.inputStream().use { it.copyTo(out) }
            }
            assertEquals(
                1,
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ),
            )
            assertEquals(archiveHash, resolver.openInputStream(uri)!!.use { hash(it.readBytes()) })
            val intent =
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                try {
                    action(description = label(com.librestatic.lightforge.feature.photos.R.string.open_settings))
                    textAction(com.librestatic.lightforge.feature.settings.R.string.settings_backup)
                    textAction(com.librestatic.lightforge.feature.settings.R.string.local_backup_title)
                    await("backup-ready") {
                        visible(
                            By.text(
                                label(
                                    com.librestatic.lightforge.feature.settings.R.string.local_backup_local_hint
                                )
                            )
                        )
                    }
                    textAction(com.librestatic.lightforge.feature.settings.R.string.local_backup_open)
                    val document = By.res("android:id/title").text(archiveName)
                    if (!device.wait(Until.hasObject(document), 1500)) {
                        // The source picker is real DocumentsUI, not a fixture URI grant.
                        device.wait(Until.findObject(By.desc("Show roots")), 5000)?.click()
                        val downloads = By.res("android:id/title").text("Downloads")
                        checkNotNull(device.wait(Until.findObject(downloads), 5000)).click()
                        val folder = By.res("android:id/title").text(owner)
                        checkNotNull(device.wait(Until.findObject(folder), 5000)).click()
                    }
                    checkNotNull(device.wait(Until.findObject(document), 5000)).click()
                    reveal(By.text(archiveName), "archive-reviewed")
                    val receiptsBefore = receiptCount()
                    beforeApply = runBlocking { repository.settings.first() }
                    assertNotEquals(targetColumn, beforeApply!!.thumbnails.gridColumns)
                    capture("archive-review")
                    textAction(com.librestatic.lightforge.feature.settings.R.string.portable_preferences_title)
                    reveal(By.res("portable-preferences-select-Presentation"), "preferences-ready")
                    // Review and selection have no settings side effect.
                    assertEquals(beforeApply, runBlocking { repository.settings.first() })
                    action(tag = "portable-preferences-select-Presentation")
                    action(tag = "portable-preferences-review-apply")
                    await("confirmation") { visible(By.res("portable-preferences-confirm")) }
                    assertEquals(beforeApply, runBlocking { repository.settings.first() })
                    capture("confirmation")
                    action(tag = "portable-preferences-confirm")
                    // Mark the expected owned write before awaiting UI, so failed observers can
                    // compensate only if the atomic field still equals this operation's value.
                    appliedColumn = targetColumn
                    reveal(By.res("portable-preferences-result"), "applied")
                    val expected =
                        beforeApply!!.copy(
                            thumbnails = beforeApply!!.thumbnails.copy(gridColumns = targetColumn)
                        )
                    assertEquals(expected, runBlocking { repository.settings.first() })
                    capture("applied")
                    scenario.recreate()
                    reveal(
                        By.text(
                            label(
                                com.librestatic.lightforge.feature.settings.R.string.portable_preferences_replayed
                            )
                        ),
                        "recreated-replay",
                    )
                    assertEquals(expected, runBlocking { repository.settings.first() })
                    capture("recreated-replay")
                    action(tag = "portable-preferences-back")
                    reveal(By.text(archiveName), "same-archive")
                    reveal(By.text(entry.name), "same-original-entry")
                    assertEquals(receiptsBefore, receiptCount())
                    assertEquals(
                        archiveHash,
                        resolver.openInputStream(uri)!!.use { hash(it.readBytes()) },
                    )
                    capture("returned-same-archive")
                    File(evidence, "result.json")
                        .writeText(
                            JSONObject()
                                .put("status", "PASS")
                                .put("archiveSha256", archiveHash)
                                .put("sourceSha256", entry.sha256)
                                .put("realDocumentsUi", true)
                                .put("archiveVersion", 2)
                                .put("gridColumnsBefore", beforeApply!!.thumbnails.gridColumns)
                                .put("gridColumnsAfter", targetColumn)
                                .put("unselectedAndSecurityUnchanged", true)
                                .put("recreationReplay", true)
                                .put("sameArchiveReturned", true)
                                .put("mediaRestoreReceiptsUnchanged", true)
                                .toString(2)
                        )
                } catch (failure: Throwable) {
                    capture("failure-before-close")
                    File(evidence, "failure.txt").writeText(failure.stackTraceToString())
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            try {
                val previous = beforeApply
                val expected = appliedColumn
                if (previous != null && expected != null)
                    runBlocking {
                        repository.update { current ->
                            // Field-level CAS inside DataStore.edit preserves all unrelated
                            // settings.
                            check(current.thumbnails.gridColumns == expected) {
                                "Owned column changed concurrently; cleanup did not overwrite it"
                            }
                            current.copy(
                                thumbnails =
                                    current.thumbnails.copy(
                                        gridColumns = previous.thumbnails.gridColumns
                                    )
                            )
                        }
                        File(evidence, "cleanup.json")
                            .writeText(
                                JSONObject()
                                    .put("status", "PASS")
                                    .put("fieldLevelCas", true)
                                    .put("restoredGridColumns", previous.thumbnails.gridColumns)
                                    .toString(2)
                            )
                    }
            } catch (cleanup: Throwable) {
                File(evidence, "cleanup-failure.txt").writeText(cleanup.stackTraceToString())
                if (testFailure != null) testFailure!!.addSuppressed(cleanup) else throw cleanup
            } finally {
                ownedUri?.let { resolver.delete(it, null, null) }
                archive.delete()
            }
        }
    }
}
