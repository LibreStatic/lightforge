package com.librestatic.lightforge

import android.Manifest
import android.content.Intent
import android.content.ContentUris
import android.content.ContentValues
import android.provider.MediaStore
import kotlinx.coroutines.flow.first
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.security.PrivateAlbumCrypto
import com.librestatic.lightforge.feature.privatealbum.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Only the explicitly owned demo acceptance UID. MainActivity and production auth callbacks are real. */
class PrivateProtectionAppDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val id get() = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")).also {
        require(UUID.fromString(it).toString() == it)
        check(context.packageName == Package)
    }
    private val legacy get() = "lightforge.privatealbum.fixture.legacy.$id"
    private val indexAlias get() = "lightforge.privatealbum.index.v1.${sha(DatabaseName.toByteArray())}"
    private val wrapper get() = File(context.noBackupFilesDir, "private-index-${sha(DatabaseName.toByteArray())}.key")
    private val manifest get() = AtomicFile(File(context.filesDir, "private-protection-fixture-$id.json"))
    private val sourceRoot get() = File(context.cacheDir, "private-protection-fixture-$id")
    private val containers get() = File(context.filesDir, "private-album")
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hash(file: File) = sha(file.readBytes())
    private fun marker(stage: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "AUTH_FIXTURE $id $stage\n") })
    }
    private fun read(): JSONObject = manifest.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }.also {
        check(it.getString("uuid") == id && it.getString("package") == Package)
        check(it.getString("legacyAlias") == legacy && it.getString("indexAlias") == indexAlias)
    }
    private fun write(value: JSONObject) {
        val bytes = value.toString().toByteArray()
        val output = manifest.startWrite()
        try { output.write(bytes); output.fd.sync(); manifest.finishWrite(output) }
        catch (error: Throwable) { manifest.failWrite(output); throw error }
        check(manifest.openRead().use { it.readBytes() }.contentEquals(bytes))
        val directoryFd = Os.open(requireNotNull(manifest.baseFile.parentFile).path, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(directoryFd).st_mode))
            Os.fsync(directoryFd)
        } finally { Os.close(directoryFd) }
        read() // Recheck ownership fields after durable publication.
    }
    private fun fixedFiles(): List<File> {
        val db = context.getDatabasePath(DatabaseName)
        return listOf("", "-wal", "-shm", "-journal", ".encrypting", ".encrypting-wal", ".encrypting-shm", ".encrypting-journal", ".initializing", ".initializing-wal", ".initializing-shm", ".initializing-journal")
            .map { File(db.path + it) } + listOf("", ".bak", ".new").map { File(wrapper.path + it) } +
            listOf("$DatabaseName.index-migration.lock", "$DatabaseName.index-creating").map { File(context.noBackupFilesDir, it) }
    }
    private fun authAliases(): List<String> = store().aliases().toList().filter {
        it.startsWith(PrivateAlbumCrypto.AUTHENTICATED_MASTER_PREFIX)
    }.onEach { alias ->
        val suffix = alias.removePrefix(PrivateAlbumCrypto.AUTHENTICATED_MASTER_PREFIX)
        require(UUID.fromString(suffix).toString() == suffix)
    }
    private fun rememberUiAlias() {
        val record = read()
        val aliases = authAliases()
        check(aliases.size <= 1 && (aliases.isEmpty() || record.getBoolean("allowUiKeyCreation")))
        val previous = record.optString("authAlias")
        aliases.singleOrNull()?.let { alias ->
            check(previous.isEmpty() || previous == alias)
            write(record.put("authAlias", alias))
        }
    }
    private fun passwordHash(): String {
        val bytes = wrapper.readBytes()
        check(bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(85, 71, 73, 75)))
        val version = bytes[4].toInt()
        val alias: String
        val offset: Int
        if (version == 1) { check(bytes.size == 65); alias = indexAlias; offset = 5 }
        else {
            check(version == 2)
            val length = ((bytes[5].toInt() and 255) shl 8) or (bytes[6].toInt() and 255)
            check(length in 1..200 && bytes.size == 67 + length)
            alias = bytes.copyOfRange(7, 7 + length).toString(Charsets.US_ASCII)
            check(alias == read().getString("authAlias")); offset = 7 + length
        }
        val key = store().getKey(alias, null) as SecretKey
        val aad = if (version == 1) "Lightforge private index key v1:$DatabaseName"
            else "Lightforge private index key v2:$DatabaseName:$alias"
        val password = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, offset, 12))
            updateAAD(aad.toByteArray()); doFinal(bytes, offset + 12, 48)
        }
        return try { check(password.size == 32); sha(password) } finally { password.fill(0) }
    }

    @Test fun bootstrapOwnedLegacyVault(): Unit = runBlocking {
        if (manifest.baseFile.exists() || File(manifest.baseFile.path + ".bak").exists()) {
            check(read().optBoolean("cleanupConfirmed", false)) { "Prior owned fixture cleanup must finish before retry" }
        }
        check(fixedFiles().none { it.exists() } && !containers.exists() && !sourceRoot.exists())
        check(authAliases().isEmpty() && !store().containsAlias(legacy) && !store().containsAlias(indexAlias))
        check(!store().containsAlias(PrivateAlbumCrypto.MASTER_KEY_ALIAS))
        // Record ownership before the first file/key operation. No existing vault is reset.
        write(JSONObject().put("uuid", id).put("package", Package).put("legacyAlias", legacy)
            .put("indexAlias", indexAlias).put("allowUiKeyCreation", false).put("rows", JSONArray()))
        check(sourceRoot.mkdir())
        val database = PrivateAlbumDatabase.open(context)
        try {
            val repo = PrivateAlbumRepository(context, database)
            repo.setupNewAlbum(legacy)
            val rows = JSONArray()
            for ((index, color) in listOf(Color.RED, Color.BLUE).withIndex()) {
                val source = File(sourceRoot, "owned-$id-$index.jpg")
                val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
                try { bitmap.eraseColor(color); source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it)) } }
                finally { bitmap.recycle() }
                val result = repo.importFromUri(Uri.fromFile(source), source.name, "image/jpeg", "image", masterKey = repo.requireMasterKeyBinding())
                check(result.success)
                val row = requireNotNull(repo.getMetadata(requireNotNull(result.mediaId)))
                rows.put(JSONObject().put("id", row.id).put("source", source.name).put("sourceHash", hash(source))
                    .put("container", File(row.containerPath).name).put("containerHash", hash(File(row.containerPath)))
                    .put("wrappedHash", sha(row.encryptedDataKey)).put("ivHash", sha(row.dataKeyIv))
                    .put("name", row.originalDisplayName).put("added", row.addedAtMillis))
                write(read().put("rows", rows))
            }
            check(repo.count() == 2)
            write(read().put("passwordHash", passwordHash()).put("bootstrapComplete", true))
            marker("BOOTSTRAP_READY")
        } finally { database.close() }
    }

    private val scenario get() = InstrumentationRegistry.getArguments().getString("scenario") ?: "legacy"

    private fun requireAbsentPrivateState() {
        check(fixedFiles().none { it.exists() } && !containers.exists() && !sourceRoot.exists())
        check(store().aliases().toList().none { it.startsWith("lightforge.privatealbum.") }) {
            "Existing private keys must be preserved"
        }
    }

    /** Read-only admission. A clean directory alone never establishes ownership of an installed UID. */
    @Test fun verifyOwnedEmptyAdmission() {
        check(scenario == "empty-setup")
        val previous = read()
        check(previous.getBoolean("verified") && previous.getBoolean("cleanupConfirmed"))
        requireAbsentPrivateState()
        marker("EMPTY_ADMISSION_CONFIRMED")
    }

    /** Establishes ownership and one public input only; production UI creates all private state. */
    @Test fun prepareOwnedEmptyFixture() {
        check(scenario == "empty-setup")
        if (manifest.baseFile.exists() || File(manifest.baseFile.path + ".bak").exists())
            check(read().optBoolean("cleanupConfirmed", false))
        requireAbsentPrivateState()
        val source = JSONObject().put("name", "private-empty-$id.jpg")
            .put("relativePath", "Pictures/private-empty-$id/")
        write(JSONObject().put("uuid", id).put("package", Package).put("legacyAlias", legacy)
            .put("indexAlias", indexAlias).put("scenario", "empty-setup")
            .put("initialPrivateStateAbsent", true).put("allowUiKeyCreation", false)
            .put("rows", JSONArray()).put("mediaSource", source))
        val uri = checkNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.getString("name"))
                put(MediaStore.MediaColumns.RELATIVE_PATH, source.getString("relativePath"))
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        write(read().put("mediaSource", source.put("uri", uri.toString())))
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            context.contentResolver.openOutputStream(uri, "w")!!.use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it))
            }
        } finally { bitmap.recycle() }
        val sourceHash = context.contentResolver.openInputStream(uri)!!.use { sha(it.readBytes()) }
        write(read().put("mediaSource", source.put("hash", sourceHash)))
        check(context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1)
        val published = checkNotNull(queryPublicSource(uri.toString()))
        check(published.owner == Package && published.name == source.getString("name") &&
            published.relativePath == source.getString("relativePath") && published.pending == 0L)
        check(context.contentResolver.openInputStream(uri)!!.use { sha(it.readBytes()) } == sourceHash)
        check(queryPublicSource(uri.toString()) == published) { "Source changed while capturing publication identity" }
        write(read().put("mediaSource", source.put("generationAdded", published.generationAdded)
            .put("generationModified", published.generationModified)))
        requireAbsentPrivateState() // Still no private DB, setup, imports, containers or aliases.
        write(read().put("bootstrapComplete", true))
        marker("BOOTSTRAP_READY")
    }

    private data class PublicSourceSnapshot(
        val uri: String, val mediaId: Long, val name: String, val relativePath: String,
        val owner: String, val generationAdded: Long, val generationModified: Long, val pending: Long,
    )

    private fun queryPublicSource(value: String): PublicSourceSnapshot? {
        val uri = Uri.parse(value)
        check(uri.scheme == "content" && uri.authority == "media" && uri.pathSegments.size == 4 &&
            uri.pathSegments.take(3) == listOf("external_primary", "images", "media") && ContentUris.parseId(uri) >= 0)
        return context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
            if (!it.moveToFirst()) null else {
                val snapshot = PublicSourceSnapshot(value, it.getLong(0), it.getString(1), it.getString(2),
                    it.getString(3), it.getLong(4), it.getLong(5), it.getLong(6))
                check(!it.moveToNext() && snapshot.mediaId == ContentUris.parseId(uri) &&
                    snapshot.generationAdded >= 0 && snapshot.generationModified >= 0)
                snapshot
            }
        }
    }

    private fun validatedPublicSource(ownership: JSONObject): PublicSourceSnapshot? {
        val source = ownership.optJSONObject("mediaSource") ?: return null
        check(ownership.getString("scenario") == "empty-setup" && ownership.getBoolean("initialPrivateStateAbsent"))
        check(source.getString("name") == "private-empty-$id.jpg" &&
            source.getString("relativePath") == "Pictures/private-empty-$id/")
        if (!source.has("uri")) {
            // A publish/receipt interruption is not authority to adopt a row by its filename.
            // Prove the planned insert never appeared, otherwise retain it for explicit recovery.
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(source.getString("name"), source.getString("relativePath")), null)!!.use {
                check(!it.moveToFirst()) { "Unreceipted public insert retained" }
            }
            return null
        }
        val current = queryPublicSource(source.getString("uri")) ?: return null
        check(current.name == source.getString("name") && current.relativePath == source.getString("relativePath") &&
            current.owner == Package && current.pending == 0L)
        // Both fields are mandatory for a present row. An incomplete publication receipt
        // retains the public item and all private keys rather than weakening deletion guards.
        check(current.generationAdded == source.getLong("generationAdded") &&
            current.generationModified == source.getLong("generationModified")) { "Changed public generation retained" }
        val actual = context.contentResolver.openInputStream(Uri.parse(current.uri))!!.use { sha(it.readBytes()) }
        check(actual == source.getString("hash")) { "Changed public source retained" }
        check(queryPublicSource(current.uri) == current) { "Source changed while its hash was verified" }
        return current
    }

    private fun ownedPublicSource(): Uri = Uri.parse(checkNotNull(validatedPublicSource(read())) {
        "Owned public source must still exist during acceptance"
    }.uri)

    /** After leaving the private route, inspect with an independent revocable read session. */
    private suspend fun verifyEmptyProtected(capture: Boolean) {
        val source = read().getJSONObject("mediaSource")
        val uri = ownedPublicSource()
        val repo = PrivateAlbumRepository.sessionBacked(context)
        try {
            repo.openSession()
            check(repo.isSetup() && repo.count() == 1)
            assertEquals(PrivateKeyProtectionStatus.Protected, repo.keyProtection().status())
            val binding = repo.requireMasterKeyBinding()
            assertEquals(read().getString("authAlias"), binding.alias)
            assertEquals(2, wrapper.readBytes()[4].toInt())
            assertFalse(store().containsAlias(legacy))
            assertFalse(store().containsAlias(indexAlias))
            assertFalse(store().containsAlias(PrivateAlbumCrypto.MASTER_KEY_ALIAS))
            assertEquals(read().getString("passwordHash"), passwordHash())
            val row = repo.allMedia.first().single()
            assertEquals(uri.toString(), row.originalMediaKey)
            assertEquals(source.getString("name"), row.originalDisplayName)
            assertEquals("image/jpeg", row.originalMimeType)
            val container = File(row.containerPath)
            check(container.isFile && container.canonicalFile.parentFile == containers.canonicalFile)
            val result = JSONObject().put("id", row.id).put("sourceUri", uri.toString())
                .put("sourceHash", source.getString("hash")).put("container", container.name)
                .put("containerHash", hash(container)).put("wrappedHash", sha(row.encryptedDataKey))
                .put("ivHash", sha(row.dataKeyIv)).put("name", row.originalDisplayName).put("added", row.addedAtMillis)
            if (capture) {
                check(read().getJSONArray("rows").length() == 0)
                write(read().put("rows", JSONArray().put(result)))
            } else {
                val before = read().getJSONArray("rows")
                check(before.length() == 1)
                val expected = before.getJSONObject(0)
                assertSamePrivateRow(expected, result)
            }
            val key = requireNotNull(repo.getDecryptedDataKey(row.id, binding))
            val plaintext = ByteArrayOutputStream()
            container.inputStream().use { PrivateAlbumCrypto.decryptStream(it, plaintext, key, row.sha256) }
            assertEquals(source.getString("hash"), sha(plaintext.toByteArray()))
            ownedPublicSource()
        } finally { repo.disposeSession(); repo.awaitSessionClosed() }
    }

    private fun assertSamePrivateRow(expected: JSONObject, actual: JSONObject) {
        // JSON reparsing narrows small integral values to Integer; Room uses Long.
        // Compare the schema's numeric values, not their JVM box identities.
        for (field in listOf("id", "added"))
            assertEquals("Readback must preserve $field", expected.getLong(field), actual.getLong(field))
        for (field in listOf("sourceUri", "sourceHash", "container", "containerHash", "wrappedHash", "ivHash", "name"))
            assertEquals("Readback must preserve $field", expected.getString(field), actual.getString(field))
    }

    @Test fun receiptRoundTripPreservesLongFieldsAndRejectsChangedContent() {
        val actual = JSONObject().put("id", 1L).put("added", 1788746635331L)
        for (field in listOf("sourceUri", "sourceHash", "container", "containerHash", "wrappedHash", "ivHash", "name"))
            actual.put(field, "fixture-$field")
        val expected = JSONObject(actual.toString())
        // Reproduce the observer defect without touching credentials or app files.
        assertThrows(AssertionError::class.java) { assertEquals(expected.get("id"), actual.get("id")) }
        assertSamePrivateRow(expected, actual)
        for (field in listOf("id", "added")) {
            val changed = JSONObject(actual.toString()).put(field, actual.getLong(field) + 1)
            assertThrows(AssertionError::class.java) { assertSamePrivateRow(expected, changed) }
        }
        for (field in listOf("sourceUri", "sourceHash", "container", "containerHash", "wrappedHash", "ivHash", "name")) {
            val changed = JSONObject(actual.toString()).put(field, "changed")
            assertThrows(AssertionError::class.java) { assertSamePrivateRow(expected, changed) }
        }
    }

    private fun grantLibraryMediaPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissions.forEach { permission ->
            instrumentation.uiAutomation.grantRuntimePermission(Package, permission)
        }
    }

    @Test fun emptyVaultConfiguresImportsAndReopens(): Unit = runBlocking {
        check(scenario == "empty-setup" && read().getString("scenario") == "empty-setup")
        check(read().getBoolean("bootstrapComplete") && read().getBoolean("initialPrivateStateAbsent"))
        requireAbsentPrivateState()
        check(context.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure)
        grantLibraryMediaPermissions()
        val sourceUri = ownedPublicSource()
        start()
        val decline = label(com.librestatic.lightforge.feature.settings.R.string.local_analysis_opt_out_decline)
        if (device.wait(Until.hasObject(decline), 2_000)) click(decline)
        val collections = context.getString(R.string.nav_collections)
        click(if (device.hasObject(By.desc(collections))) By.desc(collections) else By.text(collections))
        val privateCard = By.desc(context.getString(R.string.m6_private_album) + ". " +
            context.getString(com.librestatic.lightforge.feature.collections.R.string.collections_private_album_body))
        click(privateCard, scroll = true)
        marker("AUTH_REQUIRED_OPEN")
        click(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_unlock))
        publishCredentialInput("OPEN")
        find(tag("private-key-protection-dialog"), 60_000)
        assertFalse(store().containsAlias(legacy))
        assertFalse(store().containsAlias(PrivateAlbumCrypto.MASTER_KEY_ALIAS))
        assertTrue(authAliases().isEmpty())
        assertTrue(ownedDirectoryFiles(containers).isEmpty())
        check(wrapper.readBytes()[4].toInt() == 1)
        write(read().put("passwordHash", passwordHash()).put("emptySetupDialogObserved", true))
        marker("AUTH_SUCCEEDED_OPEN")
        write(read().put("allowUiKeyCreation", true))
        marker("AUTH_REQUIRED_SETUP")
        click(tag("private-key-protection-confirm"))
        publishCredentialInput("SETUP")
        find(tag("private-key-protection-done"), 90_000)
        rememberUiAlias()
        assertEquals(context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_key_protection_complete),
            find(tag("private-key-protection-status")).text)
        marker("AUTH_SUCCEEDED_SETUP")
        click(tag("private-key-protection-done"))
        find(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_empty_title))
        find(By.text(context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_title_count, 0)))
        write(read().put("protectedEmptyCountObserved", true))
        click(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_choose_media))
        find(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_picker_title))
        click(tag("media_external_primary_${ContentUris.parseId(sourceUri)}"), scroll = true)
        find(By.text(context.resources.getQuantityString(com.librestatic.lightforge.feature.privatealbum.R.plurals.private_picker_count, 1, 1)))
        click(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_picker_add))
        find(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_import_result_title), 60_000)
        click(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_keep_originals))
        find(By.text(context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_title_count, 1)))
        find(By.desc(read().getJSONObject("mediaSource").getString("name") + ", " +
            context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_photo)))
        ownedPublicSource()
        // Explicit exit revokes the real production session; never tap the item (that exports it).
        click(By.desc(context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_back)))
        verifyEmptyProtected(capture = true)
        marker("IMPORT_SUCCEEDED_KEEP_ORIGINALS")
        click(privateCard, scroll = true)
        find(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_unlock))
        assertFalse(device.hasObject(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_portable_title)))
        marker("AUTH_REQUIRED_REOPEN")
        click(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_unlock))
        publishCredentialInput("REOPEN")
        find(By.text(context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_title_count, 1)), 60_000)
        assertFalse(device.hasObject(tag("private-key-protection-dialog")))
        marker("AUTH_SUCCEEDED_REOPEN")
        device.pressHome(); delay(750)
        verifyEmptyProtected(capture = false)
        write(read().put("verified", true).put("publicSourceUnchanged", true).put("reauthenticatedReadback", true))
        marker("PROTECTION_APP_PASS")
    }

    private fun find(selector: BySelector, timeout: Long = 25_000, scroll: Boolean = false): UiObject2 {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var scrolls = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val decline = By.text(context.getString(com.librestatic.lightforge.feature.settings.R.string.local_analysis_opt_out_decline))
            device.findObject(decline)?.let { notice ->
                try { var action = notice; while (!action.isClickable) { action = action.parent ?: break }; action.click() }
                catch (_: StaleObjectException) { }
            }
            try { device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it } }
            catch (_: StaleObjectException) { }
            if (scroll && scrolls < 20) device.findObjects(By.pkg(Package).scrollable(true))
                .mapNotNull { node ->
                    try { node.visibleBounds.takeUnless { it.isEmpty } }
                    catch (_: StaleObjectException) { null }
                }.maxByOrNull { it.width().toLong() * it.height() }?.let { bounds ->
                try {
                    // Tablet navigation and content are independently scrollable. The main
                    // content is the largest visible pane, not the first accessibility node.
                    // Compose may move without UiObject2.scroll reporting a scroll event.
                    // Search downward with overlapping real gestures, never bounce on that boolean.
                    if (bounds.height() > 100) {
                        device.swipe(bounds.centerX(), bounds.top + bounds.height() * 4 / 5,
                            bounds.centerX(), bounds.top + bounds.height() / 4, 40)
                        marker("SEARCH_SCROLL_${++scrolls}")
                    }
                } catch (_: StaleObjectException) { }
            }
            SystemClock.sleep(150)
        }
        runCatching { device.takeScreenshot(File(context.filesDir, "private-protection-$id-missing.png")) }
        runCatching { device.dumpWindowHierarchy(File(context.filesDir, "private-protection-$id-missing.xml")) }
        error("Missing production UI control: $selector; foreground=${device.currentPackageName}")
    }
    private fun click(selector: BySelector, scroll: Boolean = false) {
        var node = find(selector, scroll = scroll)
        while (!node.isClickable) { node = node.parent ?: break }
        node.click()
    }
    /** The instrumentation owns the sole accessibility connection; host supplies only PIN input. */
    private fun publishCredentialInput(phase: String) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val packages = listOf("com.android.settings", "com.android.systemui")
            val inputs = packages.flatMap { pkg ->
                device.findObjects(By.pkg(pkg).clazz("android.widget.EditText").enabled(true))
            }.filter { !it.visibleBounds.isEmpty }
            if (inputs.size == 1) {
                val bounds = inputs.single().visibleBounds
                marker("AUTH_INPUT_$phase ${bounds.centerX()} ${bounds.centerY()}")
                return
            }
            val alternatives = packages.flatMap { pkg ->
                device.findObjects(By.pkg(pkg).text(java.util.regex.Pattern.compile(
                    "use.*pin|use.*password|device.*credential", java.util.regex.Pattern.CASE_INSENSITIVE)))
            }.filter { !it.visibleBounds.isEmpty }
            if (alternatives.size == 1) {
                var action = alternatives.single()
                while (!action.isClickable) action = action.parent ?: break
                action.click()
            }
            SystemClock.sleep(200)
        }
        error("Platform credential input did not appear for $phase")
    }

    private fun tag(value: String) = By.res(value)
    private fun label(resource: Int) = By.text(context.getString(resource))
    private fun start() {
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private suspend fun verifyProtected() {
        // MainActivity is stopped before this independent, read-only managed lease is opened.
        val repo = PrivateAlbumRepository.sessionBacked(context)
        try {
            repo.openSession()
            assertEquals(PrivateKeyProtectionStatus.Protected, repo.keyProtection().status())
            val binding = repo.requireMasterKeyBinding()
            assertEquals(read().getString("authAlias"), binding.alias)
            assertEquals(2, repo.count())
            assertEquals(2, wrapper.readBytes()[4].toInt())
            assertFalse(store().containsAlias(legacy)); assertFalse(store().containsAlias(indexAlias))
            assertEquals(read().getString("passwordHash"), passwordHash())
            val rows = read().getJSONArray("rows")
            for (index in 0 until rows.length()) {
                val before = rows.getJSONObject(index)
                val row = requireNotNull(repo.getMetadata(before.getLong("id")))
                assertEquals(before.getString("name"), row.originalDisplayName)
                assertEquals(before.getLong("added"), row.addedAtMillis)
                assertEquals(before.getString("container"), File(row.containerPath).name)
                assertEquals(before.getString("containerHash"), hash(File(row.containerPath)))
                assertNotEquals(before.getString("wrappedHash"), sha(row.encryptedDataKey))
                assertNotEquals(before.getString("ivHash"), sha(row.dataKeyIv))
                val dataKey = requireNotNull(repo.getDecryptedDataKey(row.id, binding))
                val plaintext = ByteArrayOutputStream()
                File(row.containerPath).inputStream().use { PrivateAlbumCrypto.decryptStream(it, plaintext, dataKey, row.sha256) }
                assertEquals(before.getString("sourceHash"), sha(plaintext.toByteArray()))
                assertEquals(before.getString("sourceHash"), hash(File(sourceRoot, before.getString("source"))))
            }
        } finally { repo.disposeSession(); repo.awaitSessionClosed() }
    }

    @Test fun legacyVaultMigratesThroughProductionUiAndReopens(): Unit = runBlocking {
        check(read().getBoolean("bootstrapComplete"))
        check(context.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure)
        grantLibraryMediaPermissions()
        start()
        val decline = label(com.librestatic.lightforge.feature.settings.R.string.local_analysis_opt_out_decline)
        if (device.wait(Until.hasObject(decline), 2_000)) click(decline)
        val collections = context.getString(R.string.nav_collections)
        click(if (device.hasObject(By.desc(collections))) By.desc(collections) else By.text(collections))
        // CollectionCard explicitly merges title/body into its accessible description.
        click(By.desc(context.getString(R.string.m6_private_album) + ". " +
            context.getString(com.librestatic.lightforge.feature.collections.R.string.collections_private_album_body)), scroll = true)
        marker("AUTH_REQUIRED_OPEN")
        click(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_unlock))
        publishCredentialInput("OPEN")
        find(By.desc(context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_key_protection_action)), 60_000)
        marker("AUTH_SUCCEEDED_OPEN")
        click(By.desc(context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_key_protection_action)))
        write(read().put("allowUiKeyCreation", true))
        marker("AUTH_REQUIRED_MIGRATION")
        click(tag("private-key-protection-confirm"))
        publishCredentialInput("MIGRATION")
        find(tag("private-key-protection-done"), 90_000)
        rememberUiAlias()
        assertEquals(context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_key_protection_complete),
            find(tag("private-key-protection-status")).text)
        marker("AUTH_SUCCEEDED_MIGRATION")
        click(tag("private-key-protection-done"))
        find(By.text(context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_title_count, 2)))
        device.pressHome(); delay(750)
        verifyProtected()
        marker("WAIT_EXPIRY")
        delay(32_000)
        val expired = PrivateAlbumRepository.sessionBacked(context)
        try {
            try { expired.openSession(); fail("Expired protected index reopened without authentication") }
            catch (failure: Exception) { assertTrue(PrivateAlbumCrypto.requiresAuthentication(failure)) }
        } finally { expired.disposeSession(); expired.awaitSessionClosed() }
        start()
        find(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_unlock))
        assertFalse(device.hasObject(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_portable_title)))
        marker("AUTH_REQUIRED_REOPEN")
        click(label(com.librestatic.lightforge.feature.privatealbum.R.string.private_unlock))
        publishCredentialInput("REOPEN")
        find(By.text(context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_title_count, 2)), 60_000)
        assertFalse(device.hasObject(By.desc(context.getString(com.librestatic.lightforge.feature.privatealbum.R.string.private_key_protection_action))))
        marker("AUTH_SUCCEEDED_REOPEN")
        device.pressHome(); delay(750)
        verifyProtected()
        write(read().put("verified", true))
        marker("PROTECTION_APP_PASS")
    }

    private fun requireLegacySourceHashes(expected: Map<String, String>, actual: Map<String, String>) {
        check(actual.keys == expected.keys) { "Missing or unattributed legacy source retained with keys" }
        expected.forEach { (name, expectedHash) ->
            check(actual.getValue(name) == expectedHash) { "Changed legacy source retained with keys" }
        }
    }

    private fun confirmPublicDeletion(
        expected: PublicSourceSnapshot,
        query: () -> PublicSourceSnapshot?,
        delete: (PublicSourceSnapshot) -> Int,
    ) {
        val current = query() ?: return // Exact URI already absent on retry; never delete it again.
        check(current == expected) { "Source changed before CAS deletion; private keys retained" }
        check(delete(expected) == 1) { "Public source CAS deletion failed; private keys retained" }
        check(query() == null) { "Public source absence unconfirmed; private keys retained" }
    }

    private fun deletePublicSourceCas(source: PublicSourceSnapshot): Int = context.contentResolver.delete(
        Uri.parse(source.uri),
        "${MediaStore.MediaColumns._ID}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.GENERATION_ADDED}=? AND ${MediaStore.MediaColumns.GENERATION_MODIFIED}=? AND " +
            "${MediaStore.MediaColumns.IS_PENDING}=?",
        arrayOf(source.mediaId.toString(), source.owner, source.name, source.relativePath,
            source.generationAdded.toString(), source.generationModified.toString(), source.pending.toString()),
    )

    /** No resolver/Keystore calls: small guard contracts executed by the coordinator if desired. */
    @Test fun legacyCleanupRequiresEveryDurableSourceHashBeforeRetirement() {
        val expected = mapOf("owned-one.jpg" to "hash-one", "owned-two.jpg" to "hash-two")
        for (actual in listOf(mapOf("owned-one.jpg" to "hash-one"), expected + ("owned-two.jpg" to "changed"),
            expected + ("unattributed.jpg" to "other"))) {
            var retired = false
            try { requireLegacySourceHashes(expected, actual); retired = true }
            catch (_: IllegalStateException) { }
            assertFalse("Rejected input must not reach key retirement", retired)
        }
        requireLegacySourceHashes(expected, expected)
    }

    @Test fun publicCleanupCasFailuresBlockRetirementAndAbsentRetryDoesNotDelete() {
        val source = PublicSourceSnapshot("fixture-uri", 4, "owned.jpg", "Pictures/owned/", "fixture-owner", 7, 9, 0)
        for (failure in listOf("changed-before", "zero-delete", "delete-throws", "still-present")) {
            var retired = false
            var calls = 0
            try {
                confirmPublicDeletion(source, query = {
                    if (failure == "changed-before") source.copy(generationModified = 10) else source
                }, delete = {
                    calls++
                    if (failure == "delete-throws") throw IllegalStateException("provider failure")
                    if (failure == "zero-delete") 0 else 1
                })
                retired = true
            } catch (_: IllegalStateException) { }
            assertFalse("$failure must preserve key retirement boundary", retired)
            if (failure == "changed-before") assertEquals(0, calls)
        }
        var absentQueries = 0
        confirmPublicDeletion(source, query = { absentQueries++; null }, delete = { error("Absent URI must not be deleted") })
        assertEquals(1, absentQueries)
        var queries = 0
        var deletes = 0
        confirmPublicDeletion(source, query = { if (queries++ == 0) source else null }, delete = { deletes++; 1 })
        assertEquals(2, queries); assertEquals(1, deletes)
    }

    private fun ownedDirectoryFiles(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        check(directory.isDirectory) { "Owned fixture path is not a directory" }
        return checkNotNull(directory.listFiles()) { "Directory enumeration failed; retain keys and files" }.toList()
    }

    @Test fun unreadableDirectoryCannotPassTheKeyRetirementBoundary() {
        val unreadable = object : File("owned-fixture-directory") {
            override fun exists() = true
            override fun isDirectory() = true
            override fun listFiles(): Array<File>? = null
        }
        var retired = false
        try { ownedDirectoryFiles(unreadable); retired = true }
        catch (_: IllegalStateException) { }
        assertFalse(retired)
        val absent = object : File("absent-fixture-directory") {
            override fun exists() = false
            override fun listFiles(): Array<File>? = error("Absent directory must not be enumerated")
        }
        assertTrue(ownedDirectoryFiles(absent).isEmpty())
    }

    @Test fun cleanupOwnedFixture() {
        val ownership = read()
        check(!ownership.optBoolean("cleanupConfirmed", false)) {
            "A completed receipt does not authorize cleanup of a later vault"
        }
        rememberUiAlias()
        val publicSource = validatedPublicSource(ownership)
        val rows = (0 until ownership.getJSONArray("rows").length()).map {
            ownership.getJSONArray("rows").getJSONObject(it)
        }
        val expectedContainers = rows.associate { it.getString("container") to it.getString("containerHash") }
        check(expectedContainers.size == rows.size)
        if (ownership.optString("scenario", "legacy") == "legacy")
            check(rows.all { it.has("source") && it.has("sourceHash") })
        val expectedLegacySources = rows.filter { it.has("source") }
            .associate { it.getString("source") to it.getString("sourceHash") }
        check(expectedLegacySources.size == rows.count { it.has("source") })
        check(!containers.exists() || containers.isDirectory)
        check(!sourceRoot.exists() || sourceRoot.isDirectory)
        val containerFiles = ownedDirectoryFiles(containers)
        val sourceFiles = ownedDirectoryFiles(sourceRoot)
        // Validate every file BEFORE retiring keys. Unexpected/unattributed output stops
        // cleanup while keys and the temporary credential remain available for recovery.
        containerFiles.forEach { file ->
            check(file.isFile && file.canonicalFile.parentFile == containers.canonicalFile)
            check(file.name in expectedContainers && hash(file) == expectedContainers.getValue(file.name)) {
                "Unattributed or changed private container retained with its keys"
            }
        }
        sourceFiles.forEach { file ->
            check(file.isFile && file.canonicalFile.parentFile == sourceRoot.canonicalFile)
            check(file.name in setOf("owned-$id-0.jpg", "owned-$id-1.jpg"))
        }
        requireLegacySourceHashes(expectedLegacySources, sourceFiles.associate { it.name to hash(it) })
        fixedFiles().forEach { check(!it.exists() || it.isFile) }
        val aliases = mutableListOf(legacy, indexAlias)
        read().optString("authAlias").takeIf { it.isNotEmpty() }?.let(aliases::add)
        val keys = store()
        check(keys.aliases().toList().filter { it.startsWith("lightforge.privatealbum.") }.all { it in aliases })
        if (publicSource != null) confirmPublicDeletion(publicSource,
            query = { queryPublicSource(publicSource.uri) }, delete = ::deletePublicSourceCas)
        // Also covers a retry whose exact URI was absent before this attempt. All sources
        // and containers above were revalidated before reaching this retirement boundary.
        check(validatedPublicSource(ownership) == null)
        aliases.forEach { keys.deleteEntry(it) }
        check(aliases.none(keys::containsAlias) && authAliases().isEmpty())
        containerFiles.forEach { check(it.delete()) }
        if (containers.exists()) check(containers.delete())
        sourceFiles.forEach { check(it.delete()) }
        if (sourceRoot.exists()) check(sourceRoot.delete())
        fixedFiles().forEach { if (it.exists()) check(it.delete()) }
        ownership.optJSONObject("mediaSource")?.let { source ->
            val uri = publicSource?.uri?.let(Uri::parse) ?: source.optString("uri").takeIf { it.isNotEmpty() }?.let(Uri::parse)
                ?: return@let
            val libraryFile = context.getDatabasePath(com.librestatic.lightforge.core.database.GalleryDatabaseFactory.DatabaseName)
            if (libraryFile.exists()) {
                val library = com.librestatic.lightforge.core.database.GalleryDatabaseFactory.open(context)
                try {
                    library.openHelper.writableDatabase.execSQL(
                        "DELETE FROM media_items WHERE volumeName='external_primary' AND mediaStoreId=? AND displayName=? AND relativePath=?",
                        arrayOf<Any>(ContentUris.parseId(uri), source.getString("name"), source.getString("relativePath")))
                } finally { library.close() }
            }
        }
        check(fixedFiles().none { it.exists() } && !containers.exists() && !sourceRoot.exists())
        check(keys.aliases().toList().none { it.startsWith("lightforge.privatealbum.") })
        write(read().put("cleanupConfirmed", true))
        marker("CLEANUP_CONFIRMED")
    }

    companion object {
        const val Package = "com.librestatic.lightforge.demo.pdfacceptance"
        private const val DatabaseName = PrivateAlbumDatabase.DatabaseName
    }
}
