package com.librestatic.lightforge.feature.pdfstudio

import java.util.Locale

/** Formatting for display never counts as an edit of the underlying millimeter value. */
@ConsistentCopyVisibility
internal data class PdfNumberFieldDraft private constructor(
    private val modelValue: Double,
    val text: String,
    private val edited: Boolean,
) {
    fun edit(value: String): PdfNumberFieldDraft =
        if (value == text) this else copy(text = value, edited = true)

    fun commit(): PdfNumberFieldCommit {
        val value = if (edited) text.replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0 && it != modelValue } else null
        return PdfNumberFieldCommit(fromValue(modelValue), value)
    }

    companion object {
        fun fromValue(value: Double) = PdfNumberFieldDraft(value, "%.2f".format(Locale.ROOT, value), false)
    }
}

internal data class PdfNumberFieldCommit(val draft: PdfNumberFieldDraft, val value: Double?)
