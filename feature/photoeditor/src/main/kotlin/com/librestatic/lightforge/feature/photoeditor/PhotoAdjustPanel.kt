package com.librestatic.lightforge.feature.photoeditor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSlider
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSliderRules
import com.librestatic.lightforge.core.designsystem.EditorSliderIcon
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.model.AdjustKey
import com.librestatic.lightforge.core.model.EditOperation
import kotlin.math.roundToInt

/** Sections of the stacked Adjust list; Color mix closes the list under its own heading. */
private val AdjustSections: List<Pair<Int, List<PhotoAdjustTile>>> = listOf(
    R.string.photo_editor_adjust_section_light to listOf(
        PhotoAdjustTile.Brightness, PhotoAdjustTile.Contrast, PhotoAdjustTile.WhitePoint, PhotoAdjustTile.Highlights,
        PhotoAdjustTile.Shadows, PhotoAdjustTile.BlackPoint, PhotoAdjustTile.Tone,
    ),
    R.string.photo_editor_adjust_section_color to listOf(
        PhotoAdjustTile.Saturation, PhotoAdjustTile.Warmth, PhotoAdjustTile.Tint,
        PhotoAdjustTile.SkinTone, PhotoAdjustTile.BlueTone,
    ),
    R.string.photo_editor_adjust_section_effects to listOf(PhotoAdjustTile.Vignette),
    R.string.photo_editor_adjust_color_mix to listOf(PhotoAdjustTile.ColorMix),
)

/**
 * Adjust tab: every slider stacked under Light / Color / Effects / Color mix headings. Each tile
 * of the old carousel held a single slider, so the list reaches any of them without a tap first.
 */
@Composable
internal fun AdjustControls(
    state: PhotoEditorContentState,
    tile: PhotoAdjustTile,
    actions: PhotoPanelActions,
) {
    val requesters = remember { PhotoAdjustTile.entries.associateWith { BringIntoViewRequester() } }
    // Search names a tile: scroll its slider into view once the list has been laid out.
    LaunchedEffect(tile) {
        withFrameNanos { }
        requesters.getValue(tile).bringIntoView()
    }
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Lg)) {
        AdjustSections.forEach { (title, tiles) ->
            Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Md)) {
                AdjustSectionHeading(title)
                tiles.forEach { entry ->
                    val key = entry.key
                    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requesters.getValue(entry))) {
                        if (key == null) {
                            ColorMixControls(state.colorMix, actions)
                        } else {
                            AdjustSlider(state, entry, key, actions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdjustSectionHeading(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun AdjustSlider(
    state: PhotoEditorContentState,
    tile: PhotoAdjustTile,
    key: AdjustKey,
    actions: PhotoPanelActions,
) {
    EditorAdjustmentSlider(
        label = stringResource(tile.labelRes),
        value = state.adjust[key] * 100f,
        onValueChange = { actions.onAdjustPreview(state.adjust.withValue(key, it / 100f)) },
        onValueChangeFinished = actions.onAdjustChangeFinished,
        valueRange = -100f..100f,
        displayValue = { EditorAdjustmentSliderRules.format(it, 0f, decimals = 0) },
        leading = { EditorSliderIcon(tile.icon) },
        testTag = "photo-adjust-slider-${tile.token}",
        modifier = Modifier.fillMaxWidth(),
    )
}

/** [value] is clamped to -1..1 and rounded to a whole percent; zero removes the entry. */
internal fun EditOperation.Adjust.withValue(key: AdjustKey, value: Float): EditOperation.Adjust =
    EditOperation.Adjust(values + (key to ((value * 100f).roundToInt() / 100f).coerceIn(-1f, 1f)))
