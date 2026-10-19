package com.librestatic.lightforge.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GallerySpacing

/**
 * Building blocks of the "Storage access" page: a section label, one rounded container holding list rows separated by
 * hairline dividers, and an optional one-line footer, the way Android Settings groups its rows.
 */
internal class StorageAccessGroupScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

@Composable
internal fun StorageAccessSection(
    title: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: StorageAccessGroupScope.() -> Unit,
) {
    val scope = StorageAccessGroupScope().apply(content)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = GallerySpacing.Lg).semantics { heading() },
        )
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                scope.rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = GallerySpacing.Lg),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    row()
                }
            }
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = GallerySpacing.Lg),
            )
        }
    }
}

/** Rows sit on the group's container, so they paint nothing of their own. */
@Composable
private fun rowColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    selectedContainerColor = Color.Transparent,
    selectedContentColor = MaterialTheme.colorScheme.onSurface,
    supportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun RowIcon(icon: ImageVector, enabled: Boolean = true) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
    )
}

private val RowMinHeight = 64.dp

/** A row with a leading icon, a title, at most one supporting line and an optional trailing control. */
@Composable
internal fun StorageAccessRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    supportingMaxLines: Int = 2,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val rowModifier = modifier.fillMaxWidth().heightIn(min = RowMinHeight)
    val supportingContent: (@Composable () -> Unit)? = supporting?.let {
        { Text(it, maxLines = supportingMaxLines, overflow = TextOverflow.Ellipsis) }
    }
    val titleContent: @Composable () -> Unit = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    if (onClick != null) {
        ListItem(
            onClick = onClick,
            enabled = enabled,
            modifier = rowModifier,
            colors = rowColors(),
            leadingContent = { RowIcon(icon, enabled) },
            supportingContent = supportingContent,
            trailingContent = trailing,
        ) { titleContent() }
    } else {
        ListItem(
            modifier = rowModifier,
            colors = rowColors(),
            leadingContent = { RowIcon(icon, enabled) },
            supportingContent = supportingContent,
            trailingContent = trailing,
        ) { titleContent() }
    }
}

@Composable
internal fun StorageAccessSwitchRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = RowMinHeight),
        colors = rowColors(),
        leadingContent = { RowIcon(icon, enabled) },
        supportingContent = supporting?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = title },
            )
        },
    ) { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}
