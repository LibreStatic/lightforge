package com.ugallery.feature.pdfstudio

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Same UTF-8 byte ceiling for native PDF and portable export; independent of Binder's buffer. */
internal object PdfExportManifest {
    const val LIMIT = PdfPortableArchive.MANIFEST_LIMIT

    fun encode(project: PdfProject): ByteArray =
        PdfCodec.encode(project).toByteArray(Charsets.UTF_8).also {
            if (it.size > LIMIT) throw PdfOperationFailure(PdfFailure.LimitExceeded)
        }

    /** Read at most LIMIT + 1 bytes, including streams whose reported length is unknown. */
    fun read(input: InputStream): PdfProject {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, LIMIT + 1 - bytes.size()))
            if (count < 0) break
            if (bytes.size() + count > LIMIT) throw PdfOperationFailure(PdfFailure.LimitExceeded)
            bytes.write(buffer, 0, count)
        }
        val raw =
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                throw PdfOperationFailure(PdfFailure.InvalidInput)
            }
        return PdfCodec.decode(raw)
    }
}
