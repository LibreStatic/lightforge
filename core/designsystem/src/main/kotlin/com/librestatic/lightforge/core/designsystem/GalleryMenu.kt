package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

object GalleryMenuDefaults {
    val MinWidth: Dp = 200.dp
    val MaxWidth: Dp = 320.dp
    val ItemMinHeight: Dp = 48.dp
    val ItemHorizontalPadding: Dp = 16.dp
    val IconSize: Dp = 24.dp
}

/**
 * The overflow and context menu of the app: a `surfaceContainer` popup with small corners, wide
 * enough for a typical label on one line. Fill it with [GalleryMenuItem] and [GalleryMenuDivider].
 */
@Composable
fun GalleryDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = GalleryMenuDefaults.MinWidth, max = GalleryMenuDefaults.MaxWidth),
        offset = offset,
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

/**
 * One row of a [GalleryDropdownMenu]: an outlined [icon] (any [GalleryIcons] glyph is swapped for
 * its outlined twin), the [text] on one line, and a trailing check when [checked] is true.
 *
 * [checked] null is a plain action; true or false makes the row a toggle (a [Role.Checkbox], or a
 * [Role.RadioButton] when [radio]) that announces its state. [checkedDescription] labels the check
 * for screen readers. [destructive] paints the row with the error role.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun GalleryMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    checked: Boolean? = null,
    radio: Boolean = false,
    checkedDescription: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    testTag: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val disabledAlpha = 0.38f
    val textColor = when {
        !enabled -> colors.onSurface.copy(alpha = disabledAlpha)
        destructive -> colors.error
        else -> colors.onSurface
    }
    val iconColor = when {
        !enabled -> colors.onSurface.copy(alpha = disabledAlpha)
        destructive -> colors.error
        else -> colors.onSurfaceVariant
    }
    val rowModifier = when (checked) {
        null -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else -> if (radio) {
            Modifier.selectable(selected = checked, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        } else {
            Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.semantics { testTagsAsResourceId = true }.testTag(testTag) else Modifier)
            .then(rowModifier)
            .heightIn(min = GalleryMenuDefaults.ItemMinHeight)
            .padding(horizontal = GalleryMenuDefaults.ItemHorizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Icon(
                GalleryMenuIcons.outlined(icon),
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(GalleryMenuDefaults.IconSize),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (checked == true) {
            Icon(
                GalleryMenuIcons.Check,
                contentDescription = checkedDescription,
                tint = if (enabled) colors.primary else colors.onSurface.copy(alpha = disabledAlpha),
                modifier = Modifier.size(GalleryMenuDefaults.IconSize),
            )
        }
    }
}

/** A full-width rule between two logical groups of a [GalleryDropdownMenu]. */
@Composable
fun GalleryMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
