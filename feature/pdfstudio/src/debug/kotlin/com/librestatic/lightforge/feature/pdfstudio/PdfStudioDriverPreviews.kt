package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.LightforgeTheme

/** Text inspector's alignment choice. Driver: `PdfTextAlignPreview`. */
@Preview
@Composable
fun PdfTextAlignPreview() = DriverFrame {
    var align by remember { mutableStateOf(PdfTextAlign.Center) }
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.pdf_text_align))
        PdfTextAlignChoice(selected = align, enabled = true, onSelect = { align = it })
    }
}

/** Delete-project confirmation with a 200-character name. Driver: `PdfDeleteProjectDialogPreview`. */
@Preview
@Composable
fun PdfDeleteProjectDialogPreview() = DriverFrame {
    val name = "Quarterly scanned invoices and receipts ".repeat(6).take(200)
    Confirm(
        stringResource(R.string.pdf_deleteproject),
        stringResource(R.string.pdf_deleteproject_action),
        {},
        body = stringResource(R.string.pdf_library_delete_confirm, name),
    ) {}
}

@Composable
private fun DriverFrame(content: @Composable () -> Unit) {
    LightforgeTheme { Surface(Modifier.fillMaxSize(), content = content) }
}
