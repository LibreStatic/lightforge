package com.librestatic.lightforge.feature.photos

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveIconButton
import com.librestatic.lightforge.core.designsystem.GalleryHeights
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing

/**
 * The Photos filter row: what to show (chips), how it is ordered (a sort menu) and, on wide
 * windows, how many items the current view holds. Compact windows scroll the chips and keep the
 * sort button pinned at the end; wide windows put everything on one line.
 */
@Composable
internal fun PhotosFilterBar(
    filter: PhotosFilter,
    onFilterChange: (PhotosFilter) -> Unit,
    sort: PhotosSort,
    onSortChange: ((PhotosSort) -> Unit)?,
    totalCount: Int?,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = GalleryHeights.TouchTarget)
            .testTag("photos_filter_bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (wide && totalCount != null) {
            Text(
                pluralStringResource(R.plurals.library_summary, totalCount, totalCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(end = GallerySpacing.Lg),
            )
            Spacer(Modifier.weight(1f))
        }
        Row(
            Modifier
                .then(if (wide) Modifier else Modifier.weight(1f))
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotosFilter.entries.forEach { option ->
                val selected = option == filter
                FilterChip(
                    selected = selected,
                    onClick = { onFilterChange(option) },
                    label = { Text(stringResource(option.labelRes), maxLines = 1) },
                    leadingIcon = if (selected) {
                        { Icon(GalleryIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    modifier = Modifier.testTag("photos_filter_${option.name.lowercase()}"),
                )
            }
        }
        if (onSortChange != null) {
            Spacer(Modifier.width(GallerySpacing.Xs))
            SortMenu(sort, onSortChange, showLabel = wide)
        }
    }
}

@Composable
private fun SortMenu(sort: PhotosSort, onSortChange: (PhotosSort) -> Unit, showLabel: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val current = stringResource(sort.labelRes)
    val description = stringResource(R.string.photos_sort_current, current)
    Box {
        if (showLabel) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.semantics { contentDescription = description }.testTag("photos_sort"),
            ) {
                Icon(GalleryIcons.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(GallerySpacing.Sm))
                Text(current, maxLines = 1)
                Icon(GalleryIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        } else {
            GalleryExpressiveIconButton(onClick = { expanded = true }, modifier = Modifier.testTag("photos_sort")) {
                Icon(GalleryIcons.Sort, contentDescription = description)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PhotosSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        expanded = false
                        onSortChange(option)
                    },
                    trailingIcon = if (option == sort) {
                        { Icon(GalleryIcons.Check, contentDescription = null) }
                    } else null,
                    modifier = Modifier.testTag("photos_sort_${option.name.lowercase()}"),
                )
            }
        }
    }
}

private val PhotosFilter.labelRes: Int
    get() = when (this) {
        PhotosFilter.All -> R.string.photos_filter_all
        PhotosFilter.Photos -> R.string.photos_filter_photos
        PhotosFilter.Videos -> R.string.photos_filter_videos
        PhotosFilter.Animated -> R.string.photos_filter_animated
        PhotosFilter.Raw -> R.string.photos_filter_raw
    }

private val PhotosSort.labelRes: Int
    get() = when (this) {
        PhotosSort.Newest -> R.string.photos_sort_newest
        PhotosSort.Oldest -> R.string.photos_sort_oldest
        PhotosSort.RecentlyModified -> R.string.photos_sort_modified
        PhotosSort.Name -> R.string.photos_sort_name
        PhotosSort.Largest -> R.string.photos_sort_largest
    }
