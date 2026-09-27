package com.librestatic.lightforge.core.remotestorage.sftp

import com.librestatic.lightforge.core.remotestorage.*
import com.hierynomus.sshj.sftp.RemoteResourceSelector
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.*
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One caller at a time. Streaming handles are scoped to this session and cancelled with its socket. */
internal class SftpRemoteConnection(
    private val ssh: SSHClient,
    private val sftp: SFTPClient,
    private val sockets: SftpSocketFactory,
    private val cancellation: RemoteCancellation,
    private val root: String,
    override val identity: String,
    private val owner: SftpRemoteConnection? = null,
) : RemoteManagedConnection {
    private var publicationVerified = false
    private var closed = false
    private val probeResiduals = mutableSetOf<String>()
    override val residualNames: List<String> get() = probeResiduals.toList()
    override val capabilities: RemoteCapabilities
        get() = RemoteCapabilities(publicationVerified, encrypted = true, signed = true)

    private fun check() {
        cancellation.check()
        owner?.check()
        if (closed) throw RemoteStorageException(RemoteFailure.CONNECTION)
    }
    private inline fun <T> operation(block: () -> T): T {
        check()
        return try { block() } catch (error: Exception) {
            cancellation.check()
            throw sftpFailure(error)
        }
    }

    internal fun verifyRoot() = operation {
        // Reject symlink components rather than merely checking a string prefix after resolution.
        var current = ""
        for (part in root.split('/').filter { it.isNotEmpty() }) {
            current += "/$part"
            val attrs = sftp.lstat(current)
            if (attrs.type != FileMode.Type.DIRECTORY) throw RemoteStorageException(RemoteFailure.INVALID_PATH)
        }
        if (sftp.lstat(root).type != FileMode.Type.DIRECTORY || sftp.canonicalize(root).trimEnd('/') != root.trimEnd('/')) {
            throw RemoteStorageException(RemoteFailure.INVALID_PATH)
        }
    }

    override fun list(limit: Int): List<RemoteEntry> = operation {
        require(limit in 1..10_000)
        verifyRoot()
        val result = mutableListOf<RemoteEntry>()
        var seen = 0
        sftp.ls(root, RemoteResourceSelector { entry ->
            check()
            if (++seen > limit) throw RemoteStorageException(RemoteFailure.CONFLICT)
            // Names outside our one-child portable namespace are displayed neither partially nor ambiguously.
            RemoteNames.requireChild(entry.name)
            result += remoteEntry(entry.name, entry.attributes)
            RemoteResourceSelector.Result.CONTINUE
        })
        result
    }

    override fun stat(name: String): RemoteEntry? = operation {
        verifyRoot()
        val path = SftpPaths.child(root, name)
        val attrs = try { sftp.lstat(path) } catch (error: SFTPException) {
            if (error.statusCode == Response.StatusCode.NO_SUCH_FILE) return@operation null
            throw error
        }
        remoteEntry(name, attrs)
    }

    override fun openRead(name: String, offset: Long): InputStream = operation {
        require(offset >= 0)
        val entry = stat(name) ?: throw RemoteStorageException(RemoteFailure.NOT_FOUND)
        if (!entry.regularFile || offset > entry.size) throw RemoteStorageException(RemoteFailure.INVALID_PATH)
        val handle = sftp.open(SftpPaths.child(root, name), EnumSet.of(OpenMode.READ))
        object : InputStream() {
            var position = offset
            var ended = false
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
            }
            override fun read(bytes: ByteArray, start: Int, length: Int): Int = operation {
                require(start >= 0 && length >= 0 && start <= bytes.size - length)
                if (ended) throw RemoteStorageException(RemoteFailure.CONNECTION)
                if (length == 0) return@operation 0
                val count = handle.read(position, bytes, start, minOf(length, 32_768))
                if (count > 0) position = Math.addExact(position, count.toLong())
                count
            }
            override fun close() {
                if (!ended) { ended = true; handle.close() }
            }
        }
    }

    override fun createExclusive(name: String): OutputStream = operation {
        verifyRoot()
        val path = SftpPaths.child(root, name)
        // EXCL handles collisions and pre-existing symlinks in the server's OPEN operation.
        val handle = try {
            sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL))
        } catch (error: SFTPException) {
            // v3 OpenSSH reports EEXIST as generic FAILURE. This post-error inspection only classifies;
            // it NEVER authorizes or retries an overwrite.
            if (runCatching { sftp.lstat(path) }.isSuccess) throw RemoteStorageException(RemoteFailure.ALREADY_EXISTS)
            throw error
        }
        object : OutputStream() {
            var position = 0L
            var ended = false
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, start: Int, length: Int) = operation {
                require(start >= 0 && length >= 0 && start <= bytes.size - length)
                if (ended) throw RemoteStorageException(RemoteFailure.CONNECTION)
                var at = start
                val end = start + length
                while (at < end) {
                    check()
                    val count = minOf(32_768, end - at)
                    handle.write(position, bytes, at, count)
                    position = Math.addExact(position, count.toLong())
                    at += count
                }
            }
            override fun close() {
                if (!ended) { ended = true; handle.close() }
            }
        }
    }

    override fun publishNoReplace(staging: String, destination: String, expected: RemoteDigest) = operation {
        if (!publicationVerified) throw RemoteStorageException(RemoteFailure.UNSUPPORTED)
        require(staging != destination)
        verifyDigest(staging, expected)
        hardlink(staging, destination)
        verifyDigest(destination, expected)
        // Deliberately preserve staging. A lost response never causes deletion of either data copy.
    }

    private fun hardlink(staging: String, destination: String) {
        val source = SftpPaths.child(root, staging)
        val target = SftpPaths.child(root, destination)
        verifyRoot()
        val engine = sftp.sftpEngine
        try {
            engine.request(engine.newExtendedRequest("hardlink@openssh.com").putString(source).putString(target))
                .retrieve(engine.timeoutMs.toLong(), TimeUnit.MILLISECONDS).ensureStatusPacketIsOK()
        } catch (error: SFTPException) {
            if (runCatching { sftp.lstat(target) }.isSuccess) throw RemoteStorageException(RemoteFailure.ALREADY_EXISTS)
            throw error
        }
    }

    internal fun probePublication() {
        val engine = sftp.sftpEngine
        if (engine.getServerExtensionData("hardlink", "openssh.com") != "1") return
        val id = UUID.randomUUID().toString()
        val staging = ".lightforge-sftp-probe-$id.part"
        val collision = ".lightforge-sftp-probe-$id.collision"
        val destination = ".lightforge-sftp-probe-$id.published"
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val other = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val expected = digest(bytes)
        val sentinel = digest(other)
        val owned = linkedMapOf<String, RemoteDigest>()
        var failure: RemoteFailure? = null
        try {
            // Record potential residual before OPEN: response loss may have created an empty file.
            probeResiduals += staging
            createExclusive(staging).use { it.write(bytes) }
            owned[staging] = expected
            verifyDigest(staging, expected)
            probeResiduals += collision
            createExclusive(collision).use { it.write(other) }
            owned[collision] = sentinel
            verifyDigest(collision, sentinel)
            val exclusiveFailed = runCatching { createExclusive(collision).use { } }.isFailure
            verifyDigest(collision, sentinel)
            val collisionFailed = runCatching { hardlink(staging, collision) }.isFailure
            verifyDigest(collision, sentinel)
            verifyDigest(staging, expected)
            if (!exclusiveFailed || !collisionFailed) throw RemoteStorageException(RemoteFailure.UNSUPPORTED)
            probeResiduals += destination
            hardlink(staging, destination)
            owned[destination] = expected
            verifyDigest(destination, expected)
            publicationVerified = true
        } catch (error: Exception) {
            failure = if (runCatching { cancellation.check() }.isFailure)
                RemoteFailure.CANCELLED else sftpFailure(error).failure
        } finally {
            // No broad cleanup: only own confirmed creation+matching nonce hash. Missing/uncertain stays journaled.
            for ((name, expectedDigest) in owned) {
                runCatching {
                    verifyDigest(name, expectedDigest)
                    sftp.rm(SftpPaths.child(root, name))
                    if (stat(name) == null) probeResiduals -= name
                }
            }
            bytes.fill(0)
            other.fill(0)
        }
        failure?.let { throw RemoteStorageException(it, residualNames = probeResiduals.toList()) }
    }

    private fun verifyDigest(name: String, expected: RemoteDigest) {
        val before = stat(name) ?: throw RemoteStorageException(RemoteFailure.NOT_FOUND)
        if (!before.regularFile || before.size != expected.size) throw RemoteStorageException(RemoteFailure.CONFLICT)
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        openRead(name).use { input ->
            val buffer = ByteArray(32_768)
            while (true) {
                check()
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) throw RemoteStorageException(RemoteFailure.CONNECTION)
                count = Math.addExact(count, n.toLong())
                if (count > expected.size) throw RemoteStorageException(RemoteFailure.CONFLICT)
                hash.update(buffer, 0, n)
            }
        }
        if (count != expected.size || hex(hash.digest()) != expected.sha256 || stat(name) != before) {
            throw RemoteStorageException(RemoteFailure.CONFLICT)
        }
    }

    override fun directory(segments: List<String>, create: Boolean): RemoteManagedConnection = operation {
        require(segments.size <= 64)
        verifyRoot()
        var path = root
        for (segment in segments) {
            path = SftpPaths.child(path, segment)
            val attributes = try { sftp.lstat(path) } catch (error: SFTPException) {
                if (error.statusCode != Response.StatusCode.NO_SUCH_FILE || !create) throw error
                try { sftp.mkdir(path) } catch (race: SFTPException) {
                    if (sftp.lstat(path).type != FileMode.Type.DIRECTORY) throw race
                }
                sftp.lstat(path)
            }
            if (attributes.type != FileMode.Type.DIRECTORY)
                throw RemoteStorageException(RemoteFailure.INVALID_PATH)
        }
        SftpRemoteConnection(ssh, sftp, sockets, cancellation, path, identity, this).also {
            it.verifyRoot()
            it.publicationVerified = publicationVerified
        }
    }

    override fun moveManagedNoReplace(source: String, destination: String, expected: RemoteDigest): RemoteManagedMove = operation {
        verifyRoot()
        RemoteManagedMoves.move(this, source, destination, expected) { from, to ->
            verifyRoot()
            // Explicit empty flags: SSH_FXP_RENAME, never posix-rename/OVERWRITE.
            sftp.rename(SftpPaths.child(root, from), SftpPaths.child(root, to), emptySet<RenameFlags>())
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (owner != null) return // Borrowed directory; parent owns transport.
        runCatching { sftp.close() }
        runCatching { ssh.close() }
        sockets.close()
    }

    companion object {
        private fun remoteEntry(name: String, attrs: FileAttributes): RemoteEntry {
            if (!attrs.has(FileAttributes.Flag.SIZE) || attrs.size < 0) throw RemoteStorageException(RemoteFailure.CONFLICT)
            return RemoteEntry(name, attrs.size, Math.multiplyExact(attrs.mtime, 1000), attrs.type == FileMode.Type.REGULAR)
        }
        internal fun digest(bytes: ByteArray) = RemoteDigest(bytes.size.toLong(), hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
