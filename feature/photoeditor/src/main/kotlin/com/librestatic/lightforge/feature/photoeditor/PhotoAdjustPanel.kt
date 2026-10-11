package com.librestatic.lightforge.feature.photoeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSlider
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSliderRules
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.model.AdjustKey
import com.librestatic.lightforge.core.model.EditOperation
import kotlin.math.roundToInt

/** Adjust tab: a carousel with one tile per slider and a single slider for the selected tile. */
@Composable
internal fun AdjustControls(
    state: PhotoEditorContentState,
    tile: PhotoAdjustTile,
    onTileChange: (PhotoAdjustTile) -> Unit,
    actions: PhotoPanelActions,
) {
    val listState = rememberLazyListState()
    // Search can jump to a tile that is off screen.
    LaunchedEffect(tile) {
        val index = PhotoAdjustTile.entries.indexOf(tile)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.animateScrollToItem(index)
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(PhotoAdjustTile.entries, key = { it.name }) { entry ->
            PhotoToolTile(
                icon = entry.icon,
                label = stringResource(entry.labelRes),
                selected = entry == tile,
                modified = entry.isModified(state),
                onClick = { onTileChange(entry) },
                testTag = "photo-adjust-${entry.token}",
            )
        }
    }
    val key = tile.key
    if (key == null) {
        ColorMixControls(state.colorMix, actions)
    } else {
        EditorAdjustmentSlider(
            label = stringResource(tile.labelRes),
            value = state.adjust[key] * 100f,
            onValueChange = { actions.onAdjustPreview(state.adjust.withValue(key, it / 100f)) },
            onValueChangeFinished = actions.onAdjustChangeFinished,
            valueRange = -100f..100f,
            displayValue = { EditorAdjustmentSliderRules.format(it, 0f, decimals = 0) },
            leading = { Icon(tile.icon, contentDescription = null) },
            testTag = "photo-adjust-slider",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun PhotoAdjustTile.isModified(state: PhotoEditorContentState): Boolean =
    key?.let { state.adjust[it] != 0f } ?: !state.colorMix.isNeutral

/** [value] is clamped to -1..1 and rounded to a whole percent; zero removes the entry. */
internal fun EditOperation.Adjust.withValue(key: AdjustKey, value: Float): EditOperation.Adjust =
    EditOperation.Adjust(values + (key to ((value * 100f).roundToInt() / 100f).coerceIn(-1f, 1f)))
