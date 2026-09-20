package com.ugallery.core.remotestorage

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class RemoteProtocol {
    SFTP,
    SMB,
}

enum class RemoteAuthKind {
    PASSWORD,
    PRIVATE_KEY,
}

/** Metadata only. A task freezes this value so editing a profile cannot redirect its data. */
data class RemoteProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int = if (protocol == RemoteProtocol.SFTP) 22 else 445,
    val username: String,
    val root: String,
    val share: String = "",
    val domain: String = "",
    val authKind: RemoteAuthKind = RemoteAuthKind.PASSWORD,
    val trustedHostKey: String? = null,
) {
    fun validate() {
        require(UUID.fromString(id).toString() == id)
        require(name.isNotBlank() && name.length <= 120)
        require(
            host.isNotBlank() &&
                host.length <= 253 &&
                host.none { it.isWhitespace() || it in "/\\@?#" || it.code < 32 }
        )
        require(port in 1..65535)
        require(username.isNotBlank() && username.length <= 256 && username.none { it.code < 32 })
        require(root.length <= 4096 && root.none { it.code < 32 || it == '\u007f' })
        require(root.split('/', '\\').none { it == ".." })
        if (protocol == RemoteProtocol.SMB) {
            RemoteNames.requireChild(share)
            require(authKind == RemoteAuthKind.PASSWORD)
            require(!root.startsWith("\\\\") && ':' !in root)
        }
        require(domain.length <= 256 && domain.none { it.code < 32 })
        require(
            trustedHostKey == null || Regex("SHA256:[A-Za-z0-9+/]{43}=?").matches(trustedHostKey)
        )
    }
}

/** Secrets have no structural toString/copy and are zeroed after each connection attempt. */
sealed class RemoteCredentials : Closeable {
    class Password(val value: CharArray) : RemoteCredentials() {
        override fun close() {
            value.fill('\u0000')
        }

        override fun toString() = "Password([redacted])"
    }

    class PrivateKey(val bytes: ByteArray, val passphrase: CharArray = charArrayOf()) :
        RemoteCredentials() {
        override fun close() {
            bytes.fill(0)
            passphrase.fill('\u0000')
        }

        override fun toString() = "PrivateKey([redacted])"
    }
}

interface RemoteCredentialVault {
    fun save(profileId: String, credentials: RemoteCredentials)

    fun load(profileId: String): RemoteCredentials?

    fun delete(profileId: String)
}

object RemoteNames {
    /** Operations address ONE child of the configured directory, never an archive member path. */
    fun requireChild(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && name.length <= 240)
        require(name.none { it.code < 32 || it.code == 127 || it in "/\\:*?\"<>|" })
        require(!name.endsWith('.') && !name.endsWith(' '))
        return name
    }
}

enum class RemoteFailure {
    AUTHENTICATION,
    IDENTITY_REQUIRED,
    IDENTITY_CHANGED,
    PERMISSION,
    UNSUPPORTED,
    NOT_FOUND,
    ALREADY_EXISTS,
    CONFLICT,
    CONNECTION,
    INVALID_PATH,
    CANCELLED,
}

class RemoteStorageException(
    val failure: RemoteFailure,
    /** Only for a host-key trust screen; never credentials/server exception strings. */
    val observedHostKey: String? = null,
    cause: Throwable? = null,
    val residualNames: List<String> = emptyList(),
) : IOException(failure.name, cause)

data class RemoteCapabilities(
    val atomicPublish: Boolean,
    val encrypted: Boolean,
    val signed: Boolean,
)

data class RemoteEntry(
    val name: String,
    val size: Long,
    val modifiedMillis: Long,
    val regularFile: Boolean,
)

data class RemoteDigest(val size: Long, val sha256: String) {
    init {
        require(size >= 0 && Regex("[a-f0-9]{64}").matches(sha256))
    }
}

/** Blocking I/O: callers run off Main and explicitly cancel sockets when a worker is stopped. */
fun interface RemoteConnectionFactory {
    fun connect(
        profile: RemoteProfile,
        credentials: RemoteCredentials,
        cancellation: RemoteCancellation,
    ): RemoteConnection
}

interface RemoteConnection : Closeable {
    val capabilities: RemoteCapabilities
    /** Verified SFTP key or negotiated SMB encryption/signing description; no secrets. */
    val identity: String
    val residualNames: List<String>
        get() = emptyList()

    /**
     * Bounded server iteration. Throws rather than silently presenting a truncated archive list.
     */
    fun list(limit: Int = 1000): List<RemoteEntry>

    fun stat(name: String): RemoteEntry?

    fun openRead(name: String, offset: Long = 0): InputStream

    /** Server-enforced exclusive create, NEVER pre-stat followed by overwrite/open-if. */
    fun createExclusive(name: String): OutputStream

    /**
     * Verify staging against expected; publish without replacing an existing destination. SFTP uses
     * negotiated hardlink@openssh.com (same filesystem); SMB uses rename(false). Return only after
     * destination full readback matches. On uncertain result keep all bytes. Staging may remain
     * after success. Callers journal both names; no automatic path deletion.
     */
    fun publishNoReplace(staging: String, destination: String, expected: RemoteDigest)
}

/**
 * Register a socket BEFORE connect. Cancellation closes it even before a library marks connected.
 */
class RemoteCancellation {
    private val resources = mutableSetOf<Closeable>()
    @Volatile private var cancelled = false

    fun check() {
        if (cancelled) throw RemoteStorageException(RemoteFailure.CANCELLED)
    }

    @Synchronized
    fun register(resource: Closeable) {
        if (cancelled) {
            runCatching { resource.close() }
            check()
        }
        resources.add(resource)
    }

    @Synchronized
    fun unregister(resource: Closeable) {
        resources.remove(resource)
    }

    fun cancel() {
        val closing =
            synchronized(this) {
                cancelled = true
                resources.toList().also { resources.clear() }
            }
        closing.forEach { runCatching { it.close() } }
    }
}
