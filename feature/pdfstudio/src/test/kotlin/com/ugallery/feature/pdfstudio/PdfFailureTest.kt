package com.ugallery.feature.pdfstudio

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class PdfFailureTest {
    @Test
    fun wrappedSourceRetainsAccessAndStorageClassification() {
        assertEquals(
            PdfFailure.AccessDenied,
            PdfFailure.from(PdfSourceFailure(2, SecurityException("provider private text"))),
        )
        assertEquals(
            PdfFailure.StorageFull,
            PdfFailure.from(FileNotFoundException("open failed: ENOSPC")),
        )
        assertEquals(
            PdfFailure.AccessDenied,
            PdfFailure.from(FileNotFoundException("open failed: EACCES (Permission denied)")),
        )
        assertEquals(PdfFailure.AccessDenied, PdfFailure.from(IOException("write failed: EPERM")))
        assertEquals(PdfFailure.MissingFile, PdfFailure.from(FileNotFoundException("gone")))
    }

    @Test
    fun stableCodesAndLegacyFailuresAreReadableWithoutRawMessages() {
        assertEquals(
            PdfFailure.DestinationChanged,
            PdfFailure.from(IllegalStateException("DestinationVerificationFailed")),
        )
        assertEquals(PdfFailure.AccessDenied, PdfFailure.persisted("SecurityException"))
        assertEquals(PdfFailure.Unknown, PdfFailure.persisted("private provider failure text"))
        for (failure in PdfFailure.entries) assertEquals(
            failure,
            PdfFailure.persisted(failure.name),
        )
    }

    @Test
    fun explicitFormatAndLimitReasonsSurviveWrapping() {
        assertEquals(
            PdfFailure.UnsupportedFormat,
            PdfFailure.from(PdfSourceFailure(4, PdfOperationFailure(PdfFailure.UnsupportedFormat))),
        )
        assertEquals(
            PdfFailure.LimitExceeded,
            PdfFailure.from(PdfOperationFailure(PdfFailure.LimitExceeded)),
        )
    }
}
