package com.ugallery.core.remotestorage.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.mssmb2.*
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.paths.PathResolver
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.ugallery.core.remotestorage.*
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** A dedicated encrypted connection; no pooling across workers or implicit DFS/symlink routing. */
class SmbRemoteConnectionFactory(private val timeoutMillis: Int = 15000) : RemoteConnectionFactory {
    init {
        require(timeoutMillis in 100..120000)
    }

    override fun connect(
        profile: RemoteProfile,
        credentials: RemoteCredentials,
        cancellation: RemoteCancellation,
    ): RemoteConnection {
        var client: SMBClient? = null
        val sockets = CancellableSockets(cancellation, timeoutMillis)
        try {
            profile.validate()
            require(profile.protocol == RemoteProtocol.SMB)
            val password =
                credentials as? RemoteCredentials.Password
                    ?: throw RemoteStorageException(RemoteFailure.UNSUPPORTED)
            val root = SmbPaths.root(profile.root)
            cancellation.check()
            val config =
                SmbConfig.builder()
                    .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0)
                    .withSigningEnabled(true)
                    .withSigningRequired(true)
                    .withEncryptData(true)
                    .withDfsEnabled(false)
                    .withDirectoryLeasingEnabled(false)
                    .withSocketFactory(sockets)
                    .withTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                    .withSoTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                    .build()
            client = SMBClient(config)
            val connection = client.connect(profile.host, profile.port)
            val auth = AuthenticationContext(profile.username, password.value, profile.domain)
            val session =
                try {
                    connection.authenticate(auth)
                } finally {
                    auth.password.fill('\u0000')
                }
            if (session.isGuest || session.isAnonymous || !session.shouldEncryptData()) {
                throw RemoteStorageException(RemoteFailure.UNSUPPORTED)
            }
            val original =
                session.connectShare(profile.share) as? DiskShare
                    ?: throw RemoteStorageException(RemoteFailure.UNSUPPORTED)
            if (original.treeConnect.isDfsShare)
                throw RemoteStorageException(RemoteFailure.UNSUPPORTED)
            // Connection's default resolver follows symlinks even when DFS is disabled.
            val confined = DiskShare(original.smbPath, original.treeConnect, PathResolver.LOCAL)
            return SmbRemoteConnection(
                    client,
                    sockets,
                    confined,
                    root,
                    cancellation,
                    "SMB " +
                        connection.negotiatedProtocol.dialect.name +
                        "; encryption required; signing required",
                )
                .also { it.pinRoot() }
        } catch (error: Exception) {
            sockets.close()
            runCatching { client?.close() }
            throw mapped(error, cancellation)
        } finally {
            credentials.close()
        }
    }
}

internal object SmbPaths {
    fun root(value: String): String {
        require(!value.startsWith("\\\\") && ':' !in value)
        val components = value.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        components.forEach { RemoteNames.requireChild(it) }
        return components.joinToString("\\")
    }

    fun child(root: String, name: String): String =
        if (root.isEmpty()) RemoteNames.requireChild(name)
        else root + "\\" + RemoteNames.requireChild(name)
}

