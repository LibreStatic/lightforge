package com.librestatic.lightforge.feature.pdfstudio

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class PdfDestinationState {
    Missing,
    Empty,
    Partial,
    Complete,
    Different,
}

/**
 * Only an exact complete result or a byte-for-byte prefix can be attributed to this publication.
 */
internal suspend fun inspectPdfDestination(
    resolver: ContentResolver,
    uri: Uri,
    job: PdfExportJob,
    source: File,
): PdfDestinationState {
    val input =
        try {
            resolver.openInputStream(uri) ?: throw FileNotFoundException()
        } catch (e: FileNotFoundException) {
            return PdfDestinationState.Missing
        }
    return input.use { actual ->
        val reference =
            if (
                source.isFile &&
                    source.length() == job.outputBytes &&
                    PdfProjectRepository.sha256(source) == job.outputHash
            )
                source.inputStream()
            else null
        reference.use { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            val compare = ByteArray(buffer.size)
            var count = 0L
            var prefix = expected != null
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = actual.read(buffer)
                if (n < 0) break
                count += n
                if (count > job.outputBytes) return@use PdfDestinationState.Different
                digest.update(buffer, 0, n)
                if (prefix) {
                    var offset = 0
                    while (offset < n) {
                        val got = expected!!.read(compare, offset, n - offset)
                        if (got < 0) break
                        offset += got
                    }
                    if (offset != n || (0 until n).any { buffer[it] != compare[it] }) prefix = false
                }
            }
            when {
                count == 0L -> PdfDestinationState.Empty
                job.outputHash != null &&
                    count == job.outputBytes &&
                    digest.digest().joinToString("") { "%02x".format(it) } == job.outputHash ->
                    PdfDestinationState.Complete
                prefix && count < job.outputBytes -> PdfDestinationState.Partial
                else -> PdfDestinationState.Different
            }
        }
    }
}
