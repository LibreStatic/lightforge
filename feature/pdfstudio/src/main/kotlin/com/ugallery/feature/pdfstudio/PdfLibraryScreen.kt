package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The "no project open" list: create/import a project, or open/duplicate/delete an existing one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.PdfLibraryScreen(
    vm: PdfStudioViewModel,
    projects: List<PdfProjectRow>,
    busy: Boolean,
    pendingImport: Any?,
    onNewProject: () -> Unit,
    onImportProject: () -> Unit,
    onDeleteProject: (String) -> Unit,
) {
    FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onNewProject, enabled = !busy) {
            Text(stringResource(R.string.pdf_newproject))
        }
        OutlinedButton(onClick = onImportProject, enabled = !busy && pendingImport == null) {
            Text(stringResource(R.string.pdf_importproject))
        }
    }
    Text(
        stringResource(R.string.pdf_local),
        Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodySmall,
    )
    LazyColumn(
        Modifier.weight(1f).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(projects, key = { it.id }) { row ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                    FlowRow {
                        TextButton(onClick = { vm.open(row.id) }, enabled = !busy) {
                            Text(stringResource(R.string.pdf_open))
                        }
                        TextButton(onClick = { vm.duplicate(row.id) }, enabled = !busy) {
                            Text(stringResource(R.string.pdf_duplicateproject))
                        }
                        TextButton(
                            onClick = { onDeleteProject(row.id) },
                            enabled = !busy,
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                        ) {
                            Text(stringResource(R.string.pdf_remove))
                        }
                    }
                }
            }
        }
    }
}