private class CancellableSockets(
    private val cancellation: RemoteCancellation,
    private val timeout: Int,
) : SocketFactory(), Closeable {
    private val sockets = mutableListOf<Socket>()

    override fun createSocket(): Socket =
        Socket().also {
            cancellation.register(it)
            synchronized(sockets) { sockets.add(it) }
        }

    private fun connected(address: InetSocketAddress, local: InetSocketAddress? = null): Socket {
        val socket = createSocket()
        try {
            if (local != null) socket.bind(local)
            cancellation.check()
            socket.connect(address, timeout)
            return socket
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    override fun createSocket(host: String, port: Int): Socket =
        connected(InetSocketAddress(host, port))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        connected(InetSocketAddress(host, port))

    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(local, localPort))

    override fun createSocket(
        host: InetAddress,
        port: Int,
        local: InetAddress,
        localPort: Int,
    ): Socket = connected(InetSocketAddress(host, port), InetSocketAddress(local, localPort))

    override fun close() {
        synchronized(sockets) { sockets.toList().also { sockets.clear() } }
            .forEach {
                runCatching { it.close() }
                cancellation.unregister(it)
            }
    }
}

private class SmbRemoteConnection(
    private val client: SMBClient,
    private val sockets: CancellableSockets,
    private val share: DiskShare,
    private val root: String,
    private val cancellation: RemoteCancellation,
    override val identity: String,
    private val owner: SmbRemoteConnection? = null,
) : RemoteManagedConnection {
    override val capabilities =
        RemoteCapabilities(atomicPublish = true, encrypted = true, signed = true)
    private val roots = mutableListOf<Directory>()
    private val partialNames = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    override val residualNames: List<String>
        get() = synchronized(partialNames) { partialNames.toList() }

    @Volatile private var closed = false

    fun pinRoot() = guarded {
        val paths = mutableListOf("")
        if (root.isNotEmpty()) {
            var path = ""
            root.split('\\').forEach { part ->
                path = if (path.isEmpty()) part else "$path\\$part"
                paths.add(path)
            }
        }
        // Keep directory identities pinned against delete/rename for the lifetime of this
        // connection.
        paths.forEach { path ->
            val dir =
                share.openDirectory(
                    path,
                    setOf(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
                    null,
                    setOf(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    setOf(
                        SMB2CreateOptions.FILE_DIRECTORY_FILE,
                        SMB2CreateOptions.FILE_OPEN_REPARSE_POINT,
                    ),
                )
            roots.add(dir)
            checkAttributes(dir.fileInformation)
        }
    }

    private fun checkAttributes(info: FileAllInformation) {
        if (
            info.basicInformation.fileAttributes and
                FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L
        )
            throw RemoteStorageException(RemoteFailure.INVALID_PATH)
    }

    private fun open(
        name: String,
        write: Boolean = false,
        rename: Boolean = false,
        create: Boolean = false,
        allowDelete: Boolean = false,
    ): SmbFile {
        val access = mutableSetOf(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES)
        if (write) access.add(AccessMask.FILE_WRITE_DATA)
        if (rename) access.add(AccessMask.DELETE)
        val file =
            share.openFile(
                SmbPaths.child(root, name),
                access,
                setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                if (allowDelete)
                    setOf(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_DELETE)
                else setOf(SMB2ShareAccess.FILE_SHARE_READ),
                if (create) SMB2CreateDisposition.FILE_CREATE else SMB2CreateDisposition.FILE_OPEN,
                setOf(
                    SMB2CreateOptions.FILE_NON_DIRECTORY_FILE,
                    SMB2CreateOptions.FILE_OPEN_REPARSE_POINT,
                ),
            )
        try {
            checkAttributes(file.fileInformation)
            return file
        } catch (error: Exception) {
            file.close()
            throw error
        }
    }

    private inline fun <T> guarded(block: () -> T): T {
        try {
            cancellation.check()
            if (closed || owner?.isClosed() == true) throw RemoteStorageException(RemoteFailure.CONNECTION)
            return block()
        } catch (error: Exception) {
            val translated =
                try {
                    mapped(error, cancellation)
                } catch (cancelled: RemoteStorageException) {
                    cancelled
                }
            throw RemoteStorageException(translated.failure, residualNames = residualNames)
        }
    }

    override fun list(limit: Int): List<RemoteEntry> = guarded {
        require(limit in 1..10000)
        val result = mutableListOf<RemoteEntry>()
        // Samba may snapshot enumeration at directory-open time. Pinned roots protect identity,
        // but each refresh needs its own newly opened no-follow enumeration handle.
        share
            .openDirectory(
                root,
                setOf(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
                null,
                setOf(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                setOf(
                    SMB2CreateOptions.FILE_DIRECTORY_FILE,
                    SMB2CreateOptions.FILE_OPEN_REPARSE_POINT,
                ),
            )
            .use { directory ->
                checkAttributes(directory.fileInformation)
                val iterator = directory.iterator()
                while (iterator.hasNext()) {
                    cancellation.check()
                    val entry = iterator.next()
                    if (entry.fileName == "." || entry.fileName == "..") continue
                    if (result.size == limit) throw RemoteStorageException(RemoteFailure.CONFLICT)
                    RemoteNames.requireChild(entry.fileName)
                    val attrs = entry.fileAttributes
                    result +=
                        RemoteEntry(
                            entry.fileName,
                            entry.endOfFile,
                            entry.lastWriteTime.toEpochMillis(),
                            attrs and
                                (FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value or
                                    FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value) == 0L,
                        )
                }
            }
        result
    }

    override fun stat(name: String): RemoteEntry? = guarded {
        try {
            open(name).use { file ->
                val info = file.fileInformation
                RemoteEntry(
                    name,
                    info.standardInformation.endOfFile,
                    info.basicInformation.lastWriteTime.toEpochMillis(),
                    true,
                )
            }
        } catch (error: SMBApiException) {
            if (error.statusCode == 0xC0000034L || error.statusCode == 0xC000003AL) null
            else throw error
        }
    }

    override fun openRead(name: String, offset: Long): InputStream = guarded {
        require(offset >= 0)
        val handle = open(name)
        object : InputStream() {
            private var position = offset
            private var done = false

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
            }

            override fun read(buffer: ByteArray, start: Int, length: Int): Int = guarded {
                require(start >= 0 && length >= 0 && start <= buffer.size - length)
                if (done) throw IOException("Stream closed")
                if (length == 0) 0
                else
                    handle.read(buffer, position, start, length).also { if (it > 0) position += it }
            }

            override fun close() {
                if (!done) {
                    done = true
                    runCatching { handle.close() }
                }
            }
        }
    }

    override fun createExclusive(name: String): OutputStream = guarded {
        val handle = open(name, write = true, create = true)
        partialNames.add(name)
        object : OutputStream() {
            private var position = 0L
            private var done = false

            override fun write(value: Int) {
                write(byteArrayOf(value.toByte()))
            }

            override fun write(buffer: ByteArray, start: Int, length: Int) = guarded {
                require(start >= 0 && length >= 0 && start <= buffer.size - length)
                if (done) throw IOException("Stream closed")
                var written = 0
                while (written < length) {
                    cancellation.check()
                    val n =
                        handle
                            .write(
                                buffer,
                                position,
                                start + written,
                                minOf(length - written, 256 * 1024),
                            )
                            .toInt()
                    if (n <= 0) throw IOException("Write made no progress")
                    position += n
                    written += n
                }
            }

            override fun flush() = guarded { if (!done) handle.flush() }

            override fun close() {
                if (!done) {
                    done = true
                    try {
                        guarded { handle.flush() }
                    } finally {
                        runCatching { handle.close() }
                    }
                }
            }
        }
    }

    private fun digest(file: SmbFile): RemoteDigest {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var size = 0L
        while (true) {
            cancellation.check()
            val n = file.read(buffer, size)
            if (n < 0) break
            if (n == 0) throw IOException("Read made no progress")
            hash.update(buffer, 0, n)
            size += n
        }
        return RemoteDigest(
            size,
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
        )
    }

    override fun publishNoReplace(staging: String, destination: String, expected: RemoteDigest) =
        guarded {
            require(!staging.equals(destination, ignoreCase = true))
            val destinationPath = SmbPaths.child(root, destination)
            open(staging, rename = true).use { source ->
                // No other SMB handle can write/delete this file while verification and rename
                // execute.
                if (digest(source) != expected) throw RemoteStorageException(RemoteFailure.CONFLICT)
                val id = source.fileInformation.internalInformation.indexNumber
                try {
                    source.rename(destinationPath, false)
                } catch (error: Exception) {
                    // A lost response has two possible owned locations. A server collision does
                    // not.
                    if (error !is SMBApiException) partialNames.add(destination)
                    throw error
                }
                partialNames.remove(staging)
                partialNames.add(destination)
                open(destination, allowDelete = true).use { published ->
                    if (
                        published.fileInformation.internalInformation.indexNumber != id ||
                            digest(published) != expected
                    )
                        throw RemoteStorageException(RemoteFailure.CONFLICT)
                }
                partialNames.remove(destination) // Verified final archive is not a partial.
                Unit
            }
        }

    private fun isClosed(): Boolean = closed || owner?.isClosed() == true

    override fun directory(segments: List<String>, create: Boolean): RemoteManagedConnection = guarded {
        require(segments.size <= 64)
        var path = root
        val opened = mutableListOf<Directory>()
        try {
            for (segment in segments) {
                path = SmbPaths.child(path, segment)
                val directory = share.openDirectory(
                    path,
                    setOf(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
                    setOf(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                    setOf(SMB2ShareAccess.FILE_SHARE_READ),
                    if (create) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OPEN,
                    setOf(SMB2CreateOptions.FILE_DIRECTORY_FILE, SMB2CreateOptions.FILE_OPEN_REPARSE_POINT),
                )
                opened.add(directory)
                checkAttributes(directory.fileInformation)
            }
            SmbRemoteConnection(client, sockets, share, path, cancellation, identity, this).also {
                try { it.pinRoot() } catch (error: Exception) { it.close(); throw error }
            }
        } finally { opened.forEach { runCatching { it.close() } } }
    }

    override fun moveManagedNoReplace(source: String, destination: String, expected: RemoteDigest): RemoteManagedMove = guarded {
        RemoteNames.requireChild(source); RemoteNames.requireChild(destination)
        require(!source.equals(destination, ignoreCase = true))
        fun result(state: RemoteManagedMoveState, observed: RemoteDigest? = null) =
            RemoteManagedMove(state, source, destination, observed)
        if (stat(source) == null) {
            val observed = RemoteManagedMoves.digest(this, destination)
            return@guarded if (observed == expected) result(RemoteManagedMoveState.AlreadyMoved, observed)
                else result(RemoteManagedMoveState.RetainedAmbiguous, observed)
        }
        if (stat(destination) != null) return@guarded result(RemoteManagedMoveState.TargetOccupied)
        partialNames.addAll(listOf(source, destination))
        open(source, rename = true).use { file ->
            // Deny writes/deletes throughout hashing and handle-based rename.
            if (digest(file) != expected) {
                partialNames.removeAll(listOf(source, destination))
                return@guarded result(RemoteManagedMoveState.SourceChanged)
            }
            val id = file.fileInformation.internalInformation.indexNumber
            file.rename(SmbPaths.child(root, destination), false)
            open(destination, allowDelete = true).use { moved ->
                val observed = digest(moved)
                if (moved.fileInformation.internalInformation.indexNumber != id || observed != expected || stat(source) != null)
                    return@guarded result(RemoteManagedMoveState.RetainedAmbiguous, observed)
                partialNames.removeAll(listOf(source, destination))
                result(RemoteManagedMoveState.VerifiedMoved, observed)
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            if (owner != null) {
                roots.forEach { runCatching { it.close() } }
                return
            }
            // Socket first: unblock pending synchronous operations; never remove remote paths.
            sockets.close()
            roots.forEach { runCatching { it.close() } }
            runCatching { client.close() }
        }
    }
}

private fun mapped(error: Exception, cancellation: RemoteCancellation): RemoteStorageException {
    cancellation.check()
    if (error is RemoteStorageException) return error
    val code = (error as? SMBApiException)?.statusCode
    val failure =
        when {
            error is IllegalArgumentException -> RemoteFailure.INVALID_PATH
            code == 0xC000006DL || code == 0xC000006AL || code == 0xC0000064L ->
                RemoteFailure.AUTHENTICATION
            code == 0xC0000022L -> RemoteFailure.PERMISSION
            code == 0xC0000035L -> RemoteFailure.ALREADY_EXISTS
            code == 0xC0000034L || code == 0xC000003AL -> RemoteFailure.NOT_FOUND
            code == 0xC0000043L || code == 0xC0000056L -> RemoteFailure.CONFLICT
            code == 0x8000002DL || code == 0xC0000279L || code == 0xC000050BL ->
                RemoteFailure.INVALID_PATH
            else -> RemoteFailure.CONNECTION
        }
    return RemoteStorageException(failure)
}
