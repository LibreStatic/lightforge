package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfPagesPanel(vm: PdfStudioViewModel, s: PdfStudioState, delete: () -> Unit) {
    val p = s.project ?: return
    Text(stringResource(R.string.pdf_pages), style = MaterialTheme.typography.titleMedium)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items(p.pages, key = { it.id }) { page ->
            val n = p.pages.indexOf(page)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    page.id in s.selectedPages,
                    { vm.selectExportPage(page.id, it) },
                    enabled = !s.editorLocked,
                )
                OutlinedButton(
                    onClick = { vm.selectPage(n) },
                    enabled = !s.editorLocked,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PageThumbnail(page, vm, Modifier.size(52.dp, 72.dp))
                        Text("${n+1}${if(n==s.page) " •" else ""}")
                    }
                }
            }
        }
    }
    FlowRow {
        TextButton(onClick = vm::addPage, enabled = !s.editorLocked && p.pages.size < 100) {
            Text(stringResource(R.string.pdf_addpage))
        }
        TextButton(onClick = vm::duplicatePage, enabled = !s.editorLocked && p.pages.size < 100) {
            Text(stringResource(R.string.pdf_duplicatepage))
        }
        TextButton(onClick = delete, enabled = !s.editorLocked) {
            Text(stringResource(R.string.pdf_removepage))
        }
        TextButton(onClick = { vm.movePage(-1) }, enabled = !s.editorLocked && s.page > 0) {
            Text(stringResource(R.string.pdf_pagebefore))
        }
        TextButton(
            onClick = { vm.movePage(1) },
            enabled = !s.editorLocked && s.page < p.pages.lastIndex,
        ) {
            Text(stringResource(R.string.pdf_pageafter))
        }
    }
}
