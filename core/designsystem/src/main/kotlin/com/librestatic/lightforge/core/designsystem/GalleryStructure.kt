package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Shared screen structure: section headers, setting rows and the four page states. Screens
// compose these instead of building their own rows and placeholders, so spacing, type and color
// roles stay the same everywhere.

/**
 * A section heading with an optional trailing action such as "See all". The title is a heading
 * for TalkBack; the action is a text button with a 48 dp target.
 */
@Composable
fun GallerySectionHeader(
    title: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = GalleryHeights.TouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.padding(start = GallerySpacing.Sm)) {
                Text(actionLabel, maxLines = 1)
            }
        }
    }
}

/**
 * One settings row: optional leading icon, title, optional supporting line and an optional
 * trailing slot (a value, a chevron). Clickable when [onClick] is set. Content inherits the
 * container's content color; the supporting line uses onSurfaceVariant (metadata role).
 */
@Composable
fun GallerySettingRow(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else Modifier
    SettingRowLayout(
        title = title,
        supportingText = supportingText,
        icon = icon,
        enabled = enabled,
        modifier = modifier.then(clickModifier),
        trailing = trailing,
    )
}

/**
 * A settings row with a switch. The whole row toggles and is announced as one switch; the
 * [Switch] itself is decorative for accessibility so TalkBack does not read it twice.
 */
@Composable
fun GallerySwitchSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    SettingRowLayout(
        title = title,
        supportingText = supportingText,
        icon = icon,
        enabled = enabled,
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
private fun SettingRowLayout(
    title: String,
    supportingText: String?,
    icon: ImageVector?,
    enabled: Boolean,
    modifier: Modifier,
    trailing: (@Composable () -> Unit)?,
) {
    val base = LocalContentColor.current
    val content = if (enabled) base else base.copy(alpha = 0.38f)
    CompositionLocalProvider(LocalContentColor provides content) {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = if (supportingText == null) GalleryHeights.Row else GalleryHeights.TwoLineRow)
                .padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Lg),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (supportingText != null) {
                    val metadata = MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        supportingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) metadata else metadata.copy(alpha = 0.38f),
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/** Nothing to show yet: an icon plate, a title, one line of help and an optional action. */
@Composable
fun GalleryEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = GalleryIcons.PhotoLibrary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** Use for a state that fills a whole screen: draws the decorative shape illustration. */
    hero: Boolean = false,
) = GalleryStateContent(
    title = title,
    body = body,
    illustrationDescription = title,
    modifier = modifier,
    illustration = { Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp)) },
    action = stateAction(actionLabel, onAction),
    heroIcon = if (hero) icon else null,
)

/**
 * Empty message for a small section inside a larger screen or dialog, where a full
 * [GalleryEmptyState] would be oversized: centered `bodyMedium` text in the muted content color.
 */
@Composable
fun GalleryInlineEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = GallerySpacing.Lg, horizontal = GallerySpacing.Md),
    )
}

/** Work in progress with no content yet. Long waits should say what is happening in [body]. */
@Composable
fun GalleryLoadingState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) = GalleryStateContent(
    title = title,
    body = body,
    illustrationDescription = title,
    modifier = modifier,
    // The plate is secondaryContainer, so the indicator inherits onSecondaryContainer.
    illustration = { GalleryLoadingIndicator(Modifier.size(48.dp), color = LocalContentColor.current) },
)

/** Something failed. Say what still works in [body] and offer a retry when one can help. */
@Composable
fun GalleryErrorState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) = GalleryStateContent(
    title = title,
    body = body,
    illustrationDescription = title,
    modifier = modifier,
    illustration = { Icon(GalleryIcons.Warning, contentDescription = null, modifier = Modifier.size(36.dp)) },
    action = stateAction(retryLabel, onRetry),
)

/**
 * Access is missing. The primary action asks again; the optional secondary action opens system
 * settings for users who denied it permanently.
 */
@Composable
fun GalleryPermissionState(
    title: String,
    body: String,
    grantLabel: String,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
    settingsLabel: String? = null,
    onOpenSettings: (() -> Unit)? = null,
) = GalleryStateContent(
    title = title,
    body = body,
    illustrationDescription = title,
    modifier = modifier,
    illustration = { Icon(GalleryIcons.Lock, contentDescription = null, modifier = Modifier.size(36.dp)) },
    action = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GalleryExpressiveButton(onClick = onGrant) { Text(grantLabel) }
            if (settingsLabel != null && onOpenSettings != null) {
                TextButton(onClick = onOpenSettings) { Text(settingsLabel) }
            }
        }
    },
)

private fun stateAction(label: String?, onClick: (() -> Unit)?): (@Composable () -> Unit)? =
    if (label != null && onClick != null) {
        { GalleryExpressiveButton(onClick = onClick) { Text(label) } }
    } else null
