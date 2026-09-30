package com.librestatic.lightforge.feature.localsharing

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Snapshot writes are atomic; corrupt operations remain reportable and never get cleaned blindly.
 */
class LocalSharingStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "local-sharing").apply { mkdirs() }

    internal fun directory(id: String) = File(root, "tasks/${id.also(::peerUuid)}")

    internal fun source(id: String, relative: String): File {
        val base = directory(id)
        val f = File(base, relative)
        require(f.canonicalPath.startsWith(base.canonicalPath + File.separator))
        return f
    }

    internal fun received(id: String, index: Int): File {
        require(index in 0 until LOCAL_SHARING_MAX_FILES)
        return source(id, "received-$index.partial")
    }

    internal fun lease(id: String) = File(directory(id), "lease")

    fun transfers(): List<LocalSharingTransfer> =
        synchronized(lock) {
            File(root, "tasks")
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && runCatching { peerUuid(it.name) }.isSuccess }
                .map { d ->
                    runCatching { transfer(d.name) ?: throw IOException() }
                        .getOrElse {
                            LocalSharingTransfer(
                                d.name,
                                "0".repeat(64),
                                LocalSharingDirection.Receive,
                                status = LocalSharingStatus.NeedsReview,
                                failure = "corrupt",
                            )
                        }
                }
                .sortedByDescending { it.createdAt }
        }

    fun transfer(id: String): LocalSharingTransfer? =
        synchronized(lock) {
            read(File(directory(id), "task.json"))?.let(PeerCodec::transfer)?.also {
                require(it.id == id)
            }
        }

    internal fun create(t: LocalSharingTransfer) =
        synchronized(lock) {
            require(transfer(t.id) == null)
            require(transfers().count { !it.terminal } < 24)
            directory(t.id).mkdirs()
            save(t)
        }

    internal fun update(
        id: String,
        change: (LocalSharingTransfer) -> LocalSharingTransfer,
    ): LocalSharingTransfer =
        synchronized(lock) {
            val before = transfer(id) ?: throw IOException("missing")
            val requested = change(before)
            val after =
                if (
                    requested.pauseRequested &&
                        !requested.terminal &&
                        requested.status != LocalSharingStatus.Paused
                )
                    requested.copy(
                        status = LocalSharingStatus.Paused,
                        resumeStatus = requested.resumeStatus ?: requested.status,
                    )
                else requested
            require(
                before.id == after.id &&
                    before.peerId == after.peerId &&
                    before.direction == after.direction &&
                    before.createdAt == after.createdAt &&
                    before.selection == after.selection &&
                    before.stripLocation == after.stripLocation
            )
            if (before.terminal) require(after == before)
            if (before.manifest != null) require(before.manifest == after.manifest)
            save(after)
            after
        }

    private fun save(t: LocalSharingTransfer) {
        val j = PeerCodec.transfer(t)
        require(PeerCodec.transfer(j) == t)
        write(File(directory(t.id), "task.json"), j)
    }

    fun peers(): List<LocalSharingPeer> =
        synchronized(lock) {
            read(File(root, "peers.json"))
                ?.let { j ->
                    require(j.length() <= 64)
                    j.keys()
                        .asSequence()
                        .map { k ->
                            PeerCodec.peer(j.getJSONObject(k)).also { require(k == it.id) }
                        }
                        .toList()
                }
                .orEmpty()
        }

    internal fun savePeer(p: LocalSharingPeer) =
        synchronized(lock) {
            val all = peers().filter { it.id != p.id } + p
            require(all.size <= 64)
            write(
                File(root, "peers.json"),
                JSONObject().apply { all.forEach { put(it.id, PeerCodec.peer(it)) } },
            )
        }

    internal fun secretFile(pin: String): File {
        require(peerHash(pin))
        return File(root, "secrets/$pin.bin").apply { parentFile!!.mkdirs() }
    }

    internal fun cleanupPrivate(id: String) {
        // Only our deterministic received files / private source attempts; task and receipts
        // retained.
        val t = transfer(id) ?: return
        require(t.status == LocalSharingStatus.Cancelled && t.localTaskId == null)
        directory(id)
            .listFiles()
            .orEmpty()
            .filter {
                it.name.matches(Regex("received-[0-9]+\\.partial")) ||
                    it.name.matches(Regex("attempt-[a-f0-9-]{36}"))
            }
            .forEach { file -> if (file.isDirectory) file.deleteRecursively() else file.delete() }
    }

    private fun read(file: File): JSONObject? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return AtomicFile(file).openRead().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val b = ByteArray(8192)
            var n = 0
            while (true) {
                val c = input.read(b)
                if (c < 0) break
                n += c
                require(n <= 8 * 1024 * 1024)
                out.write(b, 0, c)
            }
            JSONObject(out.toString("UTF-8"))
        }
    }

    private fun write(file: File, j: JSONObject) {
        file.parentFile!!.mkdirs()
        val a = AtomicFile(file)
        var out: FileOutputStream? = null
        try {
            out = a.startWrite()
            out.write(j.toString().toByteArray())
            a.finishWrite(out)
            revision.value++
        } catch (e: Throwable) {
            a.failWrite(out)
            throw e
        }
    }

    companion object {
        internal val lock = Any()
        internal val revision = MutableStateFlow(0L)
    }
}
