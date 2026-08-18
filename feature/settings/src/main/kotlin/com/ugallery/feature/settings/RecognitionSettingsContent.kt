package com.ugallery.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class AnalysisStatus { Ready, Running, Paused, Complete }

data class FaceAnalysisUiState(
    val consentGranted: Boolean = false,
    val paused: Boolean = false,
    val completedItems: Long = 0,
    val status: AnalysisStatus? = null,
)

@Composable
fun RecognitionSettingsContent(
    state: FaceAnalysisUiState,
    onEnable: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onAnalyzeAll: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.face_analysis_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.face_analysis_privacy), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.face_analysis_no_identity), color = MaterialTheme.colorScheme.primary)
        if (!state.consentGranted) {
            Button(onClick = onEnable) { Text(stringResource(R.string.face_analysis_enable)) }
        } else {
            if (state.status == AnalysisStatus.Running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.face_analysis_progress, state.completedItems))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (state.paused) onResume else onPause) {
                    Text(stringResource(if (state.paused) R.string.face_analysis_resume else R.string.face_analysis_pause))
                }
                OutlinedButton(onClick = onAnalyzeAll) { Text(stringResource(R.string.face_analysis_all)) }
            }
            Text(stringResource(R.string.face_analysis_all_requirements), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.face_analysis_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.face_analysis_delete_title)) },
        text = { Text(stringResource(R.string.face_analysis_delete_body)) },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDelete() }) {
                Text(stringResource(R.string.face_analysis_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.face_analysis_cancel)) }
        },
    )
}
