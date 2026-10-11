package com.librestatic.lightforge.feature.photoeditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.MediaEditorToolChip
import com.librestatic.lightforge.core.designsystem.MediaEditorToolChips
import com.librestatic.lightforge.core.model.EditOperation

/** Sub-selections inside the Adjust, Crop and Filters tabs, owned by the editor so search can move them. */
internal class PhotoToolNav(
    val adjustTile: PhotoAdjustTile,
    val onAdjustTile: (PhotoAdjustTile) -> Unit,
    val cropTool: PhotoCropTool,
    val onCropTool: (PhotoCropTool) -> Unit,
    val looksTab: PhotoLooksTab,
    val onLooksTab: (PhotoLooksTab) -> Unit,
)

/** Everything the Adjust, Filters, Crop and More panels report back to the host. */
internal class PhotoPanelActions(
    val onAdjustPreview: (EditOperation.Adjust) -> Unit,
    val onAdjustChangeFinished: () -> Unit,
    val onColorMixPreview: (EditOperation.ColorMix) -> Unit,
    val onColorMixChangeFinished: () -> Unit,
    val onLookPreview: (EditOperation.Look?) -> Unit,
    val onLookChangeFinished: () -> Unit,
    val onCreateCustomLook: () -> Unit,
    val onRenameCustomLook: (String, String) -> Unit,
    val onDeleteCustomLook: (String) -> Unit,
    val onAutoStraighten: () -> Unit,
    val onOpenWith: (() -> Unit)?,
)

/** Where the editor starts; Compose Driver previews override it to render a given tab or the search sheet. */
internal data class PhotoEditorInitialUi(
    val tool: PhotoEditorTool? = null,
    val adjustTile: PhotoAdjustTile = PhotoAdjustTile.Brightness,
    val cropTool: PhotoCropTool = PhotoCropTool.Crop,
    val looksTab: PhotoLooksTab = PhotoLooksTab.Filters,
    val searchOpen: Boolean = false,
)

internal val LocalPhotoEditorInitialUi = compositionLocalOf { PhotoEditorInitialUi() }

@Composable
internal fun tabLabel(tab: PhotoEditorTab): String = when (tab) {
    PhotoEditorTab.Automatic -> stringResource(R.string.photo_editor_automatic)
    PhotoEditorTab.Crop -> stringResource(R.string.photo_editor_crop)
    PhotoEditorTab.Adjust -> stringResource(R.string.photo_editor_adjust)
    PhotoEditorTab.Actions -> stringResource(R.string.photo_editor_tab_actions)
    PhotoEditorTab.Filters -> stringResource(R.string.photo_editor_filters)
    PhotoEditorTab.More -> stringResource(R.string.photo_editor_tab_more)
    PhotoEditorTab.Raw -> stringResource(R.string.photo_editor_raw_title)
    PhotoEditorTab.Export -> stringResource(R.string.photo_editor_export_tab)
}

@Composable
internal fun toolLabel(tool: PhotoEditorTool): String = when (tool) {
    PhotoEditorTool.ObjectEraser -> stringResource(
        R.string.photo_editor_experimental_tool,
        stringResource(R.string.photo_editor_object_eraser),
    )
    PhotoEditorTool.SubjectClip -> stringResource(
        R.string.photo_editor_experimental_tool,
        stringResource(R.string.photo_editor_subject_clip),
    )
    else -> tabLabel(tool.tab())
}

/** Search button followed by the pill tabs. */
@Composable
internal fun PhotoToolNavigation(
    tabs: List<PhotoEditorTab>,
    selectedTab: PhotoEditorTab,
    onSelectTab: (PhotoEditorTab) -> Unit,
    onSearch: () -> Unit,
    wrap: Boolean,
) {
    Row(
        Modifier.padding(start = GallerySpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSearch, modifier = Modifier.testTag("photo-tool-search")) {
            Icon(GalleryIcons.Search, contentDescription = stringResource(R.string.photo_editor_search_tools))
        }
        MediaEditorToolChips(
            tools = tabs.map { tab ->
                MediaEditorToolChip(
                    key = tab.name,
                    label = tabLabel(tab),
                    icon = null,
                    testTag = "photo-editor-tab-${tab.name.lowercase()}",
                )
            },
            selectedKey = selectedTab.name,
            onSelect = { key -> onSelectTab(PhotoEditorTab.valueOf(key)) },
            wrap = wrap,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = GallerySpacing.Sm),
        )
    }
}

/**
 * Icon-over-label tile for the carousels. Selected tiles use the secondary container pair; a
 * [modified] tile carries a small dot and announces itself as adjusted.
 */
@Composable
internal fun PhotoToolTile(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    modified: Boolean = false,
) {
    val modifiedText = stringResource(R.string.photo_editor_adjust_modified)
    Surface(
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .testTag(testTag)
            .widthIn(min = 84.dp, max = 120.dp)
            .heightIn(min = 76.dp)
            .semantics { if (modified) stateDescription = modifiedText },
    ) {
        Column(
            Modifier.padding(horizontal = GallerySpacing.Sm, vertical = GallerySpacing.Sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs, Alignment.CenterVertically),
        ) {
            Box {
                Icon(icon, contentDescription = null)
                if (modified) {
                    // A dot of the primary colour on the container: a graphic, not text, so no pair needed.
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
