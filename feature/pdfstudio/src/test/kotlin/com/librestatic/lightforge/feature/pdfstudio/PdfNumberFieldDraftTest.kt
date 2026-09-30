package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfNumberFieldDraftTest {
    @Test fun focusAndImeWithoutEditsNeverRoundFractionalModelValue() {
        val exact = 86.76883172607422
        var draft = PdfNumberFieldDraft.fromValue(exact)
        assertEquals("86.77", draft.text)
        repeat(40) {
            val commit = draft.commit()
            assertNull(commit.value)
            draft = commit.draft
        }
        assertNull(draft.edit(draft.text).commit().value)
    }

    @Test fun editedCommaDecimalEmitsOnceAcrossImeAndBlur() {
        val result = PdfNumberFieldDraft.fromValue(86.76883172607422).edit("50,25").commit()
        assertEquals(50.25, result.value!!, 0.0)
        assertNull(result.draft.commit().value)
        // The parent's accepted new value formats cleanly, rather than re-emitting on blur.
        val accepted = PdfNumberFieldDraft.fromValue(result.value)
        assertEquals("50.25", accepted.text)
        assertNull(accepted.commit().value)
    }

    @Test fun zeroAndFiniteScientificInputRemainAccepted() {
        assertEquals(0.0, PdfNumberFieldDraft.fromValue(9.0).edit("0").commit().value!!, 0.0)
        assertEquals(125.0, PdfNumberFieldDraft.fromValue(9.0).edit("1.25e2").commit().value!!, 0.0)
    }

    @Test fun invalidNonfiniteAndNegativeInputNeverChangeModelOrRetryOnBlur() {
        for (text in listOf("", "-", "NaN", "Infinity", "1e999", "-1", "3,4,5")) {
            val result = PdfNumberFieldDraft.fromValue(12.345678).edit(text).commit()
            assertNull(text, result.value)
            assertEquals("12.35", result.draft.text)
            assertNull(result.draft.commit().value)
        }
    }

    @Test fun numericallyUnchangedUserEditDoesNotEmit() {
        val result = PdfNumberFieldDraft.fromValue(50.0).edit("50,000").commit()
        assertNull(result.value)
        assertEquals("50.00", result.draft.text)
    }

    @Test fun replacingDraftForNewExternalValueDiscardsOldEdit() {
        var draft = PdfNumberFieldDraft.fromValue(86.76883172607422).edit("45")
        assertEquals("45", draft.text)
        // NumberField uses remember(value); a new external value supplies this fresh draft,
        // even if the prior field is focused. Its later blur must not replay the old edit.
        draft = PdfNumberFieldDraft.fromValue(100.123456789)
        assertEquals("100.12", draft.text)
        assertNull(draft.commit().value)
    }

    @Test fun laterUserEditCanCommitAfterPreviousEditWasConsumed() {
        val first = PdfNumberFieldDraft.fromValue(30.0).edit("31").commit()
        assertEquals(31.0, first.value!!, 0.0)
        val second = PdfNumberFieldDraft.fromValue(31.0).edit("32").commit()
        assertEquals(32.0, second.value!!, 0.0)
        assertNull(second.draft.commit().value)
    }
}
