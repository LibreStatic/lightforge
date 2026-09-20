package com.ugallery.core.remotestorage.sftp

import com.ugallery.core.remotestorage.*
import net.schmizz.sshj.DefaultSecurityProviderConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.*
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** An isolated SSH/SFTP session; never registers or replaces a JVM/Android security provider. */
class SftpRemoteConnectionFactory(
    private val connectTimeoutMillis: Int = 15_000,
    private val operationTimeoutMillis: Int = 30_000,
) : RemoteConnectionFactory {
    init { require(connectTimeoutMillis > 0 && operationTimeoutMillis > 0) }

    override fun connect(
        profile: RemoteProfile,
        credentials: RemoteCredentials,
        cancellation: RemoteCancellation,
    ): RemoteConnection {
        var ssh: SSHClient? = null
        val sockets = SftpSocketFactory(cancellation)
        val verifier = SftpHostKeyVerifier(profile.trustedHostKey)
        try {
            profile.validate()
            require(profile.protocol == RemoteProtocol.SFTP)
            val root = SftpPaths.root(profile.root)
            cancellation.check()
            val config = DefaultSecurityProviderConfig().apply {
                // Do not offer legacy SHA1 signatures, certificates or algorithms absent from this runtime.
                val ed25519 = runCatching {
                    KeyFactory.getInstance("Ed25519")
                    Signature.getInstance("Ed25519")
                }.isSuccess
                keyAlgorithms = keyAlgorithms.filter {
                    it.name in setOf("rsa-sha2-256", "rsa-sha2-512", "ecdsa-sha2-nistp256",
                        "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521") ||
                        (ed25519 && it.name == "ssh-ed25519")
                }
                keyExchangeFactories = keyExchangeFactories.filter {
                    it.name in setOf("ecdh-sha2-nistp256", "ecdh-sha2-nistp384", "ecdh-sha2-nistp521",
                        "diffie-hellman-group14-sha256", "diffie-hellman-group-exchange-sha256", "ext-info-c")
                }
                cipherFactories = cipherFactories.filter { it.name in setOf("aes128-ctr", "aes256-ctr",
                    "aes128-gcm@openssh.com", "aes256-gcm@openssh.com") }
                macFactories = macFactories.filter { it.name in setOf("hmac-sha2-256", "hmac-sha2-512",
                    "hmac-sha2-256-etm@openssh.com", "hmac-sha2-512-etm@openssh.com") }
            }
            val client = SSHClient(config).also { ssh = it }
            client.socketFactory = sockets
            client.connectTimeout = connectTimeoutMillis
            client.timeout = operationTimeoutMillis
            client.addHostKeyVerifier(verifier)
            client.connect(profile.host, profile.port)
            cancellation.check()
            // SSHClient.connect performs verified KEX before either authentication method is invoked.
            when (credentials) {
                is RemoteCredentials.Password -> {
                    require(profile.authKind == RemoteAuthKind.PASSWORD)
                    client.authPassword(profile.username, credentials.value)
                }
                is RemoteCredentials.PrivateKey -> {
                    require(profile.authKind == RemoteAuthKind.PRIVATE_KEY)
                    require(credentials.bytes.size in 1..1_048_576)
                    // SSHJ accepts PEM/OpenSSH content, so SAF keys never need a plaintext disk copy.
                    val provider = client.loadKeys(credentials.bytes.toString(Charsets.UTF_8), null,
                        PasswordUtils.createOneOff(credentials.passphrase))
                    val type = provider.type
                    if (type !in setOf(net.schmizz.sshj.common.KeyType.RSA,
                            net.schmizz.sshj.common.KeyType.ECDSA256,
                            net.schmizz.sshj.common.KeyType.ECDSA384,
                            net.schmizz.sshj.common.KeyType.ECDSA521,
                            net.schmizz.sshj.common.KeyType.ED25519)) {
                        throw RemoteStorageException(RemoteFailure.UNSUPPORTED)
                    }
                    client.authPublickey(profile.username, provider)
                }
            }
            cancellation.check()
            val sftp = client.newSFTPClient()
            sftp.sftpEngine.timeoutMs = operationTimeoutMillis
            return SftpRemoteConnection(client, sftp, sockets, cancellation, root,
                verifier.observed ?: throw RemoteStorageException(RemoteFailure.CONNECTION)).also {
                it.verifyRoot()
                it.probePublication()
            }
        } catch (error: Exception) {
            runCatching { ssh?.close() }
            sockets.close()
            if (runCatching { cancellation.check() }.isFailure) {
                throw RemoteStorageException(RemoteFailure.CANCELLED,
                    residualNames = (error as? RemoteStorageException)?.residualNames.orEmpty())
            }
            if (verifier.rejected) {
                throw RemoteStorageException(if (profile.trustedHostKey == null)
                    RemoteFailure.IDENTITY_REQUIRED else RemoteFailure.IDENTITY_CHANGED, verifier.observed)
            }
            throw sftpFailure(error)
        } finally {
            credentials.close()
        }
    }
}

