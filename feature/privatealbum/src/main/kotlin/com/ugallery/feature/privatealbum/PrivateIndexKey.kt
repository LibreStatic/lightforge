package com.ugallery.feature.privatealbum

import com.ugallery.core.security.PrivateAlbumCrypto
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Independent random SQLCipher password; v2 wraps it with the authenticated vault master. */
internal class PrivateIndexKey(context: Context, private val databaseName: String) {
    private val identity = MessageDigest.getInstance("SHA-256").digest(databaseName.toByteArray())
        .joinToString("") { "%02x".format(it) }
    internal val alias = "ugallery.privatealbum.index.v1.$identity"
    internal val file = File(context.noBackupFilesDir, "private-index-$identity.key")
    private val aad = "UGallery private index key v1:$databaseName".toByteArray(Charsets.UTF_8)

    fun loadOrCreate(allowCreation: Boolean): ByteArray {
        PrivateIndexStorage.requireRegularOrAbsent(file)
        PrivateIndexStorage.requireRegularOrAbsent(File(file.path + ".bak"))
        PrivateIndexStorage.requireRegularOrAbsent(File(file.path + ".new"))
        val atomic = AtomicFile(file)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (file.exists() || File(file.path + ".bak").exists()) {
            val wrapped = readRecord(atomic)
            return try { decrypt(wrapped, store) } finally { wrapped.fill(0) }
        }
        check(allowCreation) { "Encrypted private index key is missing" }
        val deviceKey = (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
        val password = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, deviceKey); updateAAD(aad)
            }
            val wrapped = MAGIC + cipher.iv + cipher.doFinal(password)
            val output = atomic.startWrite()
            try {
                output.write(wrapped)
                // AtomicFile logs some sync/rename failures instead of propagating them.
                output.fd.sync()
                atomic.finishWrite(output)
                val persisted = atomic.openRead().use { it.readBytesLimited(66) }
                try {
                    check(MessageDigest.isEqual(wrapped, persisted)) { "Private index key publication failed" }
                    val readback = Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, deviceKey, GCMParameterSpec(128, persisted, 5, 12))
                        updateAAD(aad); doFinal(persisted, 17, 48)
                    }
                    try { check(MessageDigest.isEqual(password, readback)) }
                    finally { readback.fill(0) }
                } finally { persisted.fill(0) }
            }
            catch (failure: Throwable) { atomic.failWrite(output); throw failure }
            finally { wrapped.fill(0) }
            PrivateIndexStorage.syncDirectory(requireNotNull(file.parentFile))
            return password
        } catch (failure: Throwable) { password.fill(0); throw failure }
    }

    fun isProtected(expectedAlias: String): Boolean {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return false
        val record = readRecord(atomic)
        return try {
            val parsed = PrivateIndexKeyRecord.parse(record)
            parsed.authenticatedAlias == expectedAlias && PrivateAlbumCrypto.isAuthenticationBound(expectedAlias) &&
                !KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)
        } finally { record.fill(0) }
    }

    /** Caller owns the index migration lock and an exclusive database lease. Password is unchanged. */
    fun protect(authenticatedAlias: String) {
        check(PrivateAlbumCrypto.isAuthenticationBound(authenticatedAlias))
        val target = PrivateAlbumCrypto.requireExistingMasterKey(authenticatedAlias)
        val atomic = AtomicFile(file)
        val previous = readRecord(atomic)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val parsed = PrivateIndexKeyRecord.parse(previous)
        require(parsed.authenticatedAlias == null || parsed.authenticatedAlias == authenticatedAlias) {
            "Private index master changed; retain the authoritative wrapper"
        }
        val password = try { decrypt(previous, store) } finally { previous.fill(0) }
        try {
            if (parsed.authenticatedAlias == null) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, target); updateAAD(authenticatedAad(authenticatedAlias))
                }
                val candidate = PrivateIndexKeyRecord.encode(authenticatedAlias, cipher.iv, cipher.doFinal(password))
                try {
                    // Verify authorization and bytes before the single durable publication.
                    val verified = decrypt(candidate, store)
                    try { check(MessageDigest.isEqual(password, verified)) } finally { verified.fill(0) }
                    val output = atomic.startWrite()
                    try { output.write(candidate); output.fd.sync(); atomic.finishWrite(output) }
                    catch (failure: Throwable) { atomic.failWrite(output); throw failure }
                    val persisted = readRecord(atomic)
                    try { check(MessageDigest.isEqual(candidate, persisted)) { "Private index wrapper publication failed" } }
                    finally { persisted.fill(0) }
                } finally { candidate.fill(0) }
            }
            // The v2 record is authoritative after rename, even if retirement is interrupted.
            // A retry decrypts v2 first; it never falls back to the old unprotected wrapper.
            PrivateIndexStorage.syncDirectory(requireNotNull(file.parentFile))
            check(!File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
            if (store.containsAlias(alias)) store.deleteEntry(alias)
            check(!store.containsAlias(alias)) { "Legacy private index key retirement requires retry" }
        } finally { password.fill(0) }
    }

    private fun readRecord(atomic: AtomicFile): ByteArray {
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach(PrivateIndexStorage::requireRegularOrAbsent)
        return atomic.openRead().use { it.readBytesLimited(PrivateIndexKeyRecord.MAX_SIZE + 1) }
            .also { PrivateIndexKeyRecord.parse(it) }
    }

    private fun authenticatedAad(keyAlias: String) =
        "UGallery private index key v2:$databaseName:$keyAlias".toByteArray(Charsets.UTF_8)

    private fun decrypt(record: ByteArray, store: KeyStore): ByteArray {
        val parsed = PrivateIndexKeyRecord.parse(record)
        val authenticatedAlias = parsed.authenticatedAlias
        val key = if (authenticatedAlias == null) {
            store.getKey(alias, null) as? SecretKey ?: error("Private index device key is missing")
        } else {
            check(PrivateAlbumCrypto.isAuthenticationBound(authenticatedAlias)) { "Private index authenticated key is missing or invalid" }
            PrivateAlbumCrypto.requireExistingMasterKey(authenticatedAlias)
        }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record, parsed.ivOffset, 12))
            updateAAD(if (authenticatedAlias == null) aad else authenticatedAad(authenticatedAlias))
            doFinal(record, parsed.ivOffset + 12, 48).also { check(it.size == 32) }
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        var size = 0
        while (size < limit) {
            val count = read(bytes, size, limit - size)
            if (count < 0) break
            size += count
        }
        return bytes.copyOf(size)
    }

    companion object { private val MAGIC = byteArrayOf(85, 71, 73, 75, 1) }
}
