package com.ugallery.feature.settings

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real controller, SAF bytes and restore; the four system-picker responses alone are injected. */
class LocalBackupWorkflowDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectedFilesCreateReviewAndConfirmedRestoreThroughNativeScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val authority = "com.ugallery.feature.settings.test.localbackup"
        val base = Uri.parse("content://$authority")
        val root = DocumentsContract.buildDocumentUri(authority, "root")
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        resolver.call(base, "fixture-reset", null, null)
        fun create(name: String, bytes: ByteArray): Uri {
            val uri =
                requireNotNull(
                    DocumentsContract.createDocument(
                        resolver,
                        root,
                        "application/octet-stream",
                        name,
                    )
                )
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            return uri
        }
        fun read(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        fun children(parent: Uri): List<Uri> {
            val uri =
                DocumentsContract.buildChildDocumentsUri(
                    authority,
                    DocumentsContract.getDocumentId(parent),
                )
            return requireNotNull(
                    resolver.query(
                        uri,
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                        null,
                        null,
                        null,
                    )
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            DocumentsContract.buildDocumentUri(authority, c.getString(0))
                        )
                    }
                }
        }
        try {
            val firstBytes = ByteArray(400_000) { (it % 251).toByte() }
            val first = create("native-photo.jpg", firstBytes)
            val second = create("native-document.pdf", byteArrayOf(1, 2, 3))
            val backup = create("native-backup.ugallery.zip", byteArrayOf())
            val results = ArrayDeque<Intent>()
            var launches = 0
            val registry =
                object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<I, O>,
                        input: I,
                        options: ActivityOptionsCompat?,
                    ) {
                        assertTrue(
                            contract
                                .createIntent(context, input)
                                .getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                        )
                        launches++
                        dispatchResult(requestCode, Activity.RESULT_OK, results.removeFirst())
                    }
                }
            val owner =
                object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }
            compose.setContent {
                UGalleryTheme {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        LocalBackupContent {}
                    }
                }
            }
            val selection =
                ClipData.newUri(resolver, "Originals", first).apply {
                    addItem(ClipData.Item(second))
                }
            results += Intent().apply { clipData = selection }
            awaitEnabled("local-backup-select")
            compose.onNodeWithTag("local-backup-select").performScrollTo().performClick()
            awaitEnabled("local-backup-create")
            compose.onNodeWithTag("local-backup-create").performScrollTo().assertIsEnabled()
            results += Intent().setData(backup)
            compose.onNodeWithTag("local-backup-create").performClick()
            awaitText(context.getString(R.string.local_backup_exported))
            compose
                .onNodeWithText(context.getString(R.string.local_backup_summary, 2, 400003L))
                .performScrollTo()
                .assertIsDisplayed()
            assertTrue(read(backup).isNotEmpty())
            results += Intent().setData(backup)
            compose.onNodeWithTag("local-backup-open").performScrollTo().performClick()
            awaitText(context.getString(R.string.local_backup_verified))
            compose.onNodeWithTag("local-backup-restore").performScrollTo().performClick()
            results += Intent().setData(tree)
            compose
                .onNodeWithText(context.getString(R.string.local_backup_choose_folder))
                .performClick()
            awaitText(context.getString(R.string.local_backup_restored))
            val folder =
                children(root).single {
                    DocumentsContract.getDocumentId(it).contains("UGallery-restored-")
                }
            val copies = children(folder).map(::read)
            assertEquals(2, copies.size)
            assertTrue(copies.any { it.contentEquals(firstBytes) })
            assertTrue(copies.any { it.contentEquals(byteArrayOf(1, 2, 3)) })
            assertArrayEquals(firstBytes, read(first))
            assertArrayEquals(byteArrayOf(1, 2, 3), read(second))
            assertEquals(4, launches)
            val screenshot =
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (screenshot != null) {
                java.io.File(context.filesDir, "local-backup-workflow.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        } finally {
            resolver.call(base, "fixture-reset", null, null)
        }
    }

    @Test
    fun leavingDuringBlockedSourceReadCancelsAndJoinsBeforeCleaningOwnedSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val authority = "com.ugallery.feature.settings.test.localbackup"
        val base = Uri.parse("content://$authority")
        val root = DocumentsContract.buildDocumentUri(authority, "root")
        resolver.call(base, "fixture-reset", null, null)
        val source =
            requireNotNull(
                DocumentsContract.createDocument(resolver, root, "image/jpeg", "slow.jpg")
            )
        resolver.openOutputStream(source)!!.use { it.write(byteArrayOf(1, 2, 3)) }
        val destination =
            requireNotNull(
                DocumentsContract.createDocument(
                    resolver,
                    root,
                    "application/zip",
                    "interrupted.zip",
                )
            )
        val cacheRoot = java.io.File(context.cacheDir, "local-backup")
        val previous = cacheRoot.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val results = ArrayDeque<Intent>()
        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    dispatchResult(requestCode, Activity.RESULT_OK, results.removeFirst())
                }
            }
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        var showing by mutableStateOf(true)
        try {
            compose.setContent {
                UGalleryTheme {
                    if (showing)
                        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                            LocalBackupContent {}
                        }
                }
            }
            awaitEnabled("local-backup-select")
            results += Intent().setData(source)
            compose.onNodeWithTag("local-backup-select").performScrollTo().performClick()
            awaitEnabled("local-backup-create")
            val session = cacheRoot.listFiles()!!.single { it.name !in previous }
            resolver.call(base, "fixture-hold-source", null, null)
            results += Intent().setData(destination)
            compose.onNodeWithTag("local-backup-create").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                resolver.call(base, "fixture-source-state", null, null)?.getBoolean("opened") ==
                    true
            }
            compose.runOnIdle { showing = false }
            compose.waitForIdle()
            assertTrue("Staging must stay until its cancelled IO finishes", session.exists())
            resolver.call(base, "fixture-release-source", null, null)
            compose.waitUntil(10_000) { !session.exists() }
            val children = DocumentsContract.buildChildDocumentsUri(authority, "root")
            val ids =
                resolver
                    .query(
                        children,
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                        null,
                        null,
                        null,
                    )!!
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            assertEquals(listOf(DocumentsContract.getDocumentId(source)), ids)
            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                resolver.openInputStream(source)!!.use { it.readBytes() },
            )
        } finally {
            resolver.call(base, "fixture-release-source", null, null)
            resolver.call(base, "fixture-reset", null, null)
        }
    }

    private fun awaitEnabled(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            try {
                compose.onNodeWithTag(tag).assertIsEnabled()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
}
