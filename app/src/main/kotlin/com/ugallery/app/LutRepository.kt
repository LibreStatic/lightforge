package com.ugallery.app

import android.content.ContentResolver
import android.net.Uri
import com.ugallery.core.database.ColorEditDao
import com.ugallery.core.database.CustomLutEntity
import com.ugallery.core.editing.video.CubeLut
import com.ugallery.core.editing.video.CubeLutParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class LutRepository(
    private val resolver: ContentResolver,
    private val dao: ColorEditDao,
    private val directory: File,
) {
    suspend fun import(uri: Uri, displayName: String): Long = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1_024)
            while (output.size() <= MaxBytes) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
            ?: error("LUT file cannot be opened")
        require(bytes.size <= MaxBytes) { "LUT file exceeds the 8 MB limit" }
        val parsed = bytes.inputStream().reader().use { CubeLutParser.parse(it, displayName) }
        val hash = bytes.sha256()
        val fileName = "$hash.cube"
        val destination = File(directory, fileName)
        dao.customLutByHash(hash)?.let { existing ->
            // A previous app restore or interrupted cleanup can leave the database row without
            // its owned file. Re-importing the same LUT must repair that row instead of returning
            // an id that can never be previewed or exported.
            val existingDestination = File(directory, existing.fileName)
            if (!existingDestination.isFile || existingDestination.sha256() != hash) {
                replaceAtomically(existingDestination, bytes)
            }
            return@withContext existing.lutId
        }
        val baseName = parsed.title.ifBlank { displayName }.take(112)
        var uniqueName = baseName
        var suffix = 2
        while (dao.hasCustomLutNamed(uniqueName)) {
            uniqueName = "$baseName ($suffix)".take(120)
            suffix += 1
        }
        replaceAtomically(destination, bytes)
        try {
            dao.insertCustomLut(
                CustomLutEntity(
                    displayName = uniqueName,
                    fileName = fileName,
                    cubeSize = parsed.size,
                    sha256 = hash,
                    importedAtMillis = System.currentTimeMillis(),
                ),
            )
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    suspend fun load(id: Long): CubeLut? = withContext(Dispatchers.IO) {
        val entity = dao.customLut(id) ?: return@withContext null
        val file = File(directory, entity.fileName)
        if (!file.isFile) return@withContext null
        if (file.sha256() != entity.sha256) return@withContext null
        file.reader().use { CubeLutParser.parse(it, entity.displayName) }
    }

    suspend fun summaries(): List<com.ugallery.core.editing.video.CustomLutOption> = withContext(Dispatchers.IO) {
        dao.customLuts().first()
            .filter {
                val file = File(directory, it.fileName)
                file.isFile && file.sha256() == it.sha256
            }
            .map { com.ugallery.core.editing.video.CustomLutOption(it.lutId, it.displayName, it.cubeSize) }
    }

    private fun replaceAtomically(destination: File, bytes: ByteArray) {
        val temporary = File.createTempFile("lut-", ".tmp", directory).apply { writeBytes(bytes) }
        try {
            if (!temporary.renameTo(destination)) {
                check(!destination.exists() || destination.delete()) { "LUT file could not be replaced" }
                check(temporary.renameTo(destination)) { "LUT file could not be stored" }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1_024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object { const val MaxBytes = 8 * 1_024 * 1_024 }
}
