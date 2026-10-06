package com.librestatic.lightforge.feature.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryOverlayTokens

/** Keyboard hints shown in tooltips; key caps are not translated. */
internal object ViewerShortcutKeys {
    const val Back = "Esc"
    const val Details = "I"
    const val Favorite = "F"
    const val PlayPause = "Space"
    const val Delete = "Del"
}

/**
 * A plain tooltip over a chrome control, naming its keyboard shortcut ("Details (I)"). Tooltips
 * appear on long press and on mouse hover, so pointer and keyboard users can discover shortcuts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerTooltip(
    label: String,
    shortcut: String?,
    modifier: Modifier = Modifier,
    above: Boolean = false,
    content: @Composable () -> Unit,
) {
    val text = if (shortcut == null) label else stringResource(R.string.viewer_shortcut_hint, label, shortcut)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (above) TooltipAnchorPosition.Above else TooltipAnchorPosition.Below,
        ),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}

/**
 * Dark gradients behind the top and bottom chrome so the date, the buttons and the scrubber stay
 * readable over bright photos (bug 21), without the flat bar that covered the media before.
 */
internal fun Modifier.viewerTopScrim(): Modifier = background(
    Brush.verticalGradient(0f to GalleryOverlayTokens.ScrimTop, 1f to Color.Transparent),
)

internal fun Modifier.viewerBottomScrim(): Modifier = background(
    Brush.verticalGradient(0f to Color.Transparent, 0.35f to GalleryOverlayTokens.ScrimMiddle, 1f to GalleryOverlayTokens.ScrimBottom),
)

/**
 * The primary actions as a compact pill under the media, mirroring Google Photos: Share, Edit,
 * Favorite and Delete (move to trash). A trashed item offers Restore and Delete permanently
 * instead. Everything else lives in the top bar's Details button and ⋮ menu.
 */
@Composable
internal fun ViewerActionPill(
    onShare: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onToggleFavorite: (() -> Unit)?,
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    onRestore: (() -> Unit)? = null,
    restoreLabel: String? = null,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String? = null,
) {
    if (onShare == null && onEdit == null && onToggleFavorite == null && onRestore == null && onDelete == null) return
    Surface(
        color = GalleryOverlayTokens.ControlSurface,
        contentColor = GalleryOverlayTokens.Content,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier,
    ) {
        // Equal-width buttons, as wide as the widest label allows within the screen.
        Row(
            Modifier.width(IntrinsicSize.Max).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onShare?.let { ViewerPillAction(it, GalleryIcons.Share, stringResource(R.string.viewer_share)) }
            onEdit?.let { ViewerPillAction(it, GalleryIcons.Edit, stringResource(R.string.viewer_edit)) }
            onToggleFavorite?.let {
                ViewerPillAction(
                    onClick = it,
                    icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    // The filled heart shows the state; the caption stays short so it never gets cut.
                    label = stringResource(R.string.viewer_favorite),
                    description = if (isFavorite) stringResource(R.string.viewer_unfavorite) else null,
                    shortcut = ViewerShortcutKeys.Favorite,
                )
            }
            onRestore?.let {
                ViewerPillAction(
                    onClick = it,
                    icon = GalleryIcons.RestoreFromTrash,
                    label = stringResource(R.string.viewer_pill_restore),
                    description = restoreLabel,
                )
            }
            onDelete?.let {
                ViewerPillAction(
                    onClick = it,
                    icon = GalleryIcons.Trash,
                    label = stringResource(R.string.viewer_pill_delete),
                    description = deleteLabel,
                    shortcut = ViewerShortcutKeys.Delete,
                )
            }
        }
    }
}

@Composable
private fun RowScope.ViewerPillAction(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    shortcut: String? = null,
    /** The full action name for tooltips and accessibility when [label] is a short caption. */
    description: String? = null,
) {
    val fullLabel = description ?: label
    // An equal share of the pill, so a long localized label ellipsizes instead of pushing Delete
    // off a narrow phone.
    Box(Modifier.weight(1f)) {
        ViewerTooltip(label = fullLabel, shortcut = shortcut, above = true) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(min = 72.dp, max = 112.dp)
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics { contentDescription = fullLabel }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    // Shrink a long localized label ("Bearbeiten") before cutting it.
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 9.sp,
                        maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
                    ),
                    modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {},
                )
            }
        }
    }
}

