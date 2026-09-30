package com.librestatic.lightforge.feature.privatealbum

import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.room.Room
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.security.PrivateAlbumCrypto
import com.librestatic.lightforge.core.security.PrivatePortableArchive
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.util.UUID
import java.util.regex.Pattern

/** Vault starts unlocked in this fixture. Actual Android DocumentsUI/grants, crypto, Room and readback are exercised; BiometricPrompt is not. */
@RunWith(AndroidJUnit4::class)
class PrivatePortableSafWorkflowDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val evidence = File(context.filesDir, "private-ui-" + UUID.randomUUID()).apply { mkdirs() }
    @Test fun unlockedVaultExportsAndRestoresThroughRealDocumentsUiWithVerifiedPrivateCopies() {
        val db = Room.inMemoryDatabaseBuilder(context, PrivateAlbumDatabase::class.java).build()
        val repository = PrivateAlbumRepository(context, db)
        val key = PrivateAlbumCrypto.getOrCreateMasterKey()
        val original = File(context.cacheDir, "private-ui-original-" + UUID.randomUUID() + ".jpg")
        val name = "private-ui-" + UUID.randomUUID() + ".ugpb"
        var saved: Uri? = null
        var initialId = 0L
        try {
            val bitmap = Bitmap.createBitmap(53, 47, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(33, 154, 208))
            try { FileOutputStream(original).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) } } finally { bitmap.recycle() }
            val originalSha = PrivatePortableArchive.hash(original)
            runBlocking {
                repository.setup(key)
                initialId = requireNotNull(repository.importFromUri(Uri.fromFile(original), "owned-private-ui.jpg", "image/jpeg", "image", 53, 47, masterKey = key).mediaId)
            }
            compose.setContent { LightforgeTheme(darkTheme = false) {
                PrivateAlbumContent(repository, onBack = {}, onUnlockRequest = { _, _ -> error("Unexpected biometric request") }, onExport = { _, _, _ -> error("Unexpected public media export") }, isUnlocked = true, onUnlocked = {}, onAddRequest = {})
            } }
            click("private-portable-open")
            click("private-portable-export")
            fillPassword(true)
            click("private-portable-prepare")
            // Pump the Compose test clock while preparation resumes its UI coroutine.
            compose.waitUntil(60_000) { device.hasObject(By.pkg("com.google.android.documentsui")) }
            assertTrue("CreateDocument must launch actual DocumentsUI", device.hasObject(By.pkg("com.google.android.documentsui")))
            downloads()
            val edit = By.clazz("android.widget.EditText")
            assertTrue(device.wait(Until.hasObject(edit), 10_000))
            reacquire(edit) { it.text = name }
            dismissIme()
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText").text(name)), 10_000))
            val save = By.res("android:id/button1").pkg("com.google.android.documentsui").text(Pattern.compile("Save", Pattern.CASE_INSENSITIVE)).enabled(true)
            assertTrue(device.wait(Until.hasObject(save), 10_000))
            device.waitForIdle()
            clickAccessible { it.viewIdResourceName == "android:id/button1" && it.text?.toString()?.equals("Save", ignoreCase = true) == true }
            awaitTag("private-portable-saved", 60_000)
            saved = context.contentResolver.persistedUriPermissions.map { it.uri }.single { uri ->
                runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() && it.getString(0) == name } == true }.getOrDefault(false)
            }
            val archiveBytes = requireNotNull(context.contentResolver.openInputStream(requireNotNull(saved))).use { it.readBytes() }
            assertTrue(archiveBytes.copyOfRange(0, 5).contentEquals(byteArrayOf(85, 71, 80, 66, 1)))
            assertFalse(String(archiveBytes, Charsets.ISO_8859_1).contains("owned-private-ui.jpg"))
            click("private-portable-close")
            click("private-portable-open")
            click("private-portable-import")
            assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10_000))
            downloads()
            val document = By.res("android:id/title").text(name)
                .hasAncestor(By.res("com.google.android.documentsui", "dir_list"))
            val search = By.res("com.google.android.documentsui", "option_menu_search")
            if (!device.hasObject(document)) {
                assertTrue(device.wait(Until.hasObject(search.enabled(true)), 10_000))
                clickAccessible { it.viewIdResourceName == "com.google.android.documentsui:id/option_menu_search" }
                val searchInput = By.res("com.google.android.documentsui", "search_src_text")
                assertTrue(device.wait(Until.hasObject(searchInput), 10_000))
                clickAccessible { it.viewIdResourceName == "com.google.android.documentsui:id/search_src_text" }
                assertTrue(device.wait(Until.hasObject(By.res("com.google.android.documentsui", "search_src_text").focused(true)), 10_000))
                reacquire(searchInput) { it.text = name }
                val searchAction = By.res("com.google.android.inputmethod.latin", "key_pos_ime_action")
                if (device.wait(Until.hasObject(searchAction), 3000)) clickAccessible("com.google.android.inputmethod.latin") {
                    it.viewIdResourceName == "com.google.android.inputmethod.latin:id/key_pos_ime_action"
                } else device.pressEnter()
            }
            // A matching query/history label is not a file: scope to the actual directory list.
            assertTrue("Owned encrypted backup must be visible in real picker results", device.wait(Until.hasObject(document), 15_000))
            dismissIme()
            device.waitForIdle()
            clickAccessible(directoryOnly = true) { it.viewIdResourceName == "android:id/title" && it.text?.toString() == name }
            compose.waitUntil(15_000) { device.hasObject(By.pkg(context.packageName)) }
            awaitTag("private-portable-password")
            fillPassword(false)
            click("private-portable-prepare")
            awaitTag("private-portable-review", 60_000)
            assertEquals("No publication before review", 1, runBlocking { repository.count() })
            click("private-portable-commit")
            awaitTag("private-portable-restored", 30_000)
            val rows = runBlocking { db.privateMediaDao().getPage(3, 0) }
            assertEquals(2, rows.size)
            val restored = rows.single { it.id != initialId }
            val dataKey = PrivateAlbumCrypto.decryptDataKey(PrivateAlbumCrypto.EncryptedDataKey(restored.encryptedDataKey, restored.dataKeyIv), key)
            val plaintext = ByteArrayOutputStream()
            FileInputStream(restored.containerPath).use { PrivateAlbumCrypto.decryptStream(it, plaintext, dataKey, restored.sha256) }
            assertArrayEquals(original.readBytes(), plaintext.toByteArray())
            assertArrayEquals(originalSha, PrivatePortableArchive.hash(original))
            assertNotNull(runBlocking { db.portableRestoreDao().getReceiptByArchiveSha(java.security.MessageDigest.getInstance("SHA-256").digest(archiveBytes).hex()) })
            File(evidence, "result.json").writeText(JSONObject().put("status", "PASS").put("biometricPromptExercised", false).put("picker", "Android DocumentsUI real SAF grants").put("archiveSha256", java.security.MessageDigest.getInstance("SHA-256").digest(archiveBytes).hex()).put("originalSha256", originalSha.hex()).put("restoredPlaintextSha256", restored.sha256.hex()).put("privateRows", rows.size).toString(2))
            click("private-portable-close")
        } catch (failure: Throwable) {
            runCatching { device.dumpWindowHierarchy(File(evidence, "failure.xml")) }
            throw failure
        } finally {
            saved?.let { uri ->
                assertTrue("Owned encrypted output cleanup", DocumentsContract.deleteDocument(context.contentResolver, uri))
                runCatching { context.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            runBlocking { db.privateMediaDao().getPage(10, 0) }.forEach { File(it.containerPath).delete() }
            original.delete(); db.close()
        }
    }
    private fun fillPassword(confirm: Boolean) {
        compose.onNodeWithTag("private-portable-password").performScrollTo().performTextInput("Real SAF private password")
        if (confirm) compose.onNodeWithTag("private-portable-confirm-password").performScrollTo().performTextInput("Real SAF private password")
        // Real IME dismissal avoids injecting a tap through a keyboard-obscured dialog.
        dismissIme()
        compose.waitForIdle()
    }
    private fun dismissIme() {
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) {
            device.pressBack()
            assertTrue("IME must be hidden before submitting", device.wait(Until.gone(By.pkg("com.google.android.inputmethod.latin")), 10_000))
        }
    }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag !in setOf("private-portable-open", "private-portable-close")) node.performScrollTo()
        node.assertIsEnabled().performClick()
        if (tag == "private-portable-prepare") compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("private-portable-password").fetchSemanticsNodes()
                .all { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text.orEmpty().isEmpty() }
        }
    }
    private fun awaitTag(tag: String, timeout: Long = 15_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }
    private fun downloads() {
        val breadcrumb = By.res("com.google.android.documentsui", "breadcrumb_text").text("Downloads")
        val directory = By.res("com.google.android.documentsui", "dir_list")
        if (device.hasObject(breadcrumb) && device.hasObject(directory)) return
        val roots = By.desc(Pattern.compile("Show roots|Navigate up"))
        assertTrue(device.wait(Until.hasObject(roots), 10_000))
        clickAccessible { it.contentDescription?.toString() in setOf("Show roots", "Navigate up") }
        val downloads = By.res("android:id/title").text("Downloads")
        assertTrue(device.wait(Until.hasObject(downloads), 10_000))
        clickAccessible { it.viewIdResourceName == "android:id/title" && it.text?.toString() == "Downloads" }
        assertTrue(device.wait(Until.hasObject(breadcrumb), 10_000))
        assertTrue(device.wait(Until.hasObject(directory), 10_000))
    }
    /** One real accessibility action on a newly acquired exact node; never retries a successful click. */
    private fun clickAccessible(
        packageName: String = "com.google.android.documentsui",
        directoryOnly: Boolean = false,
        matches: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean,
    ) {
        val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val roots = automation.windows.mapNotNull { it.root }.ifEmpty { listOf(requireNotNull(automation.rootInActiveWindow)) }
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
            nodes += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        fun inDirectory(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            var parent = node.parent
            while (parent != null) {
                if (parent.viewIdResourceName == "com.google.android.documentsui:id/dir_list") return true
                parent = parent.parent
            }
            return false
        }
        try {
            roots.forEach(::visit)
            var target = nodes.single { it.packageName?.toString() == packageName && it.isVisibleToUser && it.isEnabled && matches(it) && (!directoryOnly || inDirectory(it)) }
            while (!target.isClickable) {
                target = requireNotNull(target.parent) { "Matching control has no clickable parent" }
                require(target.packageName?.toString() == packageName)
                if (directoryOnly) require(target.viewIdResourceName != "com.google.android.documentsui:id/dir_list") { "Never click the whole file list" }
            }
            assertTrue("Exact fresh accessibility click", target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        } finally { nodes.forEach { it.recycle() } }
    }
    private fun reacquire(selector: BySelector, action: (UiObject2) -> Unit) {
        repeat(3) { attempt ->
            try { action(requireNotNull(device.findObject(selector))); return }
            catch (stale: StaleObjectException) { if (attempt == 2) throw stale }
        }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
