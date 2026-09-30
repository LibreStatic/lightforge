package com.librestatic.lightforge.feature.ownsync

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.librestatic.lightforge.core.remotestorage.*
import java.io.*
import java.util.concurrent.ConcurrentHashMap

internal class OwnSyncFixtureContext(base: Context) : ContextWrapper(base) {
    private val directory = File(base.cacheDir, "own-sync-test-${ownSyncUuid()}").apply { mkdirs() }

    override fun getFilesDir() = directory

    override fun getApplicationContext(): Context = this

    fun clean() {
        directory.deleteRecursively()
    }
}

internal class OwnSyncFixtureSource : OwnSyncSourcePort {
    val files =
        linkedMapOf<List<String>, ByteArray>(
            listOf("nested", "same.png") to byteArrayOf(1, 2, 3),
            listOf("another", "same.png") to byteArrayOf(4, 5, 6),
        )
    var incomplete = false
    var revoked = false

    override fun retain(jobId: String, tree: Uri) {
        require(tree.toString() == Tree)
        revoked = false
    }

    override suspend fun scan(tree: Uri, check: () -> Unit): OwnSyncSnapshot {
        if (revoked) throw SecurityException()
        val entries =
            files.map { (path, bytes) ->
                check()
                OwnSyncSourceEntry(
                    ownSyncHash(path.joinToString("/").toByteArray()),
                    "content://fixture/" + path.joinToString("/"),
                    path,
                    "image/png",
                    RemoteDigest(bytes.size.toLong(), ownSyncHash(bytes)),
                    1,
                )
            }
        return OwnSyncSnapshot(
            entries,
            if (incomplete) listOf("loading") else emptyList(),
            files.keys.map { it.dropLast(1) }.distinct(),
        )
    }

    override fun open(entry: OwnSyncSourceEntry): InputStream {
        if (revoked) throw SecurityException()
        return files[entry.path]!!.inputStream()
    }

    companion object {
        const val Tree = "content://fixture/tree/root"
    }
}

internal class OwnSyncFixtureRemote {
    val files = ConcurrentHashMap<String, ByteArray>()
    val folders = mutableSetOf("")
    var writes = 0
    var moves = 0
    var onWrite: (() -> Unit)? = null
    var afterMove: (() -> Unit)? = null
    var corruptReadback = false

    fun connection(prefix: List<String> = emptyList()): RemoteManagedConnection =
        object : RemoteManagedConnection {
            override val capabilities = RemoteCapabilities(true, true, true)
            override val identity = "fixture"

            fun key(name: String) = (prefix + name).joinToString("/")

            override fun directory(
                segments: List<String>,
                create: Boolean,
            ): RemoteManagedConnection {
                var current = prefix
                segments.forEach {
                    RemoteNames.requireChild(it)
                    current = current + it
                    val path = current.joinToString("/")
                    if (files.containsKey(path))
                        throw RemoteStorageException(RemoteFailure.CONFLICT)
                    if (path !in folders) {
                        if (!create) throw RemoteStorageException(RemoteFailure.NOT_FOUND)
                        folders += path
                    }
                }
                return connection(current)
            }

            override fun list(limit: Int): List<RemoteEntry> =
                files
                    .filterKeys { it.substringBeforeLast('/', "") == prefix.joinToString("/") }
                    .map { (path, bytes) ->
                        RemoteEntry(path.substringAfterLast('/'), bytes.size.toLong(), 1, true)
                    }
                    .also { require(it.size <= limit) }

            override fun stat(name: String): RemoteEntry? =
                files[key(name)]?.let { RemoteEntry(name, it.size.toLong(), 1, true) }
                    ?: if (key(name) in folders) RemoteEntry(name, 0, 1, false) else null

            override fun openRead(name: String, offset: Long): InputStream {
                val bytes =
                    files[key(name)] ?: throw RemoteStorageException(RemoteFailure.NOT_FOUND)
                return bytes.drop(offset.toInt()).toByteArray().inputStream()
            }

            override fun createExclusive(name: String): OutputStream {
                if (files.putIfAbsent(key(name), byteArrayOf()) != null)
                    throw RemoteStorageException(RemoteFailure.ALREADY_EXISTS)
                writes++
                return object : ByteArrayOutputStream() {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        super.write(b, off, len)
                        files[key(name)] = toByteArray()
                        onWrite?.invoke()
                    }

                    override fun close() {
                        files[key(name)] = toByteArray()
                        super.close()
                    }
                }
            }

            override fun publishNoReplace(
                staging: String,
                destination: String,
                expected: RemoteDigest,
            ) {
                val bytes = files[key(staging)] ?: throw IOException()
                require(RemoteDigest(bytes.size.toLong(), ownSyncHash(bytes)) == expected)
                if (
                    files.putIfAbsent(
                        key(destination),
                        if (corruptReadback) byteArrayOf(99) else bytes.copyOf(),
                    ) != null
                )
                    throw RemoteStorageException(RemoteFailure.ALREADY_EXISTS)
            }

            override fun moveManagedNoReplace(
                source: String,
                destination: String,
                expected: RemoteDigest,
            ): RemoteManagedMove {
                val before = files[key(source)]
                val after = files[key(destination)]
                fun matches(b: ByteArray?) =
                    b != null && RemoteDigest(b.size.toLong(), ownSyncHash(b)) == expected
                if (before == null && matches(after))
                    return RemoteManagedMove(
                        RemoteManagedMoveState.AlreadyMoved,
                        source,
                        destination,
                        expected,
                    )
                if (!matches(before))
                    return RemoteManagedMove(
                        RemoteManagedMoveState.SourceChanged,
                        source,
                        destination,
                    )
                if (after != null)
                    return RemoteManagedMove(
                        RemoteManagedMoveState.TargetOccupied,
                        source,
                        destination,
                    )
                files[key(destination)] = before!!
                files.remove(key(source))
                moves++
                afterMove?.invoke()
                return RemoteManagedMove(
                    RemoteManagedMoveState.VerifiedMoved,
                    source,
                    destination,
                    expected,
                )
            }

            override fun close() {}
        }
}

internal fun ownSyncFixtureServices(
    source: OwnSyncSourcePort,
    remote: OwnSyncFixtureRemote,
): OwnSyncServices {
    val profile =
        RemoteProfile(
            id = "11111111-1111-1111-1111-111111111111",
            name = "Fixture",
            protocol = RemoteProtocol.SMB,
            host = "fixture",
            username = "fixture",
            root = "",
            share = "data",
        )
    return OwnSyncServices(
        { listOf(profile) },
        RemoteConnectionFactory { _, _, _ -> remote.connection() },
        object : RemoteCredentialVault {
            override fun save(profileId: String, credentials: RemoteCredentials) {}

            override fun load(profileId: String) = RemoteCredentials.Password(charArrayOf('x'))

            override fun delete(profileId: String) {}
        },
        { true },
        { source },
    )
}
