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
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        dao.customLutByHash(hash)?.let { return@withContext it.lutId }
        val fileName = "$hash.cube"
        val baseName = parsed.title.ifBlank { displayName }.take(112)
        var uniqueName = baseName
        var suffix = 2
        while (dao.hasCustomLutNamed(uniqueName)) {
            uniqueName = "$baseName ($suffix)".take(120)
            suffix += 1
        }
        val destination = File(directory, fileName)
        val temporary = File.createTempFile("lut-", ".tmp", directory).apply { writeBytes(bytes) }
        check(temporary.renameTo(destination)) { "LUT file could not be stored" }
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
        file.reader().use { CubeLutParser.parse(it, entity.displayName) }
    }

    suspend fun summaries(): List<com.ugallery.core.editing.video.CustomLutOption> = withContext(Dispatchers.IO) {
        dao.customLuts().first()
            .map { com.ugallery.core.editing.video.CustomLutOption(it.lutId, it.displayName, it.cubeSize) }
    }

    private companion object { const val MaxBytes = 8 * 1_024 * 1_024 }
}
