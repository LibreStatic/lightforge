package com.librestatic.lightforge

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.core.editing.image.PhotoImageRenderer
import com.librestatic.lightforge.core.mediastore.MediaStoreReader
import com.librestatic.lightforge.core.mediastore.MediaStoreUriFactory
import com.librestatic.lightforge.core.model.*
import com.librestatic.lightforge.feature.settings.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Real SAF restore and editor interaction; Room/VM observations never invoke workflow callbacks.
 */
class PortablePhotoRecipeBackupAppDeviceTest {
    @Test
    fun restoredPhotoRecipeOpensCancelsAndSavesCopyWithoutLosingEntry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        val owner = "portable-photo-app-${UUID.randomUUID()}"
        val evidence = File(context.filesDir, owner).apply { mkdirs() }
        val archiveName = "$owner.lightforge.zip"
        val archive = File(context.cacheDir, archiveName)
        val ownedUris = mutableListOf<Uri>()
        var restoredForDiagnosis: MediaKey? = null
        var actualPreview: Bitmap? = null
        var expectedPreview: Bitmap? = null
        var expectedExport: Bitmap? = null
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
                try {
                    if (condition()) return
                } catch (_: StaleObjectException) {
                    // A transition invalidated this snapshot; reacquire nodes within the same
                    // deadline.
                }
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
        fun scroll(
            backward: Boolean,
            packageName: String = context.packageName,
            anchorText: String? = null,
        ): Boolean {
            if (anchorText != null) {
                // Reacquire the intended horizontal row, never the larger preview carousel.
                val anchor =
                    nodes().firstOrNull {
                        it.isVisibleToUser &&
                            it.packageName?.toString() == packageName &&
                            it.text?.toString() == anchorText
                    } ?: return false
                var row: AccessibilityNodeInfo? = anchor.parent
                while (row != null && !row.isScrollable) row = row.parent
                return row?.packageName?.toString() == packageName &&
                    row.performAction(
                        if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    )
            }
            val node =
                nodes()
                    .filter {
                        it.isVisibleToUser &&
                            it.isScrollable &&
                            it.packageName?.toString() == packageName
                    }
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
        fun action(
            text: String? = null,
            tag: String? = null,
            description: String? = null,
            packageName: String = context.packageName,
            scrollAnchorText: String? = null,
        ) {
            var backwards = false
            await("action-${tag ?: text ?: description}") {
                val candidates =
                    nodes().filter { node ->
                        node.isVisibleToUser &&
                            node.isEnabled &&
                            node.packageName?.toString() == packageName &&
                            (tag == null || node.viewIdResourceName == tag) &&
                            (description == null ||
                                node.contentDescription?.toString() == description) &&
                            (text == null || node.text?.toString() == text)
                    }
                for (candidate in candidates) {
                    var node: AccessibilityNodeInfo? = candidate
                    var depth = 0
                    while (node != null && !node.isClickable && depth++ < 8) node = node.parent
                    if (
                        node?.isClickable == true &&
                            node.packageName?.toString() == packageName &&
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) {
                        device.waitForIdle()
                        return@await true
                    }
                }
                if (!scroll(backwards, packageName, scrollAnchorText)) backwards = !backwards
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

        fun persistedRecipe(key: MediaKey, generation: Long): EditRecipe? {
            val database = GalleryDatabaseFactory.open(context)
            try {
                return runBlocking {
                    database.editRecipeDao().load(EditRecipeIds.forSource(key, generation))
                }
            } finally {
                database.close()
            }
        }
        fun operationFor(snapshotId: String): String? {
            val database = GalleryDatabaseFactory.open(context)
            try {
                return database.openHelper.readableDatabase
                    .query(
                        "SELECT operationId FROM gallery_restore_receipts WHERE snapshotId=?",
                        arrayOf(snapshotId),
                    )
                    .use {
                        if (!it.moveToFirst()) null
                        else it.getString(0).also { _ -> check(!it.moveToNext()) }
                    }
            } finally {
                database.close()
            }
        }
        fun pixels(bitmap: Bitmap): IntArray =
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        fun compare(expected: Bitmap, actual: Bitmap) {
            assertEquals(expected.width, actual.width)
            assertEquals(expected.height, actual.height)
            assertArrayEquals(pixels(expected), pixels(actual))
        }
        try {
            val sourceId = UUID.randomUUID().toString()
            val snapshotId = UUID.randomUUID().toString()
            val sourceName = "$owner.png"
            val image =
                Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
                    for (y in 0 until height) for (x in 0 until width) setPixel(
                        x,
                        y,
                        android.graphics.Color.rgb(x * 4, y * 5, ((x + y) % 32) * 8),
                    )
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
            val operations =
                listOf(
                    EditOperation.Crop(250, 0, 750, 1000),
                    EditOperation.Rotate(90),
                    EditOperation.Tone(.2f, 1.2f, .7f),
                    EditOperation.Filter("vivid"),
                )
            val encoded = operations.map(EditOperationCodec::encode)
            val entry =
                BackupManifest.Entry(
                    BackupManifest.path(0),
                    sourceName,
                    "image/png",
                    original.size.toLong(),
                    hash(original),
                    sourceId,
                )
            val snapshot =
                PortableOrganizationSnapshot(
                        schemaVersion = 2,
                        snapshotId = snapshotId,
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
                                    System.currentTimeMillis() + 60000,
                                    null,
                                    false,
                                    null,
                                )
                            ),
                        photoRecipes = listOf(PortablePhotoRecipe(sourceId, 8, 10, 20, encoded)),
                    )
                    .validate()
            val sidecar = PortableOrganizationCodec.encode(snapshot)
            val manifest =
                BackupManifest(
                    listOf(entry),
                    BackupManifest.Organization(2, sidecar.size.toLong(), hash(sidecar)),
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
            val archiveUri =
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
                    .also(ownedUris::add)
            resolver.openOutputStream(archiveUri, "w")!!.use { out ->
                archive.inputStream().use { it.copyTo(out) }
            }
            assertEquals(
                1,
                resolver.update(
                    archiveUri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ),
            )
            val intent =
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                var viewModel: GalleryViewModel? = null
                scenario.onActivity {
                    viewModel = ViewModelProvider(it)[GalleryViewModel::class.java]
                }
                val vm = checkNotNull(viewModel)
                try {
                    action(description = label(com.librestatic.lightforge.feature.photos.R.string.open_settings))
                    textAction(com.librestatic.lightforge.feature.settings.R.string.settings_backup)
                    textAction(com.librestatic.lightforge.feature.settings.R.string.local_backup_title)
                    textAction(com.librestatic.lightforge.feature.settings.R.string.local_backup_open)
                    await("documents-open") {
                        instrumentation.uiAutomation.rootInActiveWindow
                            ?.packageName
                            ?.toString()
                            ?.endsWith(".documentsui") == true
                    }
                    val documentPackage =
                        checkNotNull(
                            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                        )
                    fun documentVisible(
                        text: String? = null,
                        description: String? = null,
                    ): Boolean =
                        nodes().any {
                            it.isVisibleToUser &&
                                it.packageName?.toString() == documentPackage &&
                                (text == null ||
                                    (it.viewIdResourceName == "android:id/title" &&
                                        it.text?.toString() == text)) &&
                                (description == null ||
                                    it.contentDescription?.toString() == description)
                        }
                    // Every action obtains fresh native nodes. A successful click is never
                    // repeated;
                    // its destination state is awaited separately before navigating further.
                    action(description = "Show roots", packageName = documentPackage)
                    await("document-roots") { documentVisible(text = "Downloads") }
                    action(
                        text = "Downloads",
                        tag = "android:id/title",
                        packageName = documentPackage,
                    )
                    await("downloads-root-selected") { documentVisible(description = "Show roots") }
                    action(text = owner, tag = "android:id/title", packageName = documentPackage)
                    await("owned-document-folder") { documentVisible(text = archiveName) }
                    action(
                        text = archiveName,
                        tag = "android:id/title",
                        packageName = documentPackage,
                    )
                    await("documents-returned-to-app") {
                        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() ==
                            context.packageName
                    }
                    reveal(By.text(archiveName), "archive-review")
                    capture("archive-review")
                    textAction(com.librestatic.lightforge.feature.settings.R.string.local_backup_gallery_restore)
                    await("confirm-restore") { visible(By.res("local-backup-gallery-confirm")) }
                    action(tag = "local-backup-gallery-confirm")
                    val store = LocalBackupTaskStore(context)
                    var task: LocalBackupTask? = null
                    await("owned-task-complete", 90000) {
                        task = store.list().singleOrNull { it.name == archiveName }
                        task?.status == LocalBackupTaskStatus.Completed
                    }
                    val taskId = checkNotNull(task).id
                    val operation = checkNotNull(operationFor(snapshotId))
                    val restored =
                        resolver
                            .query(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                arrayOf(MediaStore.MediaColumns._ID),
                                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
                                arrayOf(
                                    sourceName,
                                    "Pictures/Lightforge Restore/$operation/",
                                    context.packageName,
                                ),
                                null,
                            )!!
                            .use {
                                check(it.moveToFirst())
                                MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, it.getLong(0)).also { _
                                    ->
                                    check(!it.moveToNext())
                                }
                            }
                    restoredForDiagnosis = restored
                    val restoredUri = MediaStoreUriFactory.uriFor(restored).also(ownedUris::add)
                    assertEquals(
                        entry.sha256,
                        resolver.openInputStream(restoredUri)!!.use { hash(it.readBytes()) },
                    )
                    val record = runBlocking { MediaStoreReader(resolver).readOne(restored) }!!
                    val restoredRecipe =
                        checkNotNull(persistedRecipe(restored, record.generationModified))
                    assertEquals(operations, restoredRecipe.operations)
                    assertEquals(8, restoredRecipe.revision)
                    assertEquals(restored, restoredRecipe.source)
                    textAction(com.librestatic.lightforge.feature.settings.R.string.local_backup_tasks_title)
                    reveal(By.text(archiveName), "owned-task-visible")
                    capture("restored-task")
                    textAction(com.librestatic.lightforge.feature.settings.R.string.local_backup_back)
                    // Both routes expose the same Back label: wait until the Tasks tree is
                    // replaced.
                    await("returned-to-local-backup") {
                        visible(
                            By.text(
                                label(com.librestatic.lightforge.feature.settings.R.string.local_backup_title)
                            )
                        )
                    }
                    textAction(com.librestatic.lightforge.feature.settings.R.string.local_backup_back)
                    // Settings is now the verified parent; system Back has no text/IME ambiguity.
                    await("settings-parent") {
                        visible(
                            By.text(label(com.librestatic.lightforge.feature.settings.R.string.settings_title))
                        )
                    }
                    device.pressBack()
                    device.waitForIdle()
                    val photoTag = "media_${restored.volumeName}_${restored.mediaStoreId}"
                    action(tag = photoTag)
                    textAction(com.librestatic.lightforge.feature.viewer.R.string.viewer_edit)
                    await("restored-editor-rendered", 60000) {
                        val session = vm.photoEditor.value
                        session?.source?.libraryMedia?.key == restored &&
                            !session.content.isRendering &&
                            session.content.preview != null
                    }
                    await("editor-surface-visible") {
                        visible(
                            By.text(
                                label(com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_title)
                            )
                        ) &&
                            visible(
                                By.desc(
                                    label(
                                        com.librestatic.lightforge.feature.photoeditor.R.string
                                            .photo_editor_cancel
                                    )
                                )
                            )
                    }
                    val session = checkNotNull(vm.photoEditor.value)
                    assertEquals(restoredRecipe, session.history.present)
                    assertEquals("vivid", session.content.selectedFilter)
                    assertEquals(
                        operations.filterIsInstance<EditOperation.Tone>().single(),
                        session.content.tone,
                    )
                    assertEquals(
                        operations.filterIsInstance<EditOperation.Crop>().single(),
                        session.content.crop,
                    )
                    scenario.onActivity {
                        actualPreview =
                            checkNotNull(vm.photoEditor.value?.content?.preview)
                                .copy(Bitmap.Config.ARGB_8888, false)
                    }
                    expectedPreview = runBlocking {
                        PhotoImageRenderer(resolver)
                            .renderDecoded(
                                BitmapFactory.decodeByteArray(original, 0, original.size),
                                restoredRecipe.copy(operations = operations.take(2)),
                            )
                    }
                    compare(checkNotNull(expectedPreview), checkNotNull(actualPreview))
                    capture("restored-editor")
                    action(
                        text =
                            label(com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_filters),
                        scrollAnchorText =
                            label(com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_crop),
                    )
                    await("filter-options-visible") {
                        visible(
                            By.text(
                                label(
                                    com.librestatic.lightforge.feature.photoeditor.R.string
                                        .photo_editor_filter_natural
                                )
                            )
                        )
                    }
                    action(
                        text =
                            label(
                                com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_filter_mono
                            ),
                        scrollAnchorText =
                            label(
                                com.librestatic.lightforge.feature.photoeditor.R.string
                                    .photo_editor_filter_natural
                            ),
                    )
                    await("draft-mono") { vm.photoEditor.value?.content?.selectedFilter == "mono" }
                    action(
                        description =
                            label(com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_cancel)
                    )
                    await("discard-dialog-visible") {
                        visible(By.text(label(R.string.editor_discard_title)))
                    }
                    textAction(R.string.editor_discard_confirm)
                    await("editor-cancelled") { vm.photoEditor.value == null }
                    textAction(com.librestatic.lightforge.feature.viewer.R.string.viewer_edit)
                    await("entry-restored-after-cancel", 60000) {
                        vm.photoEditor.value?.history?.present == restoredRecipe &&
                            vm.photoEditor.value?.content?.isRendering == false
                    }
                    assertEquals(
                        restoredRecipe,
                        persistedRecipe(restored, record.generationModified),
                    )
                    await("reopened-editor-surface-visible") {
                        visible(
                            By.text(
                                label(com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_title)
                            )
                        ) &&
                            visible(
                                By.desc(
                                    label(
                                        com.librestatic.lightforge.feature.photoeditor.R.string
                                            .photo_editor_cancel
                                    )
                                )
                            )
                    }
                    capture("reopened-entry")
                    expectedExport = runBlocking {
                        PhotoImageRenderer(resolver)
                            .renderDecoded(
                                BitmapFactory.decodeByteArray(original, 0, original.size),
                                restoredRecipe,
                            )
                    }
                    textAction(com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_save_copy)
                    await("edited-copy-opened", 60000) {
                        vm.photoEditor.value == null &&
                            vm.currentMedia.value?.key != null &&
                            vm.currentMedia.value?.key != restored
                    }
                    val saved = checkNotNull(vm.currentMedia.value)
                    val savedUri = MediaStoreUriFactory.uriFor(saved.key).also(ownedUris::add)
                    val savedBitmap =
                        resolver.openInputStream(savedUri)!!.use(BitmapFactory::decodeStream)
                    try {
                        compare(checkNotNull(expectedExport), checkNotNull(savedBitmap))
                    } finally {
                        savedBitmap?.recycle()
                    }
                    assertEquals(
                        restoredRecipe,
                        persistedRecipe(restored, record.generationModified),
                    )
                    assertEquals(
                        entry.sha256,
                        resolver.openInputStream(restoredUri)!!.use { hash(it.readBytes()) },
                    )
                    assertEquals(
                        archiveHash,
                        resolver.openInputStream(archiveUri)!!.use { hash(it.readBytes()) },
                    )
                    capture("saved-copy-entry-preserved")
                    File(evidence, "result.json")
                        .writeText(
                            JSONObject()
                                .put("status", "PASS")
                                .put("archiveSha256", archiveHash)
                                .put("sourceSha256", entry.sha256)
                                .put("realDocumentsUi", true)
                                .put("sourceId", sourceId)
                                .put("restoreOperationId", operation)
                                .put("taskId", taskId)
                                .put("restoredKey", restored.toString())
                                .put("savedCopyKey", saved.key.toString())
                                .put("previewPixelsMatch", true)
                                .put("savedCopyPixelsMatch", true)
                                .put("cancelRestoresEntry", true)
                                .put("saveCopyPreservesEntry", true)
                                .put("recipeRevision", 8)
                                .toString(2)
                        )
                } catch (failure: Throwable) {
                    // Diagnose eligibility BEFORE owned provider cleanup or ActivityScenario.close.
                    val diagnostic =
                        JSONObject()
                            .put("capturedAtMillis", System.currentTimeMillis())
                            .put("engineState", vm.engineState.value.toString())
                            .put("access", vm.access.value.toString())
                            .put("librarySettings", vm.gallerySettings.value.library.toString())
                            .put("ownedProviderUrisBeforeCleanup", ownedUris.map(Uri::toString))
                    restoredForDiagnosis?.let { key ->
                        diagnostic.put("restoredKey", key.toString())
                        try {
                            diagnostic.put(
                                "providerRecord",
                                runBlocking { MediaStoreReader(resolver).readOne(key) }?.toString()
                                    ?: JSONObject.NULL,
                            )
                        } catch (providerFailure: Exception) {
                            diagnostic.put("providerFailure", providerFailure.stackTraceToString())
                        }
                        val database = GalleryDatabaseFactory.open(context)
                        try {
                            val sql = database.openHelper.readableDatabase
                            fun rows(query: String, args: Array<Any?>): org.json.JSONArray {
                                return sql.query(query, args).use { cursor ->
                                    org.json.JSONArray().also { array ->
                                        while (cursor.moveToNext()) {
                                            val row = JSONObject()
                                            cursor.columnNames.forEachIndexed { index, name ->
                                                row.put(
                                                    name,
                                                    if (cursor.isNull(index)) JSONObject.NULL
                                                    else cursor.getString(index),
                                                )
                                            }
                                            array.put(row)
                                        }
                                    }
                                }
                            }
                            val args = arrayOf<Any?>(key.volumeName, key.mediaStoreId)
                            for (table in
                                listOf(
                                    "media_items",
                                    "archived_media",
                                    "portable_timeline_overrides",
                                )) {
                                diagnostic.put(
                                    table,
                                    rows(
                                        "SELECT * FROM $table WHERE volumeName=? AND mediaStoreId=?",
                                        args,
                                    ),
                                )
                            }
                            diagnostic.put(
                                "checkpoint",
                                rows(
                                    "SELECT * FROM media_store_checkpoints WHERE volumeName=?",
                                    arrayOf(key.volumeName),
                                ),
                            )
                            diagnostic.put(
                                "eligibleCount",
                                rows(
                                    "SELECT COUNT(*) AS count FROM media_items m WHERE isAccessible=1 AND isTrashed=0 AND NOT EXISTS (SELECT 1 FROM archived_media a WHERE a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)",
                                    emptyArray(),
                                ),
                            )
                            diagnostic.put(
                                "recipe",
                                runBlocking {
                                        val record =
                                            database
                                                .libraryDao()
                                                .media(key.volumeName, key.mediaStoreId)
                                        record?.let {
                                            database
                                                .editRecipeDao()
                                                .load(
                                                    EditRecipeIds.forSource(
                                                        key,
                                                        it.generationModified,
                                                    )
                                                )
                                        }
                                    }
                                    ?.toString() ?: JSONObject.NULL,
                            )
                        } catch (databaseFailure: Exception) {
                            diagnostic.put("databaseFailure", databaseFailure.stackTraceToString())
                        } finally {
                            database.close()
                        }
                    }
                    File(evidence, "eligibility-before-cleanup.json")
                        .writeText(diagnostic.toString(2))
                    capture("failure-before-close")
                    File(evidence, "failure.txt").writeText(failure.stackTraceToString())
                    throw failure
                }
            }
        } finally {
            actualPreview?.recycle()
            expectedPreview?.recycle()
            expectedExport?.recycle()
            ownedUris.forEach { resolver.delete(it, null, null) }
            archive.delete()
            // Exact completed task and import receipt remain truthful history, not replay grants
            // imported from elsewhere. No Room rows, settings or other files are reset.
            File(evidence, "cleanup.json")
                .writeText(
                    JSONObject()
                        .put("onlyOwnedUris", ownedUris.map(Uri::toString))
                        .put("settingsReset", false)
                        .toString(2)
                )
        }
    }
}
