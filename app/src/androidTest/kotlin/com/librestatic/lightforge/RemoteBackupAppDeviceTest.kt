package com.librestatic.lightforge

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.remotestorage.*
import com.librestatic.lightforge.core.remotestorage.sftp.SftpRemoteConnectionFactory
import com.librestatic.lightforge.feature.settings.LocalBackupArchive
import com.librestatic.lightforge.feature.settings.LocalBackupTaskStatus
import com.librestatic.lightforge.feature.settings.LocalBackupTaskStore
import com.librestatic.lightforge.feature.remotebackup.R as RemoteR
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.Security
import java.util.UUID
import java.util.regex.Pattern
import java.util.zip.ZipFile

/** Full app workflow, real DocumentsUI grants, real own-loopback SFTP, and local restore copies. */
class RemoteBackupAppDeviceTest {
    private fun fixtureProfile(name: String) = RemoteProfile(name = name, protocol = RemoteProtocol.SFTP,
        host = "10.0.2.2", port = 22234, username = "lightforgefixture", root = "/data/archive",
        trustedHostKey = requireNotNull(InstrumentationRegistry.getArguments().getString("sftpPin")))
    private fun fixtureGuard() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.librestatic.lightforge.pdfacceptance")
        check(InstrumentationRegistry.getArguments().getString("sftpFixture") == "lightforge-wave34")
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun settingsTrustUploadDownloadAndSafFolderRestoreAreVerifiedEndToEnd() {
        fixtureGuard()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        val name = "lightforge-remote-app-${UUID.randomUUID()}"
        val fileName = "$name.png"
        val evidence = File(context.filesDir, name).apply { mkdirs() }
        val remoteRoot = File(context.filesDir, "remote-backup")
        var source: Uri? = null
        fun capture(label: String) {
            device.takeScreenshot(File(evidence, "$label.png"))
            device.dumpWindowHierarchy(File(evidence, "$label.xml"))
        }
        fun find(selector: BySelector, timeout: Long = 20_000): UiObject2 {
            val until = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < until) {
                try { device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it } }
                catch (_: StaleObjectException) { }
                try {
                    val scroll = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.width().toLong() * it.visibleBounds.height() }
                    // Calls advance in reading order; a missing event never reverses direction.
                    scroll?.scroll(Direction.DOWN, .55f)
                } catch (_: StaleObjectException) { /* Reacquire the current hierarchy next pass. */ }
                device.waitForIdle()
            }
            capture("missing-control-before-scenario-close")
            File(evidence, "missing-control-selector.txt").writeText(selector.toString())
            error("Missing remote-backup control $selector")
        }
        fun click(selector: BySelector) {
            var clicked = false
            repeat(3) {
                if (!clicked) {
                    try { find(selector.enabled(true)).click(); clicked = true }
                    catch (stale: StaleObjectException) {
                        if (it == 2) {
                            capture("stale-control-before-scenario-close")
                            throw stale
                        }
                        device.waitForIdle()
                    }
                }
            }
            // Never repeat a successful click if the following idle observation changes.
            device.waitForIdle()
        }
        fun label(id: Int) = By.text(context.getString(id))
        fun awaitRemoteRoute() {
            check(device.wait(Until.hasObject(By.res("remote-backup-screen")), 20_000)) {
                "Remote backup route did not become visible"
            }
        }
        fun revealRemoteHeader(selector: BySelector) {
            awaitRemoteRoute()
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!device.hasObject(selector) && SystemClock.elapsedRealtime() < deadline) {
                try {
                    val screen = device.findObject(By.res("remote-backup-screen"))
                    requireNotNull(screen) { "Remote backup route disappeared" }
                    screen.scroll(Direction.UP, .8f)
                } catch (_: StaleObjectException) { /* Route recomposition; reacquire. */ }
                device.waitForIdle()
            }
            if (!device.hasObject(selector)) {
                capture("missing-header-before-scenario-close")
                error("Missing exact remote header control $selector")
            }
        }
        fun waitFor(description: String, timeout: Long = 45_000, ready: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) {
                if (runCatching(ready).getOrDefault(false)) return
                SystemClock.sleep(150)
            }
            error("Timed out: $description")
        }
        fun profiles(): List<JSONObject> {
            val file = File(remoteRoot, "profiles.json")
            if (!file.exists()) return emptyList()
            val array = JSONObject(file.readText()).getJSONArray("profiles")
            return List(array.length()) { array.getJSONObject(it) }
        }
        fun tasks(profileId: String, direction: String): List<JSONObject> = remoteRoot.listFiles().orEmpty()
            .filter { it.isDirectory }.mapNotNull { dir -> runCatching { JSONObject(File(dir, "task.json").readText()) }.getOrNull() }
            .filter { it.optString("direction") == direction && it.getJSONObject("profile").getString("id") == profileId }
        fun task(profileId: String, direction: String) = tasks(profileId, direction).single()
        fun awaitStatus(profileId: String, direction: String, expected: String) {
            waitFor("$direction task $expected", 90_000) { task(profileId, direction).getString("status") == expected }
        }
        fun type(tag: String, value: String) {
            val node = find(By.res(tag))
            val input = if (node.className == "android.widget.EditText") node else node.findObject(By.clazz("android.widget.EditText")) ?: node
            input.text = value
            device.waitForIdle()
        }
        fun downloadsFolder() {
            capture("documents-picker-entry")
            // Tree mode on the expanded Pixel Tablet starts at primary storage with no drawer.
            // Use the actual Download child there, not an invented global navigation control.
            val breadcrumb = Pattern.compile(".*documentsui:id/breadcrumb_text")
            if (device.hasObject(By.res(breadcrumb).text(name))) return
            val downloadChild = By.res("android:id/title").text("Download")
            if (device.hasObject(By.res(breadcrumb).text("Download"))) {
                click(By.res(breadcrumb).text("Download"))
            } else if (device.wait(Until.hasObject(downloadChild), 1_500)) {
                click(downloadChild)
            } else {
                val roots = if (device.wait(Until.hasObject(By.desc("Show roots")), 10_000))
                    By.desc("Show roots") else By.desc("Navigate up")
                click(roots)
                check(device.wait(Until.hasObject(By.res("android:id/title").text("Downloads")), 10_000)) {
                    "DocumentsUI Downloads root absent"
                }
                click(By.res("android:id/title").text("Downloads"))
                waitFor("DocumentsUI Downloads root loaded") {
                    device.hasObject(By.res(breadcrumb).text(Pattern.compile("Downloads?"))) ||
                        device.hasObject(By.res("android:id/title").text(name))
                }
            }
            // Select the exact document title, not a truncated visual label or another UUID.
            click(By.res("android:id/title").text(name))
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            source = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$name/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            val bitmap = Bitmap.createBitmap(91, 73, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(19, 129, 223)) }
            resolver.openOutputStream(source!!)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            resolver.update(source!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val originalBytes = resolver.openInputStream(source!!)!!.use { it.readBytes() }
            val originalSha = sha(originalBytes)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                click(By.desc(context.getString(com.librestatic.lightforge.feature.photos.R.string.open_settings)))
                click(label(com.librestatic.lightforge.feature.settings.R.string.settings_backup))
                click(label(com.librestatic.lightforge.feature.settings.R.string.remote_backup_entry))
                awaitRemoteRoute()
                capture("remote-screen-ready")
                revealRemoteHeader(label(RemoteR.string.remote_add).enabled(true))
                capture("remote-screen-header")
                click(label(RemoteR.string.remote_add))
                type("remote-profile-name", name)
                type("remote-profile-host", "10.0.2.2")
                type("remote-profile-port", "22234")
                type("remote-profile-user", "lightforgefixture")
                type("remote-profile-folder", "/data/archive")
                type("remote-secret", "lightforge-fixture-password")
                if (device.hasObject(By.pkg("com.google.android.inputmethod.latin")) ||
                    device.hasObject(By.pkg("com.android.inputmethod.latin"))) device.pressBack()
                click(label(RemoteR.string.remote_save))
                waitFor("Profile stored") { profiles().any { it.getString("name") == name } }
                val profileId = profiles().single { it.getString("name") == name }.getString("id")
                revealRemoteHeader(By.res("remote-profile-$profileId").enabled(true))
                click(By.res("remote-profile-$profileId"))
                click(By.res("remote-test"))
                click(label(RemoteR.string.remote_trust))
                find(By.text(requireNotNull(fixtureProfile(name).trustedHostKey)))
                capture("host-key-review")
                click(label(RemoteR.string.remote_confirm))
                click(By.res("remote-test"))
                waitFor("Verified identity") {
                    device.findObject(By.res("remote-connection-identity"))?.text == fixtureProfile(name).trustedHostKey
                }
                capture("connection-verified")
                click(By.res("remote-select-sources"))
                // ACTION_OPEN_DOCUMENT may show the newly created UUID file in Recent.
                // That is a real provider selection and avoids unnecessary drawer navigation.
                val recentSource = By.res("android:id/title").text(fileName)
                if (device.wait(Until.hasObject(recentSource), 10_000)) click(recentSource)
                else {
                    downloadsFolder()
                    click(By.res("android:id/title").text(fileName))
                }
                awaitRemoteRoute()
                click(By.res("remote-prepare"))
                awaitStatus(profileId, "Upload", "AwaitingUploadReview")
                val uploadId = task(profileId, "Upload").getString("id")
                click(By.res("remote-review-$uploadId"))
                find(By.textContains(fileName))
                capture("upload-review")
                click(label(RemoteR.string.remote_upload))
                awaitStatus(profileId, "Upload", "Completed")
                val upload = task(profileId, "Upload")
                val archiveName = upload.getString("name")
                val archive = File(evidence, "remote.lightforge.zip")
                SftpRemoteConnectionFactory().connect(fixtureProfile(name),
                    RemoteCredentials.Password("lightforge-fixture-password".toCharArray()), RemoteCancellation()).use { connection ->
                    connection.openRead(archiveName).use { input -> archive.outputStream().use { output -> input.copyTo(output) } }
                }
                assertEquals(upload.getString("archiveSha"), sha(archive.readBytes()))
                val manifest = LocalBackupArchive.inspect(archive)
                assertEquals(1, manifest.entries.size)
                assertEquals(originalSha, manifest.entries.single().sha256)
                ZipFile(archive).use { zip -> assertArrayEquals(originalBytes, zip.getInputStream(zip.getEntry(manifest.entries.single().path)).use { it.readBytes() }) }
                capture("upload-completed")
                scenario.recreate()
                awaitRemoteRoute()
                revealRemoteHeader(By.res("remote-profile-$profileId").enabled(true))
                click(By.res("remote-profile-$profileId"))
                click(By.res("remote-list"))
                click(By.res("remote-download-$archiveName"))
                awaitStatus(profileId, "Download", "AwaitingRestoreReview")
                val downloadId = task(profileId, "Download").getString("id")
                val downloaded = File(remoteRoot, "$downloadId/archive.lightforge.zip")
                assertEquals(sha(archive.readBytes()), sha(downloaded.readBytes()))
                click(By.res("remote-review-$downloadId"))
                capture("download-review")
                click(label(RemoteR.string.remote_restore_folder))
                downloadsFolder()
                assertTrue("Restore must target only the owned UUID directory", device.hasObject(By.text(name)))
                click(By.text(Pattern.compile("USE THIS FOLDER", Pattern.CASE_INSENSITIVE)))
                val allow = By.res("android:id/button1").text(Pattern.compile("Allow", Pattern.CASE_INSENSITIVE))
                check(device.wait(Until.hasObject(allow), 10_000)) { "DocumentsUI exact Allow confirmation absent" }
                click(allow)
                awaitStatus(profileId, "Download", "RestoringLocally")
                val download = task(profileId, "Download")
                val localId = download.getString("localTaskId")
                val localStore = LocalBackupTaskStore(context)
                waitFor("Local folder restoration completed", 90_000) { localStore.read(localId)?.status == LocalBackupTaskStatus.Completed }
                val local = requireNotNull(localStore.read(localId))
                assertEquals(1, local.outputs.size)
                assertTrue(local.outputs.single().verified)
                val restored = Uri.parse(local.outputs.single().uri)
                assertNotEquals(source, restored)
                assertArrayEquals(originalBytes, resolver.openInputStream(restored)!!.use { it.readBytes() })
                assertEquals(originalSha, sha(resolver.openInputStream(source!!)!!.use { it.readBytes() }))
                capture("local-restore-completed")
                File(evidence, "result.json").writeText(JSONObject().put("status", "PASS").put("profileId", profileId)
                    .put("uploadId", uploadId).put("downloadId", downloadId).put("localTaskId", localId)
                    .put("remoteName", archiveName).put("archiveSha256", sha(archive.readBytes()))
                    .put("sourceSha256", originalSha).put("restoredUri", restored.toString())
                    .put("sourceUnchanged", true).put("profileRecreated", true).put("realSaf", true)
                    .put("serverPin", fixtureProfile(name).trustedHostKey).toString(2))
            }
        } catch (error: Throwable) {
            runCatching { capture("failure") }
            File(evidence, "failure.txt").writeText(error.stackTraceToString())
            throw error
        } finally {
            // UUID source only. Verified remote/local restore artifacts remain available for matrix audit.
            source?.let { resolver.delete(it, null, null) }
        }
    }

    @Test fun pdfEncryptionStillWorksBeforeAndAfterSftpWithoutChangingProviders() {
        fixtureGuard()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val evidence = File(context.filesDir, "sftp-app-pdf-${UUID.randomUUID()}").apply { mkdirs() }
        val providers = Security.getProviders().map { it.name to it.javaClass.name }
        fun encryptedPdf(label: String) {
            val file = File(evidence, "$label.pdf")
            PDDocument().use { document ->
                document.addPage(PDPage())
                document.protect(StandardProtectionPolicy("fixture-owner", "fixture-reader", AccessPermission()).apply { encryptionKeyLength = 128 })
                document.save(file)
            }
            PDDocument.load(file, "fixture-reader").use { document ->
                assertTrue(document.isEncrypted)
                assertEquals(1, document.numberOfPages)
            }
            assertThrows(java.io.IOException::class.java) { PDDocument.load(file, "wrong-password").close() }
        }
        encryptedPdf("before")
        SftpRemoteConnectionFactory().connect(fixtureProfile("PDF interop fixture"),
            RemoteCredentials.Password("lightforge-fixture-password".toCharArray()), RemoteCancellation()).use {
            assertTrue(it.capabilities.atomicPublish)
            assertEquals(fixtureProfile("PDF interop fixture").trustedHostKey, it.identity)
        }
        encryptedPdf("after")
        assertEquals(providers, Security.getProviders().map { it.name to it.javaClass.name })
        File(evidence, "result.json").writeText(JSONObject().put("status", "PASS").put("pdfBefore", true)
            .put("pdfAfter", true).put("wrongPasswordRejected", true).put("providersUnchanged", true).toString(2))
    }
}
