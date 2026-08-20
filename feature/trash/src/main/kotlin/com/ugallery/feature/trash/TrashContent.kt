package com.ugallery.feature.trash

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.ugallery.core.model.TimelineMedia
import java.util.Date

/** [visibleItems] is the bounded Paging window, never the complete trash selection. */
@Composable
fun TrashContent(
    visibleItems: List<TimelineMedia>,
    totalCount: Long,
    onRestore: (TimelineMedia) -> Unit,
    onDeletePermanently: (TimelineMedia) -> Unit,
    onEmptyTrash: () -> Unit,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.fillMaxSize().widthIn(max = 720.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeader) Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.trash_title), style = MaterialTheme.typography.headlineSmall)
            if (totalCount > 0) {
                Button(onClick = onEmptyTrash) { Text(stringResource(R.string.trash_empty)) }
            }
        }
        if (totalCount == 0L) {
            Text(stringResource(R.string.trash_empty_state))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visibleItems, key = { "${it.key.volumeName}:${it.key.mediaStoreId}" }) { media ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                      Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            media.dateExpiresMillis?.let {
                                stringResource(
                                    R.string.trash_expires_on,
                                    DateFormat.getMediumDateFormat(context).format(Date(it)),
                                )
                            } ?: stringResource(R.string.trash_expiry_unknown),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onRestore(media) }) { Text(stringResource(R.string.trash_restore)) }
                            TextButton(onClick = { onDeletePermanently(media) }) {
                                Text(stringResource(R.string.trash_delete_permanently))
                            }
                        }
                      }
                    }
                }
            }
        }
      }
    }
}
