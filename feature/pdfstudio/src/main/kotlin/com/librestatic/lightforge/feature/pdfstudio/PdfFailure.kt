package com.librestatic.lightforge.feature.pdfstudio

import java.io.FileNotFoundException
import java.io.IOException

/** Stable persisted codes; provider messages, paths and exception text never enter UI copy. */
enum class PdfFailure(val message: Int) {
    AccessDenied(R.string.pdf_failure_access),
    MissingFile(R.string.pdf_failure_missing),
    UnsupportedFormat(R.string.pdf_failure_format),
    LimitExceeded(R.string.pdf_failure_limit),
    /** Specifically "this page already has the max photos allowed" (Media panel insert, Phase F
     * item 3 review fix): distinct from the generic [LimitExceeded] so the issue card can say
     * exactly what's full instead of the generic "exceeds the project or import limit" text. */
    PageFull(R.string.pdf_failure_page_full),
    StorageFull(R.string.pdf_failure_storage),
    MemoryPressure(R.string.pdf_failure_memory),
    RendererUnavailable(R.string.pdf_failure_renderer),
    Interrupted(R.string.pdf_failure_interrupted),
    EncryptedPdf(R.string.pdf_failure_encrypted),
    InvalidPdf(R.string.pdf_failure_invalid_pdf),
    DestinationChanged(R.string.pdf_failure_destination),
    ImportTargetMissing(R.string.pdf_failure_import_target),
    InvalidInput(R.string.pdf_failure_input),
    /** A text element contains a character the bundled fonts cannot render, or that needs shaping
     * PDFBox doesn't perform (Phase G1a's [PdfTextSupport]). */
    UnsupportedGlyph(R.string.pdf_failure_unsupported_glyph),
    Unknown(R.string.pdf_error);

    companion object {
        fun from(error: Throwable): PdfFailure {
            val chain = generateSequence(error) { it.cause }.take(12).toList()
            chain.filterIsInstance<PdfOperationFailure>().firstOrNull()?.let {
                return it.failure
            }
            if (chain.any { it.javaClass.simpleName == "InvalidPasswordException" })
                return EncryptedPdf
            if (
                chain.any {
                    it is SecurityException ||
                        (it is IOException &&
                            (it.message?.contains("EACCES") == true ||
                                it.message?.contains("EPERM") == true))
                }
            )
                return AccessDenied
            if (chain.any { it is OutOfMemoryError }) return MemoryPressure
            if (
                chain.any {
                    it.javaClass.simpleName == "SQLiteFullException" ||
                        (it is IOException && it.message?.contains("ENOSPC") == true)
                }
            )
                return StorageFull
            if (chain.any { it is FileNotFoundException }) return MissingFile
            if (
                chain.any {
                    it.javaClass.simpleName in setOf("DeadObjectException", "RemoteException")
                }
            )
                return RendererUnavailable
            if (
                chain.any {
                    it.message in
                        setOf(
                            "DestinationChanged",
                            "DestinationNotEmpty",
                            "DestinationInUse",
                            "DestinationVerificationFailed",
                        )
                }
            )
                return DestinationChanged
            if (chain.any { it is IllegalArgumentException }) return InvalidInput
            return Unknown
        }

        fun remote(code: String, fallback: PdfFailure = Unknown): PdfFailure =
            entries.firstOrNull { it.name == code && it != Unknown }
                ?: when (code) {
                    "InvalidPasswordException" -> EncryptedPdf
                    "OutOfMemoryError" -> MemoryPressure
                    else -> fallback
                }

        fun persisted(code: String?): PdfFailure =
            entries.firstOrNull { it.name == code }
                ?: when (code) {
                    "SecurityException" -> AccessDenied
                    "FileNotFoundException" -> MissingFile
                    else -> Unknown
                }
    }
}

internal class PdfOperationFailure(val failure: PdfFailure) : IOException(failure.name)

internal class PdfSourceFailure(val number: Int, cause: Exception) :
    IOException("Source $number", cause)
