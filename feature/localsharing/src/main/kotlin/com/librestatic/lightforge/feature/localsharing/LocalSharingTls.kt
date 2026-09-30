package com.librestatic.lightforge.feature.localsharing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** JSSE TLS with an AndroidKeyStore P-256 identity. No homemade key exchange or password PAKE. */
internal class PeerTls(context: Context) {
    private val IDENTITY = context.packageName + ".lightforge-local-sharing-identity-v2"
    private val VAULT = context.packageName + ".lightforge-local-sharing-vault-v1"
    private val store = LocalSharingStore(context)
    private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        synchronized(identityLock) {
            if (!keys.containsAlias(IDENTITY)) {
                val generator =
                    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                generator.initialize(
                    KeyGenParameterSpec.Builder(
                            IDENTITY,
                            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                        )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        // Conscrypt signs its already-computed TLS digest via NONEwithECDSA.
                        // Native API30 evidence: CryptoUpcalls -> Incompatible digest without NONE.
                        // TLS still negotiates hashed signature schemes; this authorizes only the
                        // KeyStore prehashed signing operation. Old v1 aliases are never deleted.
                        .setDigests(
                            KeyProperties.DIGEST_NONE,
                            KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512,
                        )
                        .setCertificateSubject(X500Principal("CN=Lightforge Local Peer"))
                        .setCertificateSerialNumber(BigInteger(128, SecureRandom()))
                        .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                        .setCertificateNotAfter(
                            Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L)
                        )
                        .build()
                )
                generator.generateKeyPair()
            }
            if (!keys.containsAlias(VAULT)) {
                val generator =
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(
                    KeyGenParameterSpec.Builder(
                            VAULT,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generator.generateKey()
            }
        }
    }

    private val certificate
        get() = keys.getCertificate(IDENTITY) as X509Certificate

    val pin
        get() = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).peerHex()

    fun context(expectedPin: String? = null): SSLContext {
        val trust =
            object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

                override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) {
                    validate(chain)
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
                    validate(chain)
                }

                private fun validate(chain: Array<X509Certificate>) {
                    require(chain.size == 1)
                    chain[0].checkValidity()
                    require(chain[0].publicKey.algorithm == "EC")
                    if (expectedPin != null)
                        require(
                            MessageDigest.isEqual(
                                MessageDigest.getInstance("SHA-256")
                                    .digest(chain[0].encoded)
                                    .peerHex()
                                    .toByteArray(),
                                expectedPin.toByteArray(),
                            )
                        )
                    // On receive, pin+bearer authorization is mandatory before every application
                    // verb.
                }
            }
        val manager =
            object : X509ExtendedKeyManager() {
                override fun getPrivateKey(alias: String?) =
                    keys.getKey(IDENTITY, null) as PrivateKey

                override fun getCertificateChain(alias: String?) = arrayOf(certificate)

                override fun getClientAliases(
                    type: String?,
                    issuers: Array<java.security.Principal>?,
                ) = if (type?.startsWith("EC") == true) arrayOf(IDENTITY) else null

                override fun getServerAliases(
                    type: String?,
                    issuers: Array<java.security.Principal>?,
                ) = if (type?.startsWith("EC") == true) arrayOf(IDENTITY) else null

                override fun chooseClientAlias(
                    types: Array<String>?,
                    issuers: Array<java.security.Principal>?,
                    socket: Socket?,
                ) = if (types?.any { it.startsWith("EC") } == true) IDENTITY else null

                override fun chooseServerAlias(
                    type: String?,
                    issuers: Array<java.security.Principal>?,
                    socket: Socket?,
                ) = if (type?.startsWith("EC") == true) IDENTITY else null

                override fun chooseEngineClientAlias(
                    types: Array<String>?,
                    issuers: Array<java.security.Principal>?,
                    engine: javax.net.ssl.SSLEngine?,
                ): String? = if (types?.any { it.startsWith("EC") } == true) IDENTITY else null

                override fun chooseEngineServerAlias(
                    type: String?,
                    issuers: Array<java.security.Principal>?,
                    engine: javax.net.ssl.SSLEngine?,
                ): String? = if (type?.startsWith("EC") == true) IDENTITY else null
            }
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf(manager), arrayOf(trust), SecureRandom())
        }
    }

    fun remotePin(socket: SSLSocket) =
        MessageDigest.getInstance("SHA-256")
            .digest(socket.session.peerCertificates.single().encoded)
            .peerHex()

    fun saveSecret(pin: String, value: String) {
        require(peerHash(pin) && peerHash(value))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.getKey(VAULT, null) as SecretKey)
        cipher.updateAAD(pin.toByteArray())
        val encrypted = cipher.doFinal(value.toByteArray())
        val a = AtomicFile(store.secretFile(pin))
        var out: FileOutputStream? = null
        try {
            out = a.startWrite()
            out.write(cipher.iv.size)
            out.write(cipher.iv)
            out.write(encrypted)
            a.finishWrite(out)
        } catch (e: Throwable) {
            a.failWrite(out)
            throw e
        }
    }

    fun secret(pin: String): String? {
        val f = store.secretFile(pin)
        if (!f.exists() && !java.io.File(f.path + ".bak").exists()) return null
        return AtomicFile(f).openRead().use { input ->
            val ivSize = input.read()
            require(ivSize == 12)
            val iv = ByteArray(ivSize)
            java.io.DataInputStream(input).readFully(iv)
            val bytes = input.readBytes()
            require(bytes.size == 80)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                keys.getKey(VAULT, null) as SecretKey,
                GCMParameterSpec(128, iv),
            )
            cipher.updateAAD(pin.toByteArray())
            String(cipher.doFinal(bytes)).also { require(peerHash(it)) }
        }
    }

    fun revoke(pin: String) {
        AtomicFile(store.secretFile(pin)).delete()
    }

    companion object {
        private val identityLock = Any()

        fun randomSecret() = ByteArray(32).also { SecureRandom().nextBytes(it) }.peerHex()
    }
}
