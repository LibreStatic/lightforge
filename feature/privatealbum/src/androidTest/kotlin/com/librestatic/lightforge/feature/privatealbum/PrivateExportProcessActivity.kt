package com.librestatic.lightforge.feature.privatealbum

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.security.PrivateAlbumCrypto
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Launched normally from the test APK; no instrumentation, grants or persistent media master. */
class PrivateExportProcessActivity : ComponentActivity() {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var fixture: Owned? = null
    private var database: PrivateAlbumDatabase? = null
    private var repository by mutableStateOf<PrivateAlbumRepository?>(null)
    private var uiDisposed = CompletableDeferred<Unit>()
    @Volatile private var uiMounted = false
    private var state by mutableStateOf("Preparing")
    private var mode = "prepare"
    private var id = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LightforgeTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true }.padding(8.dp)) {
                    Text(state, Modifier.testTag("private-export-process-state"))
                    repository?.let { repo ->
                        DisposableEffect(repo) {
                            uiMounted = true
                            onDispose { uiMounted = false; uiDisposed.complete(Unit) }
                        }
                        Box(Modifier.weight(1f)) {
                            PrivateExportRecoveryContent(repo, true,
                                onBack = { repository = null },
                                onAuthenticationRequired = { state = "Failed" })
                        }
                        Button(onClick = { verify() }, modifier = Modifier.testTag("private-export-process-verify")) {
                            Text("Verify fixture")
                        }
                    }
                }
            }
        }
        val requested = intent.getStringExtra("mode") ?: error("Required mode")
        val restoredReady = savedInstanceState?.getBoolean("ready") == true
        // Saved task restoration must not replay its original prepare Intent.
        dispatch(intent, if (requested == "prepare" && restoredReady) "recover" else requested)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        dispatch(intent, requireNotNull(intent.getStringExtra("mode")))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("ready", state in setOf("Ready", "RecoveredReady", "Completed", "Acked"))
        outState.putString("fixtureUuid", id)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        super.onStop()
        val f = fixture ?: return
        val stoppedPid = Process.myPid(); val stoppedTask = taskId
        scope.launch(Dispatchers.IO) {
            if (f.canRecordFailure && f.hasReceipt()) f.update {
                if (!it.optBoolean("cleanupComplete")) it.put("stopped", true)
                    .put("stopPid", stoppedPid).put("stopTaskId", stoppedTask)
            }
        }
    }
    override fun onDestroy() {
        owner.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            owner.join()
            if (uiMounted) withTimeout(10_000) { uiDisposed.await() }
            database?.close(); database = null
        }
        super.onDestroy()
    }
    private fun dispatch(incoming: Intent, requested: String) {
        check(packageName == "com.librestatic.lightforge.feature.privatealbum.test")
        val uuid = requireNotNull(incoming.getStringExtra("fixtureUuid"))
        require(UUID.fromString(uuid).toString() == uuid)
        require(requested in setOf("prepare", "recover", "cleanup"))
        check(id.isEmpty() || id == uuid) { "Never replace a different active fixture" }
        if (id == uuid && requested == "recover" && mode == "recover" &&
            (operation?.isActive == true || state in setOf("RecoveredReady", "Completed", "Acked"))) return
        id = uuid; mode = requested
        val previous = operation
        operation = scope.launch {
            previous?.cancelAndJoin()
            try {
                detachUi()
                withContext(Dispatchers.IO) {
                    database?.close(); database = null
                    val f = Owned(applicationContext, uuid); fixture = f
                    when (requested) {
                        "prepare" -> prepare(f)
                        "recover" -> recover(f)
                        else -> {
                            // A failed/non-ACK run may be explicitly closed and inventoried here,
                            // but its public object must still match its existing ownership proof.
                            val saved = f.read()
                            check(!saved.optBoolean("cleanupComplete"))
                            f.canRecordFailure = true
                            if (!saved.optBoolean("databaseClosed")) {
                                f.admitInterruptedInsert()
                                f.sealClosedInventory()
                            }
                            f.cleanup()
                            withContext(Dispatchers.Main) { state = "Cleaned" }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                fixture?.takeIf { it.canRecordFailure && it.hasReceipt() }?.let { f -> withContext(Dispatchers.IO) {
                    f.update { it.put("failure", failure.javaClass.name + ":" + failure.message).put("failureStack", failure.stackTraceToString().take(8192)) }
                } }
                state = "Failed"
            }
        }
    }
    private suspend fun prepare(f: Owned) {
        f.create(Process.myPid(), taskId)
        val db = PrivateAlbumDatabase.open(f.context, f.name); database = db
        val repo = PrivateAlbumRepository(f.context, db)
        val master = PrivateAlbumCrypto.generateDataKey() // Deliberately lost at process death.
        val imported = repo.importFromUri(Uri.fromFile(f.source), f.source.name, "image/png", "image", 1, 1, 0, master)
        check(imported.success)
        val mediaId = requireNotNull(imported.mediaId)
        val media = requireNotNull(db.privateMediaDao().getById(mediaId))
        val container = File(media.containerPath)
        val held = File(container.parentFile, container.name + ".held")
        val cipherSha = hash(container)
        f.update { it.put("mediaId", mediaId).put("container", container.relativeTo(f.root).invariantSeparatorsPath)
            .put("held", held.relativeTo(f.root).invariantSeparatorsPath).put("cipherSha256", cipherSha) }
        repo.privateExportCheckpoint = { receipt ->
            val uri = Uri.parse(JSONObject(requireNotNull(receipt.snapshotJson)).getString("uri"))
            if (receipt.phase == "Inserted") {
                f.captureInserted(receipt)
            }
            if (receipt.phase == "Ready") {
                val snapshot = requireNotNull(f.publicSnapshot(uri))
                check(snapshot.getString("pending") == "1" && snapshot.getString("sha256") == f.sourceSha)
                check(hash(container) == cipherSha && !present(held) && container.renameTo(held))
                PrivatePortableJournal.syncDirectory(requireNotNull(held.parentFile))
                check(hash(held) == cipherSha && !present(container))
                val serialized = JSONObject().put("id", receipt.id).put("mediaId", receipt.mediaId)
                    .put("displayName", receipt.displayName).put("mimeType", receipt.mimeType).put("mediaKind", receipt.mediaKind)
                    .put("expectedSha256", receipt.expectedSha256).put("phase", receipt.phase)
                    .put("snapshotJson", receipt.snapshotJson).put("createdAtMillis", receipt.createdAtMillis).toString()
                f.update { it.put("state", "Ready").put("readyReceiptSha256", digest(serialized.toByteArray()))
                    .put("readyReceipt", serialized).put("readySnapshot", snapshot) }
                withContext(Dispatchers.Main) { state = "Ready" }
                awaitCancellation() // Normal process death interrupts the real export before publication.
            }
        }
        repo.exportToMediaStore(mediaId, master)
        error("Prepare must remain suspended at Ready")
    }
    private suspend fun recover(f: Owned) {
        val saved = f.read()
        check(saved.getString("state") == "Ready" && saved.getBoolean("stopped"))
        check(saved.getInt("preparePid") != Process.myPid() && saved.getInt("prepareTaskId") == taskId)
        check(saved.getInt("stopPid") == saved.getInt("preparePid") && saved.getInt("stopTaskId") == saved.getInt("prepareTaskId"))
        f.canRecordFailure = true
        f.requireSources()
        check(!present(f.own(saved.getString("container")))) { "Original container must remain absent" }
        val db = PrivateAlbumDatabase.open(f.context, f.name); database = db
        val repo = PrivateAlbumRepository(f.context, db)
        val before = requireNotNull(db.privateExportDao().get(saved.getString("publicationId")))
        val review = repo.exportRecoveries().single()
        check(review.status == PrivateExportStatus.Ready && review.id == before.id && review.uri == saved.getString("outputUri"))
        check(db.privateExportDao().get(before.id) == before) { "Inspect must not update the receipt" }
        check(f.publicSnapshot(Uri.parse(requireNotNull(review.uri))).toString() == saved.getJSONObject("readySnapshot").toString())
        check(digest(saved.getString("readyReceipt").toByteArray()) == saved.getString("readyReceiptSha256"))
        val anchored = JSONObject(saved.getString("readyReceipt"))
        check(before.id == anchored.getString("id") && before.mediaId == anchored.getLong("mediaId") &&
            before.phase == "Ready" && before.snapshotJson == anchored.getString("snapshotJson") &&
            before.expectedSha256 == f.sourceSha)
        f.update { it.put("state", "RecoveredReady").put("recoverPid", Process.myPid()).put("recoverTaskId", taskId)
            .put("recoveryProof", review.proof).put("pid", Process.myPid()).put("taskId", taskId) }
        withContext(Dispatchers.Main) { uiDisposed = CompletableDeferred(); repository = repo; state = "RecoveredReady" }
    }
    private fun verify() {
        if (operation?.isActive == true) return
        val repo = repository ?: return
        val f = requireNotNull(fixture)
        operation = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    f.requireSources()
                    val saved = f.read()
                    val current = requireNotNull(f.publicSnapshot(Uri.parse(saved.getString("outputUri"))))
                    val ready = saved.getJSONObject("readySnapshot")
                    check(f.sameIdentity(ready, current) && current.getString("pending") == "0" &&
                        current.getString("trashed") == "0" && current.getString("sha256") == f.sourceSha &&
                        current.getLong("bytes") == f.bytes.size.toLong() && current.getString("modified").toLong() >= ready.getString("modified").toLong())
                    val rows = repo.exportRecoveries()
                    if (rows.isNotEmpty()) {
                        check(rows.size == 1 && rows.single().id == saved.getString("publicationId") && rows.single().status == PrivateExportStatus.Published)
                        if (saved.has("publishedSnapshot")) check(saved.getJSONObject("publishedSnapshot").toString() == current.toString())
                        f.update { it.put("state", "Completed").put("publishedSnapshot", current) }
                        withContext(Dispatchers.Main) { state = "Completed" }
                    } else {
                        check(saved.getString("state") == "Completed") { "Verify Published before explicit ACK" }
                        check(saved.getJSONObject("publishedSnapshot").toString() == current.toString())
                        f.update { it.put("state", "Acked") }
                        detachUi()
                        database?.close(); database = null
                        withContext(Dispatchers.Main) { state = "Acked" }
                        f.sealClosedInventory()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { state = "Failed"; withContext(Dispatchers.IO) {
                f.update { it.put("failure", failure.javaClass.name + ":" + failure.message).put("failureStack", failure.stackTraceToString().take(8192)) }
            } }
        }
    }

    private suspend fun detachUi() {
        val wait = withContext(Dispatchers.Main) { val mounted = uiMounted; repository = null; mounted }
        if (wait) withTimeout(10_000) { uiDisposed.await() }
    }

    private class Owned(val base: Context, val id: String) {
        @Volatile var canRecordFailure = false
        val root = File(base.cacheDir.canonicalFile, "private-export-process-$id")
        val receipt = File(base.filesDir.canonicalFile, "private-export-process-$id.json")
        val name = "private-export-process-$id.db"
        val alias = "lightforge.privatealbum.index.v1." + digest(name.toByteArray())
        val bytes = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", 0)
        val sourceSha = digest(bytes)
        val source = File(root, "source-$id.png")
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { check(isDirectory || mkdir()) }
            override fun getCacheDir() = File(root, "cache").apply { check(isDirectory || mkdir()) }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { check(isDirectory || mkdir()) }
            override fun getDatabasePath(name: String) = File(File(root, "databases").apply { check(isDirectory || mkdir()) }, name)
        }
        fun create(pid: Int, task: Int) {
            check(!present(root) && listOf(receipt, File(receipt.path + ".bak"), File(receipt.path + ".new")).none(::present))
            check(!keyStore().containsAlias(alias) && root.mkdir())
            FileOutputStream(source).use { it.write(bytes); it.fd.sync() }
            PrivatePortableJournal.syncDirectory(root)
            write(JSONObject().put("fixtureUuid", id).put("fixture", id).put("package", base.packageName).put("root", root.path)
                .put("databaseName", name).put("indexAlias", alias).put("sourceSha256", sourceSha)
                .put("preparePid", pid).put("prepareTaskId", task).put("pid", pid).put("taskId", task).put("state", "Preparing").put("stopped", false))
            canRecordFailure = true
        }
        fun hasReceipt() = present(receipt)
        fun read(): JSONObject = synchronized(receiptLock) {
            listOf(receipt, File(receipt.path + ".bak"), File(receipt.path + ".new")).filter(::present).forEach(::regular)
            val data = AtomicFile(receipt).openRead().use { input ->
                val buffer = ByteArray(64 * 1024 + 1); var count = 0
                while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                check(count <= 64 * 1024); buffer.copyOf(count)
            }
            JSONObject(data.toString(Charsets.UTF_8)).also {
                check(it.getString("fixtureUuid") == id && it.getString("package") == base.packageName &&
                    it.getString("root") == root.path && it.getString("databaseName") == name &&
                    it.getString("indexAlias") == alias && it.getString("sourceSha256") == sourceSha)
            }
        }
        fun update(change: (JSONObject) -> Unit) = synchronized(receiptLock) { val json = read(); change(json); write(json) }
        private fun write(json: JSONObject) = synchronized(receiptLock) {
            listOf(receipt, File(receipt.path + ".bak"), File(receipt.path + ".new")).filter(::present).forEach(::regular)
            val atomic = AtomicFile(receipt); val output = atomic.startWrite(); val bytes = json.toString().toByteArray()
            check(bytes.size <= 64 * 1024)
            try { output.write(bytes); output.fd.sync(); atomic.finishWrite(output) }
            catch (failure: Throwable) { atomic.failWrite(output); throw failure }
            check(atomic.openRead().use { it.readBytes() }.contentEquals(bytes))
            PrivatePortableJournal.syncDirectory(requireNotNull(receipt.parentFile))
        }
        fun requireSources() {
            regular(source); check(hash(source) == sourceSha)
            val json = read()
            if (json.has("held")) {
                val held = own(json.getString("held")); val original = own(json.getString("container"))
                val selected = if (present(held)) held else original
                regular(selected); check(hash(selected) == json.getString("cipherSha256"))
                check(!(present(held) && present(original)))
            }
        }
        fun own(relative: String): File {
            require(relative.isNotBlank() && !File(relative).isAbsolute && relative.split('/').none { it.isEmpty() || it == "." || it == ".." })
            return File(root, relative).also { check(it.canonicalFile == it.absoluteFile && it.path.startsWith(root.path + "/")) }
        }
        fun sameIdentity(a: JSONObject, b: JSONObject) = listOf("uri", "owner", "name", "path", "mime", "added")
            .all { a.getString(it) == b.getString(it) }
        private fun publicMetadata(uri: Uri): JSONObject? {
            require(Regex("content://media/external_primary/images/media/[1-9][0-9]*").matches(uri.toString()))
            val columns = arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.GENERATION_ADDED,
                MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED)
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE); putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            val result = requireNotNull(base.contentResolver.query(uri, columns, args, null)).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val json = JSONObject().put("uri", uri.toString())
                listOf("owner", "name", "path", "mime", "added", "modified", "size", "pending", "trashed").forEachIndexed { index, key ->
                    json.put(key, if (cursor.isNull(index)) JSONObject.NULL else cursor.getString(index))
                }
                check(!cursor.moveToNext()); json
            }
            return result
        }
        fun publicSnapshot(uri: Uri, inserted: JSONObject? = null): JSONObject? {
            val result = publicMetadata(uri) ?: return null
            if (inserted != null) check(metadataEqual(result, inserted) && result.getString("pending") == "1" && result.getString("trashed") == "0")
            val input = try { requireNotNull(base.contentResolver.openInputStream(uri)) }
            catch (failure: FileNotFoundException) {
                if (inserted == null) throw failure
                // Binder recreates FileNotFoundException without its cause. Its message is not evidence.
                val missingPath = proveInsertedBackingMissing(uri, inserted)
                check(publicMetadata(uri)?.toString() == result.toString())
                return result.put("sha256", JSONObject.NULL).put("bytes", JSONObject.NULL)
                    .put("backingFileMissing", true).put("missingBackingPath", missingPath)
            }
            val content = input.use {
                val buffer = ByteArray(bytes.size + 1); var size = 0
                while (size < buffer.size) { val count = it.read(buffer, size, buffer.size - size); if (count < 0) break; size += count }
                buffer.copyOf(size)
            }
            check(content.size <= bytes.size && content.contentEquals(bytes.copyOf(content.size))) { "Public bytes changed; retain output" }
            check(publicMetadata(uri)?.toString() == result.toString())
            return result.put("sha256", digest(content)).put("bytes", content.size)
        }
        private fun metadataEqual(a: JSONObject, b: JSONObject) =
            listOf("uri", "owner", "name", "path", "mime", "added", "modified", "size", "pending", "trashed")
                .all { a.isNull(it) == b.isNull(it) && (a.isNull(it) || a.getString(it) == b.getString(it)) }
        fun captureInserted(entity: PrivateExportReceiptEntity) {
            check(entity.phase == "Inserted" && entity.expectedSha256 == sourceSha && entity.mediaKind == "image" && entity.mimeType == "image/png")
            val raw = JSONObject(requireNotNull(entity.snapshotJson))
            check(raw.getBoolean("pending") && !raw.getBoolean("trashed") && raw.getString("owner") == base.packageName)
            val anchor = JSONObject().put("uri", raw.getString("uri"))
            listOf("owner", "name", "path", "mime", "added", "modified", "size").forEach {
                anchor.put(it, if (raw.isNull(it)) JSONObject.NULL else raw.get(it).toString())
            }
            anchor.put("pending", "1").put("trashed", "0")
            val uri = Uri.parse(anchor.getString("uri"))
            check(metadataEqual(requireNotNull(publicMetadata(uri)), anchor))
            // Preserve the authoritative DAO identity before attempting the as-yet-uncreated file.
            update {
                check(!it.has("outputUri") || it.getString("outputUri") == uri.toString())
                check(!it.has("insertedMetadata") || metadataEqual(it.getJSONObject("insertedMetadata"), anchor))
                check(!it.has("publicationId") || it.getString("publicationId") == entity.id)
                it.put("publicationId", entity.id).put("exportId", entity.id).put("outputUri", uri.toString()).put("insertedMetadata", anchor)
            }
            // No file read here: stageStream has not opened/created the backing file yet.
        }
        private fun proveInsertedBackingMissing(uri: Uri, anchor: JSONObject): String {
            check(anchor.getString("owner") == base.packageName && anchor.getString("pending") == "1" &&
                anchor.getString("trashed") == "0" && anchor.getString("path") == "Pictures/Lightforge/" &&
                anchor.getString("mime") == "image/png")
            val name = anchor.getString("name")
            check(Regex("Lightforge-Private-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png").matches(name))
            val expected = File(Environment.getExternalStorageDirectory(), "Pictures/Lightforge/$name")
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE); putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            @Suppress("DEPRECATION")
            val path = requireNotNull(base.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_EXPIRES), args, null)).use {
                check(it.moveToFirst() && !it.isNull(0))
                val found = it.getString(0)
                val expiry = if (it.isNull(1)) null else it.getLong(1)
                val pendingName = expiry?.takeIf { value -> value > 0 }?.let { value -> ".pending-$value-$name" }
                check(found == expected.path || (pendingName != null && found == File(expected.parentFile, pendingName).path)) {
                    "Unexpected backing path; retain output"
                }
                check(!it.moveToNext()); found
            }
            try {
                Os.lstat(path)
                error("Backing file exists; failed provider read is not absence")
            } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.ENOENT) throw failure
            }
            // Path is a read-only absence probe, never a deletion target.
            return path
        }
        suspend fun admitInterruptedInsert() {
            val saved = read()
            if (saved.has("readySnapshot") || saved.has("insertedSnapshot")) return
            requireSources()
            check(saved.has("failure") && saved.has("mediaId") && saved.has("container"))
            val dbFile = File(root, "databases/$name")
            regular(dbFile) // Never create a missing database to discover an output.
            val db = PrivateAlbumDatabase.open(context, name)
            try {
                val media = requireNotNull(db.privateMediaDao().getById(saved.getLong("mediaId")))
                check(File(media.containerPath) == own(saved.getString("container")) && media.sha256.contentEquals(MessageDigest.getInstance("SHA-256").digest(bytes)))
                val entity = requireNotNull(db.privateExportDao().getForMedia(media.id))
                check(db.privateExportDao().list() == listOf(entity) && entity.phase == "Inserted")
                captureInserted(entity)
                val anchor = read().getJSONObject("insertedMetadata")
                val observed = requireNotNull(publicSnapshot(Uri.parse(anchor.getString("uri")), anchor))
                check(observed.optBoolean("backingFileMissing") || observed.getLong("bytes") == 0L) { "Interrupted Inserted unexpectedly contains bytes" }
                check(db.privateExportDao().get(entity.id) == entity)
                update { it.put("insertedSnapshot", observed) }
            } finally { db.close() }
        }
        private fun inventory(): Pair<JSONArray, JSONArray> {
            val files = JSONArray(); val dirs = JSONArray()
            fun visit(file: File) {
                check(file.canonicalFile == file.absoluteFile)
                val mode = Os.lstat(file.path).st_mode
                val relative = if (file == root) "" else file.relativeTo(root).invariantSeparatorsPath
                if (OsConstants.S_ISDIR(mode)) { dirs.put(relative); requireNotNull(file.listFiles()).sortedBy { it.name }.forEach(::visit) }
                else { regular(file); files.put(JSONObject().put("path", relative).put("size", file.length()).put("sha256", hash(file))) }
            }
            if (present(root)) visit(root)
            return files to dirs
        }
        fun sealClosedInventory() {
            requireSources()
            val json = read()
            check(!json.optBoolean("cleanupComplete"))
            if (json.has("files")) { check(json.getBoolean("databaseClosed")); return }
            if (json.has("outputUri")) {
                val expected = when { json.has("publishedSnapshot") -> json.getJSONObject("publishedSnapshot")
                    json.has("readySnapshot") -> json.getJSONObject("readySnapshot")
                    else -> json.getJSONObject("insertedSnapshot") }
                check(publicSnapshot(Uri.parse(json.getString("outputUri")), if (!json.has("readySnapshot")) expected else null).toString() == expected.toString()) { "Output changed without verified snapshot" }
            }
            val (files, dirs) = inventory()
            update { check(!it.has("files")); it.put("files", files).put("directories", dirs).put("databaseClosed", true) }
        }
        fun cleanup() {
            val json = read(); check(json.getBoolean("databaseClosed") && !json.optBoolean("cleanupComplete"))
            val retry = json.optBoolean("cleanupStarted")
            if (!retry) requireSources()
            val (currentFiles, currentDirs) = inventory()
            val files = json.getJSONArray("files"); val dirs = json.getJSONArray("directories")
            val expected = (0 until files.length()).associate { i -> files.getJSONObject(i).let { it.getString("path") to it } }
            val expectedDirs = (0 until dirs.length()).map { dirs.getString(it) }
            check(expected.size == files.length() && expectedDirs.distinct().size == dirs.length())
            expected.keys.forEach(::own)
            expectedDirs.filter(String::isNotEmpty).forEach(::own)
            if (!retry) check(currentFiles.toString() == files.toString() && currentDirs.toString() == dirs.toString())
            for (i in 0 until currentFiles.length()) {
                val current = currentFiles.getJSONObject(i); val anchored = requireNotNull(expected[current.getString("path")])
                check(current.getLong("size") == anchored.getLong("size") && current.getString("sha256") == anchored.getString("sha256"))
            }
            for (i in 0 until currentDirs.length()) check(currentDirs.getString(i) in expectedDirs)
            var output: Pair<Uri, JSONObject?>? = null
            if (json.has("outputUri")) {
                val uri = Uri.parse(json.getString("outputUri"))
                val anchor = when { json.has("publishedSnapshot") -> json.getJSONObject("publishedSnapshot")
                    json.has("readySnapshot") -> json.getJSONObject("readySnapshot") else -> json.getJSONObject("insertedSnapshot") }
                val actual = publicSnapshot(uri, if (!json.has("readySnapshot")) anchor else null)
                check(anchor.getString("owner") == base.packageName)
                if (actual == null) check(retry) { "Output disappeared before cleanup admission" }
                else check(actual.toString() == anchor.toString()) { "Changed output must be retained" }
                output = uri to actual
            }
            if (!retry) update { it.put("cleanupStarted", true) }
            output?.let { (uri, actual) ->
                if (actual != null) {
                    val mapping = linkedMapOf("owner" to MediaStore.MediaColumns.OWNER_PACKAGE_NAME, "name" to MediaStore.MediaColumns.DISPLAY_NAME,
                        "path" to MediaStore.MediaColumns.RELATIVE_PATH, "mime" to MediaStore.MediaColumns.MIME_TYPE,
                        "added" to MediaStore.MediaColumns.GENERATION_ADDED, "modified" to MediaStore.MediaColumns.GENERATION_MODIFIED,
                        "pending" to MediaStore.MediaColumns.IS_PENDING, "trashed" to MediaStore.MediaColumns.IS_TRASHED, "size" to MediaStore.MediaColumns.SIZE)
                    val selection = mapping.entries.joinToString(" AND ") { (key, column) -> if (actual.isNull(key)) "$column IS NULL" else "$column=?" }
                    val values = mapping.keys.filterNot(actual::isNull).map(actual::getString).toTypedArray()
                    check(base.contentResolver.delete(uri, selection, values) == 1)
                }
                val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE); putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
                requireNotNull(base.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), args, null)).use { check(!it.moveToFirst()) }
                update { it.put("outputAbsent", true) }
            }
            for (i in 0 until files.length()) {
                val entry = files.getJSONObject(i); val file = own(entry.getString("path"))
                if (!present(file)) { check(retry); continue }
                regular(file)
                check(file.length() == entry.getLong("size") && hash(file) == entry.getString("sha256")); check(file.delete())
                PrivatePortableJournal.syncDirectory(requireNotNull(file.parentFile))
            }
            expectedDirs.sortedByDescending(String::length).forEach {
                val directory = if (it.isEmpty()) root else own(it)
                if (present(directory)) {
                    check(OsConstants.S_ISDIR(Os.lstat(directory.path).st_mode) && requireNotNull(directory.listFiles()).isEmpty() && directory.delete())
                    PrivatePortableJournal.syncDirectory(requireNotNull(directory.parentFile))
                } else check(retry)
            }
            check(!present(root)); keyStore().deleteEntry(alias); check(!keyStore().containsAlias(alias))
            update { it.put("cleanupComplete", true).put("state", "Cleaned") }
        }
    }
    companion object {
        private val receiptLock = Any()
        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun present(file: File): Boolean = try { Os.lstat(file.path); true }
            catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) false else throw failure }
        private fun regular(file: File) { check(OsConstants.S_ISREG(Os.lstat(file.path).st_mode) && file.canonicalFile == file.absoluteFile) }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun hash(file: File) = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
