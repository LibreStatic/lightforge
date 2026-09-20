package com.ugallery.feature.places

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.CancellationSignal
import android.os.StatFs
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.crypto.digests.Blake3Digest
import org.json.JSONArray
import org.json.JSONObject

/** Application-lived controller. Worker delegates run(id); UI never owns download lifetime. */
class OfflinePlacesController(
    context: Context,
    private val schedule: (String) -> Unit = {},
    private val sourceAccess: OfflineMapSourceAccess = AndroidOfflineMapSourceAccess(context),
    private val factsProvider: (() -> OfflineMapDeviceFacts)? = null,
    storageDirectory: File? = null,
) {
    private val context = context.applicationContext
    val directory =
        (storageDirectory ?: File(this.context.filesDir, "offline-maps")).apply { mkdirs() }
    private val state = AtomicFile(File(directory, "state.json"))
    private val _packs = MutableStateFlow<List<OfflineMapPackage>>(emptyList())
    private val _tasks = MutableStateFlow<List<OfflineMapTask>>(emptyList())
    private val _failure = MutableStateFlow(false)
    val packs = _packs.asStateFlow()
    val tasks = _tasks.asStateFlow()
    val storageFailure = _failure.asStateFlow()

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        refresh()
        observerScope.launch { changes.collect { refresh() } }
        observerScope.launch { transferLock.withLock { runCatching { cleanupRetired() } } }
    }

    fun close() {
        observerScope.cancel()
    }

    private fun read(): JSONObject =
        if (state.baseFile.exists()) {
            require(state.baseFile.length() <= 2 * 1024 * 1024) { "JOURNAL" }
            JSONObject(state.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).also {
                require(it.getInt("version") == 1)
            }
        } else JSONObject().put("version", 1).put("packs", JSONArray()).put("tasks", JSONArray())

    private fun write(data: JSONObject) {
        require(data.toString().toByteArray().size <= 2 * 1024 * 1024) { "JOURNAL" }
        val stream = state.startWrite()
        try {
            stream.write(data.toString().toByteArray())
            state.finishWrite(stream)
        } catch (t: Throwable) {
            state.failWrite(stream)
            throw t
        }
    }

    private fun refreshLocked() {
        val d = read()
        _packs.value = list(d.getJSONArray("packs"), ::decodePack)
        _tasks.value = list(d.getJSONArray("tasks"), ::decodeTask)
        _failure.value = false
    }

    fun refresh() {
        synchronized(journalLock) {
            try {
                refreshLocked()
            } catch (e: Exception) {
                _failure.value = true
            }
        }
    }

    private fun <T> list(array: JSONArray, decode: (JSONObject) -> T): List<T> {
        require(array.length() <= 1000)
        return (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }

    private fun mutate(block: (JSONObject) -> Unit) {
        synchronized(journalLock) {
            val d = read()
            block(d)
            write(d)
            refreshLocked()
            changes.value = changes.value + 1
        }
    }

    private fun task(id: String): OfflineMapTask =
        synchronized(journalLock) {
            list(read().getJSONArray("tasks"), ::decodeTask).single { it.id == id }
        }

    private fun update(id: String, block: (OfflineMapTask) -> OfflineMapTask) = mutate { d ->
        val all = list(d.getJSONArray("tasks"), ::decodeTask)
        require(all.any { it.id == id })
        d.put("tasks", JSONArray(all.map { encodeTask(if (it.id == id) block(it) else it) }))
    }

    /** A delayed provider/HTTP response never overwrites a concurrent user stop. */
    private fun updateActive(
        id: String,
        allowWaiting: Boolean = false,
        block: (OfflineMapTask) -> OfflineMapTask,
    ) =
        update(id) {
            if (
                it.status in
                    setOf(
                        OfflineMapTaskStatus.Paused,
                        OfflineMapTaskStatus.Cancelled,
                        OfflineMapTaskStatus.Installed,
                        OfflineMapTaskStatus.Failed,
                        OfflineMapTaskStatus.ReadyForReview,
                    ) || (!allowWaiting && it.status in stopped)
            )
                throw Stopped()
            block(it)
        }

    fun deviceFacts(): OfflineMapDeviceFacts {
        factsProvider?.let {
            return it()
        }
        val am = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memory)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.getNetworkCapabilities(cm.activeNetwork)
        val battery = context.getSystemService(BatteryManager::class.java)
        return OfflineMapDeviceFacts(
            StatFs(directory.path).availableBytes,
            memory.totalMem,
            am.isLowRamDevice,
            am.deviceConfigurationInfo.reqGlEsVersion >= 0x00030000,
            net?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            battery.isCharging,
        )
    }

    suspend fun importPackage(uri: Uri, name: String, replaceId: String? = null): String =
        withContext(Dispatchers.IO) {
            require(uri.scheme == "content" && name.isNotBlank() && name.length <= 120)
            if (replaceId != null) require(packs.value.any { it.id == replaceId })
            val id = UUID.randomUUID().toString()
            grantLock.withLock {
                val before = sourceAccess.hasRead(uri)
                val job =
                    OfflineMapTask(
                        id,
                        name,
                        uri.toString(),
                        false,
                        status = OfflineMapTaskStatus.Queued,
                        replaceId = replaceId,
                        // Write-ahead exact-source ownership: recovery can release a take completed
                        // immediately before process death. Previously held grants are never
                        // claimed.
                        ownsReadGrant = !before,
                    )
                mutate { d ->
                    val all = d.getJSONArray("tasks")
                    require(all.length() < 1000)
                    all.put(encodeTask(job))
                }
                try {
                    currentActive(id)
                    sourceAccess.retainRead(uri)
                    // No Queued rewrite here: pause/cancel may have arrived while take blocked.
                } catch (_: Stopped) {
                    // Schedule exact-owned cleanup below; never reactivate the durable stop.
                } catch (e: SecurityException) {
                    update(id) {
                        if (it.status in stopped || it.status in terminal) it
                        else
                            it.copy(
                                status = OfflineMapTaskStatus.WaitingPermission,
                                failure = "PERMISSION",
                            )
                    }
                }
            }
            schedule(id)
            id
        }

    suspend fun downloadWorld(confirmed: Boolean): String =
        withContext(Dispatchers.IO) {
            require(confirmed) { "CONSENT" }
            val edition = OfflineMapCatalog.World
            require(
                tasks.value.none {
                    it.world &&
                        it.status !in terminal &&
                        it.status != OfflineMapTaskStatus.Cancelled
                }
            ) {
                "DUPLICATE"
            }
            val id = UUID.randomUUID().toString()
            val job =
                OfflineMapTask(
                    id,
                    edition.id,
                    edition.url,
                    true,
                    total = edition.bytes,
                    approved = true,
                )
            mutate { d ->
                val all = d.getJSONArray("tasks")
                require(all.length() < 1000)
                all.put(encodeTask(job))
            }
            schedule(id)
            id
        }

    suspend fun review(id: String): OfflineMapInspection =
        withContext(Dispatchers.IO) {
            require(task(id).status == OfflineMapTaskStatus.ReadyForReview)
            inspectCancellable(File(directory, "$id.partial"), id)
        }

    fun confirmImport(id: String) {
        update(id) {
            require(!it.world && it.status == OfflineMapTaskStatus.ReadyForReview)
            it.copy(approved = true, status = OfflineMapTaskStatus.Queued)
        }
        schedule(id)
    }

    fun pause(id: String) {
        update(id) {
            if (it.status in terminal) it else it.copy(status = OfflineMapTaskStatus.Paused)
        }
    }

    fun cancel(id: String) {
        update(id) {
            if (it.status == OfflineMapTaskStatus.Installed) it
            else it.copy(status = OfflineMapTaskStatus.Cancelled)
        }
        schedule(id)
    }

    fun resume(id: String) {
        update(id) {
            require(
                it.status != OfflineMapTaskStatus.Installed &&
                    it.status != OfflineMapTaskStatus.Cancelled
            )
            it.copy(status = OfflineMapTaskStatus.Queued, failure = "")
        }
        schedule(id)
    }

    suspend fun regrant(id: String, uri: Uri): String {
        val before = task(id)
        require(!before.world && before.status == OfflineMapTaskStatus.WaitingPermission)
        // New URI is an explicit new import; the old source was never verified and is not
        // substituted silently.
        cancel(id)
        return importPackage(uri, before.name, before.replaceId)
    }

    suspend fun removePackage(id: String) =
        withContext(Dispatchers.IO) {
            transferLock.withLock {
                val old = packs.value.single { it.id == id }
                mutate { d ->
                    retire(d, listOf(old.fileName))
                    d.put(
                        "packs",
                        JSONArray(
                            list(d.getJSONArray("packs"), ::decodePack)
                                .filterNot { it.id == id }
                                .map(::encodePack)
                        ),
                    )
                }
                cleanupRetired()
            }
        }

    fun file(pack: OfflineMapPackage): File {
        require(pack.fileName.matches(Regex("[a-f0-9-]{36}\\.(pmtiles|mbtiles)")))
        return File(directory, pack.fileName)
    }

    /**
     * Returns the resulting durable state; scheduling interruptions do not become user
     * cancellation.
     */
    suspend fun run(id: String): OfflineMapTaskStatus =
        withContext(Dispatchers.IO) {
            transferLock.withLock {
                var job = task(id)
                val staging = File(directory, "$id.partial")
                require(OfflineMapCatalog.validId(id))
                if (job.status == OfflineMapTaskStatus.Cancelled) {
                    staging.delete()
                    File(directory, "$id.pmtiles").delete()
                    File(directory, "$id.mbtiles").delete()
                    releaseGrant(job)
                    return@withLock job.status
                }
                if (
                    job.status == OfflineMapTaskStatus.Paused ||
                        job.status == OfflineMapTaskStatus.Installed ||
                        job.status == OfflineMapTaskStatus.ReadyForReview
                )
                    return@withLock job.status
                try {
                    updateActive(id, allowWaiting = true) {
                        it.copy(status = OfflineMapTaskStatus.Queued)
                    }
                    // Recover the publication-before-receipt crash from this task's own unique
                    // final file.
                    val pendingFinal =
                        listOf(File(directory, "$id.pmtiles"), File(directory, "$id.mbtiles"))
                            .singleOrNull { it.isFile }
                    if (pendingFinal != null && !staging.exists())
                        require(pendingFinal.renameTo(staging)) { "PUBLISH" }
                    if (job.world) download(job, staging)
                    else if (!job.approved || !staging.exists()) copy(job, staging)
                    job = task(id)
                    if (job.status in stopped) return@withLock job.status
                    updateActive(id) { it.copy(status = OfflineMapTaskStatus.Verifying) }
                    val inspected = inspectCancellable(staging, id)
                    if (!job.world && !job.approved) {
                        updateActive(id) { it.copy(status = OfflineMapTaskStatus.ReadyForReview) }
                        return@withLock OfflineMapTaskStatus.ReadyForReview
                    }
                    val sha = digest(staging, job.world, id)
                    currentActive(id)
                    val ext = if (inspected.format.name.startsWith("PM")) "pmtiles" else "mbtiles"
                    val name = "$id.$ext"
                    val installed = File(directory, name)
                    require(!installed.exists()) { "COLLISION" }
                    require(staging.renameTo(installed)) { "PUBLISH" }
                    // A crash here leaves owned bytes but no false Installed receipt; replay
                    // revalidates them below.
                    val pack =
                        OfflineMapPackage(
                            id,
                            job.name,
                            name,
                            inspected.format,
                            installed.length(),
                            sha,
                            inspected.bounds,
                            inspected.minZoom,
                            inspected.maxZoom,
                            inspected.schema,
                            inspected.attribution,
                            System.currentTimeMillis(),
                            job.source,
                            if (job.world) OfflineMapCatalog.World.schemaVersion else "",
                        )
                    mutate { d ->
                        val latest =
                            list(d.getJSONArray("tasks"), ::decodeTask).single { it.id == id }
                        require(latest.status !in stopped) { "STOPPED" }
                        val prior = list(d.getJSONArray("packs"), ::decodePack)
                        retire(d, prior.filter { it.id == job.replaceId }.map { it.fileName })
                        d.put(
                            "packs",
                            JSONArray(
                                (prior.filterNot { it.id == job.replaceId } + pack).map(
                                    ::encodePack
                                )
                            ),
                        )
                        d.put(
                            "tasks",
                            JSONArray(
                                list(d.getJSONArray("tasks"), ::decodeTask).map {
                                    encodeTask(
                                        if (it.id == id)
                                            it.copy(
                                                status = OfflineMapTaskStatus.Installed,
                                                copied = installed.length(),
                                                total = installed.length(),
                                                failure = "",
                                            )
                                        else it
                                    )
                                }
                            ),
                        )
                    }
                } catch (e: Stopped) {
                    /* state already durable */
                } catch (e: CancellationException) {
                    update(id) {
                        if (it.status in stopped) it
                        else it.copy(status = OfflineMapTaskStatus.Queued)
                    }
                    throw e
                } catch (e: Exception) {
                    update(id) {
                        if (it.status in stopped) it
                        else
                            it.copy(
                                status =
                                    if (e is SecurityException)
                                        OfflineMapTaskStatus.WaitingPermission
                                    else OfflineMapTaskStatus.Failed,
                                failure =
                                    when (e) {
                                        is SecurityException -> "PERMISSION"
                                        else ->
                                            e.message?.takeIf { text -> text in errorCodes } ?: "IO"
                                    },
                            )
                    }
                }
                runCatching { cleanupRetired() }
                val last = task(id)
                if (last.status == OfflineMapTaskStatus.Cancelled) {
                    staging.delete()
                    File(directory, "$id.pmtiles").delete()
                    File(directory, "$id.mbtiles").delete()
                }
                if (last.status in terminal) releaseGrant(last)
                last.status
            }
        }

    /** A sibling monitor interrupts Android SQLite itself, not only the next copy chunk. */
    private suspend fun inspectCancellable(file: File, id: String): OfflineMapInspection =
        coroutineScope {
            val signal = CancellationSignal()
            val monitor =
                launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            currentActive(id)
                            delay(50)
                        }
                    } catch (_: Stopped) {
                        signal.cancel()
                    } finally {
                        signal.cancel()
                    }
                }
            try {
                val result = OfflineMapValidator.inspect(file, signal)
                currentCoroutineContext().ensureActive()
                currentActive(id)
                result
            } catch (e: android.os.OperationCanceledException) {
                currentCoroutineContext().ensureActive()
                currentActive(id)
                throw e
            } finally {
                monitor.cancel()
            }
        }

    private fun retire(data: JSONObject, names: List<String>) {
        val previous = data.optJSONArray("retired") ?: JSONArray()
        val all = ((0 until previous.length()).map { previous.getString(it) } + names).distinct()
        require(all.size <= 1000) { "JOURNAL" }
        all.forEach { require(it.matches(Regex("[a-f0-9-]{36}\\.(pmtiles|mbtiles)"))) }
        data.put("retired", JSONArray(all))
    }

    /**
     * Retired bytes are journaled in the same receipt transaction; crash replay is exact-name only.
     */
    private fun cleanupRetired() {
        synchronized(journalLock) {
            val data = read()
            val retired = data.optJSONArray("retired") ?: return
            val referenced =
                list(data.getJSONArray("packs"), ::decodePack).map { it.fileName }.toSet()
            val remaining =
                (0 until retired.length())
                    .map { retired.getString(it) }
                    .filter { name ->
                        require(name.matches(Regex("[a-f0-9-]{36}\\.(pmtiles|mbtiles)")))
                        require(name !in referenced) { "JOURNAL" }
                        val old = File(directory, name)
                        old.exists() && !old.delete()
                    }
            if (remaining.size != retired.length()) {
                data.put("retired", JSONArray(remaining))
                write(data)
            }
        }
    }

    private suspend fun releaseGrant(job: OfflineMapTask) {
        if (job.world) return
        grantLock.withLock {
            synchronized(journalLock) {
                val related =
                    list(read().getJSONArray("tasks"), ::decodeTask).filter {
                        !it.world && it.source == job.source
                    }
                if (related.any { it.status !in terminal } || related.none { it.ownsReadGrant })
                    return
                val uri = Uri.parse(job.source)
                try {
                    if (sourceAccess.hasRead(uri)) sourceAccess.releaseRead(uri)
                    mutate { d ->
                        d.put(
                            "tasks",
                            JSONArray(
                                list(d.getJSONArray("tasks"), ::decodeTask).map {
                                    encodeTask(
                                        if (it.source == job.source) it.copy(ownsReadGrant = false)
                                        else it
                                    )
                                }
                            ),
                        )
                    }
                } catch (_: SecurityException) {
                    /* already revoked, never acquire additional rights here */
                }
            }
        }
    }

    private fun currentActive(id: String) {
        if (task(id).status in stopped) throw Stopped()
    }

    private fun admit(job: OfflineMapTask, remaining: Long) {
        val waiting = OfflineMapEligibility.waiting(deviceFacts(), remaining, job.world)
        if (waiting != null) {
            updateActive(job.id) { it.copy(status = waiting) }
            throw Stopped()
        }
    }

    private suspend fun copy(job: OfflineMapTask, staging: File) {
        currentActive(job.id)
        sourceAccess.open(Uri.parse(job.source)).use { afd ->
            val size = afd.bytes
            require(size < 0 || size <= OfflineMapCatalog.MaxPackageBytes) { "LIMIT" }
            admit(job, size.coerceAtLeast(0))
            updateActive(job.id) {
                it.copy(
                    status = OfflineMapTaskStatus.Copying,
                    total = size.coerceAtLeast(0),
                    copied = 0,
                )
            }
            afd.stream.use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var n: Int
                    var count = 0L
                    var checkpoint = 0L
                    while (input.read(buffer).also { n = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        currentActive(job.id)
                        require(count + n <= OfflineMapCatalog.MaxPackageBytes) { "LIMIT" }
                        admit(job, if (size >= 0) size - count else n.toLong())
                        output.write(buffer, 0, n)
                        count += n
                        if (count - checkpoint >= 1024 * 1024) {
                            updateActive(job.id) { it.copy(copied = count) }
                            checkpoint = count
                        }
                    }
                    output.fd.sync()
                    require(size < 0 || count == size) { "SIZE" }
                    updateActive(job.id) { it.copy(copied = count, total = count) }
                }
            }
        }
    }

    private suspend fun download(job: OfflineMapTask, staging: File) {
        val edition = OfflineMapCatalog.World
        require(job.source == edition.url && job.total == edition.bytes)
        val offset = staging.takeIf { it.isFile }?.length() ?: 0L
        require(offset <= edition.bytes) { "SIZE" }
        admit(job, edition.bytes - offset)
        if (offset == edition.bytes) return
        val connection = URL(edition.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Range", "bytes=$offset-")
        if (job.etag.isNotBlank()) connection.setRequestProperty("If-Match", job.etag)
        try {
            require(connection.responseCode == 206) { "SOURCE_CHANGED" }
            require(
                connection.getHeaderField("Content-Range") ==
                    "bytes $offset-${edition.bytes-1}/${edition.bytes}"
            ) {
                "SOURCE_CHANGED"
            }
            val etag = connection.getHeaderField("ETag") ?: error("SOURCE_CHANGED")
            require(job.etag.isBlank() || job.etag == etag) { "SOURCE_CHANGED" }
            updateActive(job.id) {
                it.copy(status = OfflineMapTaskStatus.Downloading, copied = offset, etag = etag)
            }
            connection.inputStream.use { input ->
                FileOutputStream(staging, true).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var n: Int
                    var count = offset
                    var checkpoint = offset
                    while (input.read(buffer).also { n = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        currentActive(job.id)
                        admit(job, edition.bytes - count)
                        require(count + n <= edition.bytes) { "SIZE" }
                        output.write(buffer, 0, n)
                        count += n
                        if (count - checkpoint >= 4 * 1024 * 1024) {
                            updateActive(job.id) { it.copy(copied = count) }
                            checkpoint = count
                        }
                    }
                    output.fd.sync()
                    require(count == edition.bytes) { "SIZE" }
                    updateActive(job.id) { it.copy(copied = count) }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun digest(file: File, world: Boolean, id: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val b3 = if (world) Blake3Digest() else null
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                currentCoroutineContext().ensureActive()
                currentActive(id)
                sha.update(buffer, 0, n)
                b3?.update(buffer, 0, n)
            }
        }
        if (b3 != null) {
            val value = ByteArray(32)
            b3.doFinal(value, 0)
            require(hex(value) == OfflineMapCatalog.World.blake3) { "HASH" }
        }
        return hex(sha.digest())
    }

    private class Stopped : Exception()

    companion object {
        private val journalLock = Any()
        private val changes = MutableStateFlow(0L)
        private val transferLock = Mutex()
        private val grantLock = Mutex()
        private val terminal = setOf(OfflineMapTaskStatus.Installed, OfflineMapTaskStatus.Cancelled)
        private val stopped =
            setOf(
                OfflineMapTaskStatus.Paused,
                OfflineMapTaskStatus.Cancelled,
                OfflineMapTaskStatus.WaitingWifi,
                OfflineMapTaskStatus.WaitingCharging,
                OfflineMapTaskStatus.WaitingStorage,
                OfflineMapTaskStatus.WaitingHardware,
                OfflineMapTaskStatus.WaitingPermission,
            )
        private val errorCodes =
            setOf(
                "FORMAT",
                "VERSION",
                "BOUNDS",
                "EMPTY",
                "ZOOM",
                "COMPRESSION",
                "LIMIT",
                "DIRECTORY",
                "SCHEMA",
                "TILE",
                "CORRUPT",
                "SIZE",
                "PUBLISH",
                "COLLISION",
                "SOURCE_CHANGED",
                "HASH",
            )

        private fun hex(bytes: ByteArray) =
            bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun bounds(b: PlaceBounds) = JSONArray(listOf(b.west, b.south, b.east, b.north))

        private fun decodeBounds(a: JSONArray) =
            PlaceBounds(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3))

        private fun encodePack(p: OfflineMapPackage) =
            JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("file", p.fileName)
                .put("format", p.format.name)
                .put("bytes", p.bytes)
                .put("sha", p.sha256)
                .put("bounds", bounds(p.bounds))
                .put("min", p.minZoom)
                .put("max", p.maxZoom)
                .put("schema", p.schema)
                .put("attribution", p.attribution)
                .put("installed", p.installedAt)
                .put("source", p.source)
                .put("edition", p.edition)

        private fun decodePack(j: JSONObject) =
            OfflineMapPackage(
                j.getString("id"),
                j.getString("name"),
                j.getString("file"),
                OfflineMapFormat.valueOf(j.getString("format")),
                j.getLong("bytes"),
                j.getString("sha"),
                decodeBounds(j.getJSONArray("bounds")),
                j.getInt("min"),
                j.getInt("max"),
                j.getString("schema"),
                j.getString("attribution"),
                j.getLong("installed"),
                j.getString("source"),
                j.optString("edition"),
            )

        private fun encodeTask(t: OfflineMapTask) =
            JSONObject()
                .put("id", t.id)
                .put("name", t.name)
                .put("source", t.source)
                .put("world", t.world)
                .put("status", t.status.name)
                .put("copied", t.copied)
                .put("total", t.total)
                .put("failure", t.failure)
                .put("replace", t.replaceId)
                .put("etag", t.etag)
                .put("ownsRead", t.ownsReadGrant)
                .put("approved", t.approved)

        private fun decodeTask(j: JSONObject) =
            OfflineMapTask(
                j.getString("id"),
                j.getString("name"),
                j.getString("source"),
                j.getBoolean("world"),
                OfflineMapTaskStatus.valueOf(j.getString("status")),
                j.getLong("copied"),
                j.getLong("total"),
                j.getString("failure"),
                if (j.isNull("replace")) null else j.getString("replace"),
                j.optString("etag"),
                j.optBoolean("ownsRead"),
                j.optBoolean("approved"),
            )
    }
}
