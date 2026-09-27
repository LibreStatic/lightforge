package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource

/**
 * The export quality dialog. This is a placeholder for the full [PdfExportSheet] destination-first
 * flow that phase B replaces it with; Phase A only moves it out of PdfStudioScreen.kt and wires it
 * to the new top bar's Export action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfExportDialog(
    hasSelectedPages: Boolean,
    onDismiss: () -> Unit,
    onExport: (compact: Boolean, selectedOnly: Boolean) -> Unit,
) {
    var compact by remember { mutableStateOf(false) }
    var selectedOnly by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_pdfexport)) },
        text = {
            Column {
                Toggle(stringResource(R.string.pdf_compactquality), compact) { compact = it }
                if (hasSelectedPages)
                    Toggle(stringResource(R.string.pdf_exportselected), selectedOnly) {
                        selectedOnly = it
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(compact, selectedOnly) }) {
                Text(stringResource(R.string.pdf_pdfexport))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pdf_cancel)) } },
    )
}
