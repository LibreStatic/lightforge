package com.ugallery.core.remotestorage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound secrets outside backup/cache. Profile identity is authenticated as GCM AAD. */
class AndroidRemoteCredentialVault(context: Context) : RemoteCredentialVault {
    private val root = File(context.applicationContext.noBackupFilesDir, "remote-credentials")

    private fun file(id: String): AtomicFile {
        require(UUID.fromString(id).toString() == id)
        return AtomicFile(File(root, "$id.bin"))
    }

    override fun save(profileId: String, credentials: RemoteCredentials) =
        synchronized(lock) {
            val target = file(profileId)
            val plain = encode(credentials)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                cipher.updateAAD(profileId.toByteArray(Charsets.US_ASCII))
                val encrypted = cipher.doFinal(plain)
                check(root.isDirectory || root.mkdirs())
                val out = target.startWrite()
                try {
                    out.write(byteArrayOf(1, cipher.iv.size.toByte()))
                    out.write(cipher.iv)
                    out.write(encrypted)
                    target.finishWrite(out)
                } catch (error: Throwable) {
                    target.failWrite(out)
                    throw error
                }
            } finally {
                plain.fill(0)
            }
        }

    override fun load(profileId: String): RemoteCredentials? =
        synchronized(lock) {
            val target = file(profileId)
            if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists())
                return@synchronized null
            val bytes =
                target.openRead().use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(out.size() + count <= MaxBytes + 1024)
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
            require(bytes.size >= 30 && bytes[0].toInt() == 1 && bytes[1].toInt() == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 2, 12))
            cipher.updateAAD(profileId.toByteArray(Charsets.US_ASCII))
            val plain = cipher.doFinal(bytes, 14, bytes.size - 14)
            try {
                decode(plain)
            } finally {
                plain.fill(0)
                bytes.fill(0)
            }
        }

    override fun delete(profileId: String) = synchronized(lock) { file(profileId).delete() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(Alias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            Alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }

    companion object {
        private val lock = Any()
        private const val Alias = "ugallery.remote.credentials.v1"
        private const val MaxBytes = 1024 * 1024

        private fun encode(credentials: RemoteCredentials): ByteArray {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { out ->
                when (credentials) {
                    is RemoteCredentials.Password -> {
                        out.writeByte(1)
                        writeChars(out, credentials.value)
                    }
                    is RemoteCredentials.PrivateKey -> {
                        require(credentials.bytes.size in 1..MaxBytes)
                        out.writeByte(2)
                        out.writeInt(credentials.bytes.size)
                        out.write(credentials.bytes)
                        writeChars(out, credentials.passphrase)
                    }
                }
            }
            return output.toByteArray().also { require(it.size <= MaxBytes) }
        }

        private fun writeChars(out: DataOutputStream, chars: CharArray) {
            require(chars.size <= 16384)
            val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
            val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
            try {
                out.writeInt(bytes.size)
                out.write(bytes)
            } finally {
                bytes.fill(0)
                if (encoded.hasArray()) encoded.array().fill(0)
            }
        }

        private fun readBytes(input: DataInputStream, limit: Int): ByteArray {
            val size = input.readInt()
            require(size in 0..limit && size <= input.available())
            return ByteArray(size).also { input.readFully(it) }
        }

        private fun readChars(input: DataInputStream): CharArray {
            val bytes = readBytes(input, 65536)
            val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
            return try {
                CharArray(decoded.remaining()).also { decoded.get(it) }
            } finally {
                bytes.fill(0)
                if (decoded.hasArray()) decoded.array().fill('\u0000')
            }
        }

        private fun decode(bytes: ByteArray): RemoteCredentials =
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val secret =
                    when (input.readUnsignedByte()) {
                        1 -> RemoteCredentials.Password(readChars(input))
                        2 ->
                            RemoteCredentials.PrivateKey(
                                readBytes(input, MaxBytes),
                                readChars(input),
                            )
                        else -> error("Unknown credential format")
                    }
                if (input.available() != 0) {
                    secret.close()
                    error("Unexpected credential data")
                }
                secret
            }
    }
}
