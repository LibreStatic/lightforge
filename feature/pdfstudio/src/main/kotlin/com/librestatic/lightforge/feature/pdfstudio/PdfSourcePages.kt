package com.librestatic.lightforge.feature.pdfstudio

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.Closeable

/** One parsed source at a time; destination streams never borrow the source scratch buffer. */
internal class PdfSourcePages(
    private val destination: PDDocument,
    private val open: (String) -> PDDocument,
) : Closeable {
    private var key: String? = null
    private var source: PDDocument? = null
    private var cloner: PDFCloneUtility? = null

    fun append(hash: String, page: Int, rotation: Int) {
        if (key != hash) {
            close()
            val next = open(hash)
            source = next
            key = hash
            cloner = PDFCloneUtility(destination)
        }
        val input = requireNotNull(source)
        if (input.isEncrypted) throw PdfOperationFailure(PdfFailure.EncryptedPdf)
        val original = input.getPage(page)
        val clone = requireNotNull(cloner)
        val copy = PDPage()
        // Resolve inherited page properties without copying Parent and the entire source page tree.
        val properties =
            listOf(
                COSName.CONTENTS to original.cosObject.getDictionaryObject(COSName.CONTENTS),
                COSName.RESOURCES to original.resources?.cosObject,
                COSName.MEDIA_BOX to original.mediaBox.cosArray,
                COSName.CROP_BOX to original.cropBox.cosArray,
                COSName.BLEED_BOX to original.bleedBox.cosArray,
                COSName.TRIM_BOX to original.trimBox.cosArray,
                COSName.ART_BOX to original.artBox.cosArray,
                COSName.GROUP to original.cosObject.getDictionaryObject(COSName.GROUP),
            )
        properties.forEach { (name, value) ->
            if (value != null) copy.cosObject.setItem(name, clone.cloneForNewDocument(value))
        }
        copy.rotation = (original.rotation + rotation) % 360
        copy.userUnit = original.userUnit
        // Export static pages, not source annotations, page actions or signature promises.
        destination.addPage(copy)
    }

    override fun close() {
        val previous = source
        source = null
        key = null
        cloner = null
        previous?.close()
    }
}
