package com.ugallery.feature.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val DefaultCollectionLayoutOrder = listOf(
    "documents", "people", "archive", "trash", "virtual-albums", "physical-albums",
    "dogs", "cats", "memories", "all-memories", "create-album", "private-album",
    "memory-controls", "smart-albums", "photo-stacks", "pdf-studio", "collage", "local-analysis",
)

internal fun normalizedCollectionLayoutOrder(order: List<String>): List<String> =
    order.filter { it in DefaultCollectionLayoutOrder }.distinct().let { known ->
        known + DefaultCollectionLayoutOrder.filterNot { it in known }
    }

/** Draft-only editor. Disabled/unavailable features remain listed and never lose preferences. */
@Composable
fun CollectionLayoutDialog(
    order: List<String>,
    hidden: Set<String>,
    labels: Map<String, String>,
    available: Set<String>,
    working: Boolean,
    failed: Boolean,
    onSave: (List<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftOrder by remember(order, hidden) { mutableStateOf(normalizedCollectionLayoutOrder(order)) }
    var draftHidden by remember(order, hidden) { mutableStateOf(hidden.intersect(DefaultCollectionLayoutOrder.toSet())) }
    var unknownOrder by remember(order, hidden) { mutableStateOf(order.filterNot { it in DefaultCollectionLayoutOrder }.distinct()) }
    var unknownHidden by remember(order, hidden) { mutableStateOf(hidden - DefaultCollectionLayoutOrder.toSet()) }
    fun move(id: String, delta: Int) {
        if (working) return
        val index = draftOrder.indexOf(id)
        val target = index + delta
        if (index < 0 || target !in draftOrder.indices) return
        draftOrder = draftOrder.toMutableList().apply { removeAt(index); add(target, id) }
    }
    Dialog(onDismissRequest = { if (!working) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !working, dismissOnClickOutside = !working)) {
        Surface(modifier = Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.collection_layout_manage), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                LazyColumn(Modifier.weight(1f).testTag("collection-layout-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "explanation") { Text(stringResource(R.string.collection_layout_body)) }
                    item(key = "global-actions") {
                        Column {
                            TextButton(onClick = { draftHidden = emptySet() }, enabled = !working,
                                modifier = Modifier.testTag("collection-layout-show-all")) { Text(stringResource(R.string.collection_layout_show_all)) }
                            TextButton(onClick = { draftOrder = DefaultCollectionLayoutOrder; draftHidden = emptySet(); unknownOrder = emptyList(); unknownHidden = emptySet() }, enabled = !working,
                                modifier = Modifier.testTag("collection-layout-reset")) { Text(stringResource(R.string.collection_layout_reset)) }
                        }
                    }
                    itemsIndexed(draftOrder, key = { _, id -> id }) { index, id ->
                        val label = labels.getValue(id)
                        Column(Modifier.fillMaxWidth().testTag("collection-layout-$id")) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.collection_layout_position, index + 1, draftOrder.size))
                            if (id !in available) Text(stringResource(R.string.collection_layout_unavailable))
                            val toggleLabel = stringResource(R.string.collection_layout_show_label, label)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(if (id in draftHidden) R.string.collection_layout_hidden else R.string.collection_layout_visible),
                                    modifier = Modifier.weight(1f))
                                Switch(checked = id !in draftHidden, enabled = !working,
                                    onCheckedChange = { show -> if (!working) draftHidden = if (show) draftHidden - id else draftHidden + id },
                                    modifier = Modifier.testTag("collection-layout-toggle-$id").semantics { contentDescription = toggleLabel })
                            }
                            Row(Modifier.fillMaxWidth()) {
                                val upLabel = stringResource(R.string.collection_layout_move_up_label, label)
                                val downLabel = stringResource(R.string.collection_layout_move_down_label, label)
                                TextButton(onClick = { move(id, -1) }, enabled = !working && index > 0,
                                    modifier = Modifier.weight(1f).testTag("collection-layout-up-$id").semantics { contentDescription = upLabel }) {
                                    Text(stringResource(R.string.collection_layout_up))
                                }
                                TextButton(onClick = { move(id, 1) }, enabled = !working && index < draftOrder.lastIndex,
                                    modifier = Modifier.weight(1f).testTag("collection-layout-down-$id").semantics { contentDescription = downLabel }) {
                                    Text(stringResource(R.string.collection_layout_down))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                if (failed) Text(stringResource(R.string.collection_layout_failed), Modifier.testTag("collection-layout-error"))
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { if (!working) onDismiss() }, enabled = !working,
                        modifier = Modifier.weight(1f).testTag("collection-layout-cancel")) { Text(stringResource(R.string.moment_cancel)) }
                    TextButton(onClick = {
                        if (!working) {
                            check(draftOrder.size == DefaultCollectionLayoutOrder.size && draftOrder.toSet() == DefaultCollectionLayoutOrder.toSet())
                            check(draftHidden.all { it in DefaultCollectionLayoutOrder })
                            onSave(draftOrder + unknownOrder, draftHidden + unknownHidden)
                        }
                    }, enabled = !working, modifier = Modifier.weight(1f).testTag("collection-layout-save")) {
                        Text(stringResource(if (working) R.string.collection_layout_saving else R.string.moment_save))
                    }
                }
            }
        }
    }
}
