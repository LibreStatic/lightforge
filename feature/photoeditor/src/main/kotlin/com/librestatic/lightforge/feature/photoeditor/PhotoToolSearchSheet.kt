package com.librestatic.lightforge.feature.photoeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing

internal fun PhotoToolDestination.searchKey(): String =
    listOfNotNull(tool.name.lowercase(), adjustTile?.token, cropTool?.token, looksTab?.name?.lowercase()).joinToString("-")

/** Bottom sheet with a text field that filters every tool by its localized name; a tap navigates to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoToolSearchSheet(
    entries: List<PhotoToolEntry>,
    onDismiss: () -> Unit,
    onSelect: (PhotoToolDestination) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val labels = entries.associateWith { stringResource(it.labelRes) }
    val results = filterToolEntries(entries, query) { labels.getValue(it) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("photo-tool-search-sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = GallerySpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.photo_editor_search_tools)) },
                leadingIcon = { Icon(GalleryIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(GalleryIcons.Close, contentDescription = stringResource(R.string.photo_editor_search_clear))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("photo-tool-search-field"),
            )
            if (results.isEmpty()) {
                Text(
                    stringResource(R.string.photo_editor_search_empty, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = GallerySpacing.Md),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp).testTag("photo-tool-search-results")) {
                    items(results, key = { it.destination.searchKey() }) { entry ->
                        ListItem(
                            headlineContent = { Text(labels.getValue(entry)) },
                            leadingContent = { Icon(entry.icon, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .testTag("photo-tool-result-${entry.destination.searchKey()}")
                                .clickable { onSelect(entry.destination) },
                        )
                    }
                }
            }
        }
    }
}