internal class SftpHostKeyVerifier(private val trusted: String?) : HostKeyVerifier {
    var observed: String? = null
        private set
    var rejected = false
        private set
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val encoded = Buffer.PlainBuffer().putPublicKey(key).compactData
        observed = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(encoded))
        val accepted = trusted != null && MessageDigest.isEqual(
            trusted.trimEnd('=').toByteArray(Charsets.US_ASCII), observed!!.toByteArray(Charsets.US_ASCII))
        rejected = !accepted
        return accepted
    }
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

/** Socket is registered before SSHJ starts its blocking connect, not after successful authentication. */
internal class SftpSocketFactory(private val cancellation: RemoteCancellation) : SocketFactory(), Closeable {
    private val sockets = mutableListOf<Socket>()
    @Synchronized override fun createSocket(): Socket {
        cancellation.check()
        return Socket().also { cancellation.register(it); sockets.add(it) }
    }
    override fun createSocket(host: String, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
    @Synchronized override fun close() {
        sockets.forEach { runCatching { it.close() }; cancellation.unregister(it) }
        sockets.clear()
    }
}

internal object SftpPaths {
    fun root(value: String): String {
        require(value.startsWith('/') && value.length <= 4096 && '\\' !in value)
        val components = value.split('/').filter { it.isNotEmpty() }
        require(components.none { it == "." || it == ".." || it.any { c -> c.code < 32 || c.code == 127 } })
        return "/" + components.joinToString("/")
    }
    fun child(root: String, name: String) = root.trimEnd('/') + "/" + RemoteNames.requireChild(name)
}

internal fun sftpFailure(error: Exception): RemoteStorageException {
    if (error is RemoteStorageException) return error
    val failure = when (error) {
        is IllegalArgumentException -> RemoteFailure.INVALID_PATH
        is UserAuthException -> RemoteFailure.AUTHENTICATION
        is net.schmizz.sshj.transport.TransportException ->
            if (error.disconnectReason == net.schmizz.sshj.common.DisconnectReason.KEY_EXCHANGE_FAILED)
                RemoteFailure.UNSUPPORTED else RemoteFailure.CONNECTION
        is SFTPException -> when (error.statusCode) {
            Response.StatusCode.NO_SUCH_FILE -> RemoteFailure.NOT_FOUND
            Response.StatusCode.PERMISSION_DENIED -> RemoteFailure.PERMISSION
            Response.StatusCode.FILE_ALREADY_EXISTS -> RemoteFailure.ALREADY_EXISTS
            Response.StatusCode.OP_UNSUPPORTED -> RemoteFailure.UNSUPPORTED
            else -> RemoteFailure.CONNECTION
        }
        is java.security.GeneralSecurityException -> RemoteFailure.UNSUPPORTED
        else -> RemoteFailure.CONNECTION
    }
    // Do not propagate arbitrary remote strings (paths, banners, usernames) to UI or logs.
    return RemoteStorageException(failure)
}
