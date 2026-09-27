package com.librestatic.lightforge.feature.pdfstudio

import java.io.File

/** Keep room for project/Room metadata; recheck unknown-length inputs before each chunk. */
internal class PdfStorageBudget(private val freeBytes: (File) -> Long = { it.usableSpace }) {
    fun beforeWrite(output: File, bytes: Long = 64 * 1024) {
        require(bytes >= 0)
        val free = freeBytes(requireNotNull(output.absoluteFile.parentFile))
        if (free < RESERVE_BYTES || bytes > free - RESERVE_BYTES)
            throw PdfOperationFailure(PdfFailure.StorageFull)
    }

    companion object {
        const val RESERVE_BYTES = 8L * 1024 * 1024
    }
}
