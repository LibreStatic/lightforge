package com.librestatic.lightforge.feature.photoeditor

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSlider
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSliderRules
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.model.ColorMixBand
import com.librestatic.lightforge.core.model.ColorMixHue
import com.librestatic.lightforge.core.model.EditOperation
import kotlin.math.roundToInt

@get:StringRes
internal val ColorMixHue.labelRes: Int
    get() = when (this) {
        ColorMixHue.Red -> R.string.photo_editor_mix_red
        ColorMixHue.Orange -> R.string.photo_editor_mix_orange
        ColorMixHue.Yellow -> R.string.photo_editor_mix_yellow
        ColorMixHue.Green -> R.string.photo_editor_mix_green
        ColorMixHue.Aqua -> R.string.photo_editor_mix_aqua
        ColorMixHue.Blue -> R.string.photo_editor_mix_blue
        ColorMixHue.Purple -> R.string.photo_editor_mix_purple
        ColorMixHue.Magenta -> R.string.photo_editor_mix_magenta
    }

/** The swatch depicts the hue it edits, so it is the one place a hue-derived colour (not a semantic role) is used. */
internal fun ColorMixHue.swatchColor(): Color = Color.hsv(centerDegrees.toFloat(), 0.78f, 0.92f)

/** Replaces one band of the mix; every value is rounded to a whole percent. */
internal fun EditOperation.ColorMix.withBand(hue: ColorMixHue, band: ColorMixBand): EditOperation.ColorMix =
    EditOperation.ColorMix(bands.toMutableList().also { it[hue.ordinal] = band })

private fun Float.percent(): Float = (this * 100f).roundToInt() / 100f

/** Eight hue swatches and Hue / Saturation / Luminance sliders for the selected band. */
@Composable
internal fun ColorMixControls(mix: EditOperation.ColorMix, actions: PhotoPanelActions) {
    var selected by rememberSaveable { mutableStateOf(ColorMixHue.Red) }
    val band = mix[selected]
    val swatch: @Composable (ColorMixHue) -> Unit = { hue ->
        val isSelected = hue == selected
        val name = stringResource(hue.labelRes)
        val modifiedText = stringResource(R.string.photo_editor_adjust_modified)
        Box(
            Modifier
                .size(48.dp)
                .testTag("photo-colormix-${hue.token}")
                .selectable(selected = isSelected, role = Role.RadioButton, onClick = { selected = hue })
                .semantics {
                    contentDescription = name
                    if (!mix[hue].isNeutral) stateDescription = modifiedText
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(if (isSelected) 40.dp else 32.dp)
                    .background(hue.swatchColor(), CircleShape)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    ),
            )
            if (!mix[hue].isNeutral) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
    }
    // The eight swatches sit in two even rows instead of scrolling off the panel's edge.
    Column(Modifier.fillMaxWidth().padding(vertical = GallerySpacing.Xs)) {
        ColorMixHue.entries.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { row.forEach { swatch(it) } }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm), modifier = Modifier.fillMaxWidth()) {
        MixSlider(R.string.photo_editor_mix_hue, GalleryIcons.AdjustHue, band.hue, "hue", actions) {
            mix.withBand(selected, band.copy(hue = it))
        }
        MixSlider(R.string.photo_editor_saturation, GalleryIcons.AdjustSaturation, band.saturation, "saturation", actions) {
            mix.withBand(selected, band.copy(saturation = it))
        }
        MixSlider(R.string.photo_editor_mix_luminance, GalleryIcons.AdjustLuminance, band.luminance, "luminance", actions) {
            mix.withBand(selected, band.copy(luminance = it))
        }
    }
}

@Composable
private fun MixSlider(
    @StringRes label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Float,
    token: String,
    actions: PhotoPanelActions,
    update: (Float) -> EditOperation.ColorMix,
) {
    EditorAdjustmentSlider(
        label = stringResource(label),
        value = value * 100f,
        onValueChange = { actions.onColorMixPreview(update((it / 100f).percent().coerceIn(-1f, 1f))) },
        onValueChangeFinished = actions.onColorMixChangeFinished,
        valueRange = -100f..100f,
        displayValue = { EditorAdjustmentSliderRules.format(it, 0f, decimals = 0) },
        leading = { Icon(icon, contentDescription = null) },
        testTag = "photo-colormix-$token",
        modifier = Modifier.fillMaxWidth(),
    )
}
