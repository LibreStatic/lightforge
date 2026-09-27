package com.librestatic.lightforge

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import androidx.lifecycle.ViewModelProvider
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.core.mediastore.*
import com.librestatic.lightforge.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern

class VerifiedMovePickerRecreationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun pendingMoveRetainsSourceAcrossBackgroundRecreationAndViewerChange(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val resolver = context.contentResolver
        val uuid = UUID.randomUUID().toString()
        val authority = instrumentation.context.packageName + ".safcopyfixture"
        val rootTitle = "Lightforge SAF $uuid"
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "$uuid:root")
        val directory = File(context.noBackupFilesDir.canonicalFile, "verified-move")
        check(!directory.exists() || directory.listFiles()!!.isEmpty())
        val journal = VerifiedMoveJournal(directory)
        check(journal.readActive() == null)
        check(ManualMomentPendingCreateStore(File(context.noBackupFilesDir.canonicalFile, "manual-memory-pending")).read() == null)
        for (name in listOf("collage-publications", "gif-publications", "motion-publications"))
            check(File(context.noBackupFilesDir.canonicalFile, name).walkTopDown().none { it.isFile })
        val settings = com.librestatic.lightforge.core.preferences.GallerySettingsRepository(context)
        val before = settings.settings.first()
        check(!before.security.destructiveActionLockEnabled)
        val grantsBefore = resolver.persistedUriPermissions.map { Triple(it.uri.toString(), it.isReadPermission, it.isWritePermission) }.toSet()
        val sources = mutableListOf<Uri>()
        val hashes = mutableMapOf<Uri, String>()
        fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        suspend fun providerAdmin(operation: String): android.os.Bundle {
            val received = CompletableDeferred<android.os.Bundle>()
            val intent = android.content.Intent("com.librestatic.lightforge.SAF_FIXTURE")
                .setComponent(android.content.ComponentName(instrumentation.context.packageName, "com.librestatic.lightforge.SafCopyFixtureGrantReceiver"))
                .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES or android.content.Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("target", context.packageName).putExtra("operation", operation).putExtra("uuid", uuid)
            context.sendOrderedBroadcast(intent, null, object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    if (resultCode == android.app.Activity.RESULT_OK) received.complete(getResultExtras(false) ?: android.os.Bundle())
                    else received.completeExceptionally(IllegalStateException("Fixture admin failed: $resultCode/$resultData"))
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()), android.app.Activity.RESULT_CANCELED, null, null)
            return withTimeout(10_000) { received.await() }
        }
        var setup = false
        var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null
        val db = GalleryDatabaseFactory.open(context)
        var failure: Throwable? = null
        try {
            val result = providerAdmin("setupPicker")
            check(result.getBoolean("ready")); setup = true
            check(context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            for (index in 0..1) {
                val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri("external_primary"), ContentValues().apply {
                    put("_display_name", "$uuid-$index.png"); put("mime_type", "image/png")
                    put("relative_path", "Pictures/move-picker-$uuid/"); put("is_pending", 1)
                    put("datetaken", System.currentTimeMillis() + index)
                }))
                sources += uri
                val bitmap = Bitmap.createBitmap(48, 40, Bitmap.Config.ARGB_8888)
                try { bitmap.eraseColor(if (index == 0) android.graphics.Color.RED else android.graphics.Color.BLUE)
                    resolver.openOutputStream(uri, "w")!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
                check(resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null) == 1)
                hashes[uri] = hash(uri)
            }
            scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            lateinit var old: MainActivity
            scenario.onActivity { old = it; check(ViewModelProvider(it)[GalleryViewModel::class.java].selectionCount.value == 0L) }
            withTimeout(30_000) { while (sources.any { db.libraryDao().media("external_primary", ContentUris.parseId(it)) == null }) delay(50) }
            val keyA = MediaKey("external_primary", ContentUris.parseId(sources[0]))
            val tagA = "media_${keyA.volumeName}_${keyA.mediaStoreId}"
            compose.onNodeWithTag("timeline_grid").performScrollToNode(hasTestTag(tagA))
            compose.onNodeWithTag(tagA).performTouchInput { click() }
            compose.onNodeWithContentDescription(context.getString(com.librestatic.lightforge.feature.viewer.R.string.viewer_more)).performTouchInput { click() }
            compose.onNodeWithText(context.getString(com.librestatic.lightforge.feature.viewer.R.string.viewer_move_to)).performScrollTo().performTouchInput { click() }
            compose.waitForIdle()
            val device = UiDevice.getInstance(instrumentation)
            val pickerPackage = withTimeout(10_000) {
                var pkg: String? = null
                while (pkg == null) { pkg = device.currentPackageName?.takeIf { it.endsWith("documentsui") }; if (pkg == null) delay(30) }
                pkg
            }
            val originalPid = android.os.Process.myPid()
            var originalTaskId = -1
            lateinit var originalVm: GalleryViewModel
            instrumentation.runOnMainSync {
                originalTaskId = old.taskId
                originalVm = ViewModelProvider(old)[GalleryViewModel::class.java]
                old.recreate()
            }
            var replacement: MainActivity? = null
            withTimeout(15_000) { while (replacement == null) {
                instrumentation.runOnMainSync {
                    replacement = listOf(Stage.CREATED, Stage.STARTED, Stage.STOPPED, Stage.PAUSED).flatMap {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
                    }.filterIsInstance<MainActivity>().firstOrNull { it !== old }
                }
                if (replacement == null) delay(30)
            } }
            check(device.currentPackageName == pickerPackage) { "Recreation brought gallery foreground" }
            val rowB = requireNotNull(db.libraryDao().media("external_primary", ContentUris.parseId(sources[1])))
            instrumentation.runOnMainSync {
                check(old.isDestroyed)
                check(android.os.Process.myPid() == originalPid)
                check(requireNotNull(replacement).taskId == originalTaskId)
                val vm = ViewModelProvider(requireNotNull(replacement))[GalleryViewModel::class.java]
                check(vm === originalVm)
                vm.openTimelineMedia(TimelineMedia(MediaKey(rowB.volumeName, rowB.mediaStoreId), MediaKind.Image,
                    rowB.generationModified, rowB.timelineSortMillis, rowB.width, rowB.height, rowB.durationMillis,
                    displayName = rowB.displayName, sizeBytes = rowB.sizeBytes))
                check(vm.currentMedia.value?.key?.mediaStoreId == rowB.mediaStoreId)
                check(vm.pendingTreeOperation?.target?.key == keyA && vm.pendingTreeOperation?.move == true) { "Pending Move did not retain A" }
            }
            check(device.currentPackageName == pickerPackage)
            device.waitForIdle()
            fun clickFresh(selector: BySelector) {
                repeat(3) {
                    try {
                        val node = device.wait(Until.findObject(selector), 5_000) ?: error("Picker control absent: $selector")
                        node.click()
                        return
                    } catch (_: StaleObjectException) { device.waitForIdle() }
                }
                error("Picker control kept changing: $selector")
            }
            if (!device.hasObject(By.text(rootTitle))) {
                val menu = device.wait(Until.findObject(By.desc(Pattern.compile("Show roots|Open navigation drawer", Pattern.CASE_INSENSITIVE))), 5_000)
                    ?: error("DocumentsUI roots control absent")
                clickFresh(By.desc(Pattern.compile("Show roots|Open navigation drawer", Pattern.CASE_INSENSITIVE)))
            }
            val root = device.wait(Until.findObject(By.text(rootTitle)), 10_000) ?: error("Owned picker root absent")
            clickFresh(By.text(rootTitle))
            val use = device.wait(Until.findObject(By.text(Pattern.compile("USE THIS FOLDER", Pattern.CASE_INSENSITIVE))), 10_000) ?: error("Use folder absent")
            check(device.hasObject(By.text(rootTitle))); use.click()
            val allow = device.wait(Until.findObject(By.res("android", "button1").text(Pattern.compile("Allow", Pattern.CASE_INSENSITIVE))), 5_000) ?: error("Folder Allow absent")
            allow.click()
            compose.waitUntil(20_000) {
                compose.waitForIdle()
                journal.readCopyDraft() == null && runCatching { journal.readActive() }.getOrNull() != null
            }
            compose.waitForIdle()
            instrumentation.runOnMainSync {
                check(ViewModelProvider(requireNotNull(replacement))[GalleryViewModel::class.java].pendingTreeOperation == null)
            }
            val entry = requireNotNull(journal.readActive())
            assertEquals(sources[0].toString(), entry.proof.sourceUri)
            assertEquals(hashes.getValue(sources[0]), hash(Uri.parse(entry.proof.destinationUri)))
            val cancel = device.wait(Until.findObject(By.res("android", "button2")), 10_000) ?: error("Delete Cancel absent")
            check(cancel.applicationPackage in setOf("com.google.android.providers.media.module", "com.android.providers.media.module", "com.android.providers.media"))
            cancel.click()
            withTimeout(10_000) { while (journal.readActive()?.phase != VerifiedMovePhase.Cancelled) delay(50) }
            for (uri in sources) assertEquals(hashes.getValue(uri), hash(uri))
            println("MOVE_PICKER real Move A→DocumentsUI→background Activity recreate→VM selects B→owned folder grant→proof A exact→Android Cancel; no biometric claim")
        } catch (caught: Throwable) {
            println("MOVE_PICKER_DIAGNOSTIC journal=${runCatching { journal.readActive() }}")
            val hierarchy = java.io.ByteArrayOutputStream()
            runCatching { UiDevice.getInstance(instrumentation).dumpWindowHierarchy(hierarchy) }
            println("MOVE_PICKER_HIERARCHY ${hierarchy.toString("UTF-8")}")
            failure = caught
        }
        finally {
            suspend fun step(action: suspend () -> Unit) { try { action() } catch (caught: Throwable) { if (failure == null) failure = caught else failure!!.addSuppressed(caught) } }
            step { scenario?.close() }
            var ownedJournal = false
            step { val active = journal.readActive(); check(active == null || active.proof.sourceUri == sources.firstOrNull()?.toString() && active.proof.treeUri == tree.toString())
                ownedJournal = true; if (active != null) check(journal.forget(active)) }
            if (ownedJournal) {
                for ((index, uri) in sources.withIndex()) step {
                    hashes[uri]?.let { check(hash(uri) == it) }
                    check(resolver.delete(uri, "_display_name=? AND relative_path=? AND owner_package_name=?",
                        arrayOf("$uuid-$index.png", "Pictures/move-picker-$uuid/", context.packageName)) == 1)
                }
                if (setup) step { check(providerAdmin("cleanupPicker").getBoolean("absent")) }
            }
            step { resolver.persistedUriPermissions.singleOrNull { it.uri == tree }?.let { grant ->
                if (grantsBefore.none { it.first == tree.toString() }) {
                    val flags = (if (grant.isReadPermission) android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (grant.isWritePermission) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                    resolver.releasePersistableUriPermission(tree, flags)
                }
            } }
            try { assertEquals(before, settings.settings.first()) } catch (caught: Throwable) { if (failure == null) failure = caught else failure!!.addSuppressed(caught) }
            step { assertEquals(grantsBefore, resolver.persistedUriPermissions.map { Triple(it.uri.toString(), it.isReadPermission, it.isWritePermission) }.toSet()) }
            step { db.close() }
        }
        failure?.let { throw it }
    }
}
