package com.ugallery.feature.privatealbum

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import com.ugallery.core.security.PrivateAlbumCrypto
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateAlbumContent(
    repository: PrivateAlbumRepository,
    onBack: () -> Unit,
    onExport: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaList by repository.allMedia.collectAsState(initial = emptyList())
    var isUnlocked by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf<String?>(null) }
    var showSetupWarning by remember { mutableStateOf(false) }

    // FLAG_SECURE: prevent screenshots while private album is visible
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(Unit) {
        val setup = repository.isSetup()
        if (!setup) showSetupWarning = true
    }

    if (showSetupWarning) {
        AlertDialog(
            onDismissRequest = { showSetupWarning = false },
            title = { Text("Private Album Setup") },
            text = {
                Text(
                    "Moving media to the private album encrypts it with your biometric. " +
                    "If you uninstall the app, this data cannot be recovered. " +
                    "Always export important items back to your library before uninstalling."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showSetupWarning = false
                    isUnlocked = true
                    scope.launch { repository.setup(PrivateAlbumCrypto.getOrCreateMasterKey()) }
                }) { Text("I Understand") }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text("Cancel") }
            },
        )
        return
    }

    if (!isUnlocked) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Private album is locked", style = MaterialTheme.typography.titleMedium)
                Text("Authenticate with biometrics to unlock", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { isUnlocked = true },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Unlock") }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Private Album (${mediaList.size})") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        if (mediaList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No private items yet.\nMove media here from the viewer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(mediaList, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier.padding(2.dp),
                        onClick = { onExport(item.id) },
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                item.originalDisplayName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            Text(
                                if (item.mediaKind == "video") "Video" else "Photo",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }

    showError?.let { error ->
        AlertDialog(
            onDismissRequest = { showError = null },
            title = { Text("Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { showError = null }) { Text("OK") }
            },
        )
    }
}
