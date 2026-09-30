package com.librestatic.lightforge.feature.pdfstudio

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only disposable previews live here; sources/projects never enter the eviction set. */
internal class PdfDiskPreviewCache(
    private val parent: File,
    private val maxBytes: Long = PdfPreviewPolicy.DISK_BYTES,
    private val maxEntries: Int = PdfPreviewPolicy.DISK_ENTRIES,
    private val freeBytes: () -> Long = { parent.usableSpace },
) {
    private val directory = File(parent, "pdf-previews-v1")

    init {
        require(maxBytes > 0 && maxEntries > 0)
    }

    suspend fun <T> read(
        hash: String,
        page: Int,
        render: suspend (File) -> Unit,
        decode: suspend (File) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            require(hash.matches(Regex("[a-f0-9]{64}")) && page in 0 until 100)
            gate.withLock {
                directory.mkdirs()
                // This lock spans generation and decoding, so eviction cannot race an active
                // reader.
                directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                parent
                    .listFiles()
                    ?.filter {
                        it.name.matches(
                            Regex("pdf-preview-(?:[a-f0-9]{64}-[0-9]+\\.png|[a-f0-9-]{36}\\.tmp)")
                        )
                    }
                    ?.forEach { it.delete() }
                val target = File(directory, "$hash-$page.png")
                if (validSizeAndHeader(target)) {
                    try {
                        val value = decode(target)
                        touch(target)
                        prune(target)
                        return@withLock value
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is PdfOperationFailure && e.failure == PdfFailure.MemoryPressure)
                            throw e
                        target.delete()
                    } // A damaged cached PNG is regenerated once.
                } else target.delete()
                val requiredFree =
                    PdfPreviewPolicy.FREE_BYTES + minOf(maxBytes, PdfPreviewPolicy.ENTRY_BYTES)
                prune(requiredFree = requiredFree)
                if (freeBytes() < requiredFree) throw PdfOperationFailure(PdfFailure.StorageFull)
                val temporary = File(directory, "${newId()}.part")
                try {
                    render(temporary)
                    currentCoroutineContext().ensureActive()
                    check(validSizeAndHeader(temporary)) { "Invalid preview cache output" }
                    check(temporary.renameTo(target))
                    try {
                        val result = decode(target)
                        currentCoroutineContext().ensureActive()
                        touch(target)
                        prune(target)
                        result
                    } catch (e: Exception) {
                        if (e !is PdfOperationFailure || e.failure != PdfFailure.MemoryPressure)
                            target.delete()
                        throw e
                    }
                } finally {
                    temporary.delete()
                }
            }
        }

    private fun validSizeAndHeader(file: File): Boolean =
        file.isFile &&
            file.length() in 8..minOf(maxBytes, PdfPreviewPolicy.ENTRY_BYTES) &&
            file.inputStream().use { input ->
                val header = ByteArray(8)
                input.read(header) == 8 &&
                    header.contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            }

    private fun touch(file: File) {
        val newest = directory.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
        file.setLastModified(maxOf(System.currentTimeMillis(), newest + 1))
    }

    private fun prune(protected: File? = null, requiredFree: Long = PdfPreviewPolicy.FREE_BYTES) {
        val files =
            directory
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(".png") }
                ?.sortedBy { it.lastModified() }
                .orEmpty()
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (bytes <= maxBytes && count <= maxEntries && freeBytes() >= requiredFree) break
            val size = file.length()
            if (file != protected && file.delete()) {
                bytes -= size
                count--
            }
        }
    }

    companion object {
        private val gate = Mutex()
    }
}
