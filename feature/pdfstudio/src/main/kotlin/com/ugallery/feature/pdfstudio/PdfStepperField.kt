package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ugallery.core.designsystem.GalleryIcons

/**
 * A [−] field [+] numeric input (Phase D item 4): 48dp step buttons, a unit suffix, a configurable
 * [step] and a clamped [min]..[max] range. Built on [PdfNumberFieldDraft], the same debounced
 * commit-on-blur/IME-Done logic as the plain [NumberField], so typed edits behave identically; the
 * step buttons call [onValue] directly since they always produce an in-range value.
 *
 * The text field keeps [label] as its own label/hint (never folded into the unit suffix or a
 * combined string) because [PdfAccessibleAdjustTest] locates the width control by a "Width"
 * text/hint match.
 */
@Composable
internal fun PdfStepperField(
    label: String,
    value: Double,
    unit: String,
    step: Double,
    min: Double,
    max: Double,
    onValue: (Double) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var draft by remember(value) { mutableStateOf(PdfNumberFieldDraft.fromValue(value)) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val committed = draft.commit()
        draft = committed.draft
        committed.value?.let { onValue(it.coerceIn(min, max)) }
    }
    val decreaseLabel = stringResource(R.string.pdf_stepper_decrease, label)
    val increaseLabel = stringResource(R.string.pdf_stepper_increase, label)
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onValue(PdfStepperMath.stepped(value, step, -1, min, max)) },
            enabled = enabled && value > min,
            modifier = Modifier.size(48.dp).semantics { contentDescription = decreaseLabel },
        ) {
            Icon(GalleryIcons.Minus, contentDescription = null)
        }
        OutlinedTextField(
            draft.text,
            { draft = draft.edit(it) },
            // D1 review fix: a plain wrapping Text label mid-word-split ("Widt/h") once the box
            // got narrow; single line + shrink-to-fit keeps it legible instead, and never eats
            // into the value's own space (the label floats above the value, not beside it).
            label = {
                Text(
                    label,
                    maxLines = 1,
                    softWrap = false,
                    autoSize =
                        TextAutoSize.StepBased(
                            minFontSize = 9.sp,
                            maxFontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        ),
                )
            },
            suffix = { Text(unit, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false) },
            singleLine = true,
            enabled = enabled,
            modifier =
                Modifier.weight(1f).padding(horizontal = 4.dp).onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
        )
        IconButton(
            onClick = { onValue(PdfStepperMath.stepped(value, step, 1, min, max)) },
            enabled = enabled && value < max,
            modifier = Modifier.size(48.dp).semantics { contentDescription = increaseLabel },
        ) {
            Icon(GalleryIcons.Plus, contentDescription = null)
        }
    }
}
