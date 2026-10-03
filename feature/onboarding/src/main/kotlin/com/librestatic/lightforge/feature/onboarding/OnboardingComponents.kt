package com.librestatic.lightforge.feature.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import kotlinx.coroutines.delay

/** Non-interactive label chip; a clickable chip that does nothing would mislead TalkBack. */
@Composable
internal fun StaticChip(icon: ImageVector?, label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(horizontal = GallerySpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(GallerySpacing.Sm))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, container: Color, content: Color) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(container),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = content) }
}

/** Grouped list row container; the first and last row of a group get the larger outer corners. */
@Composable
private fun RowSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

@Composable
internal fun PermissionRow(
    icon: ImageVector,
    title: String,
    body: String,
    status: OnboardingPermissionStatus,
    container: Color,
    content: Color,
    tag: String,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    enabled: Boolean = true,
    disabledHint: String? = null,
    grantedNote: String? = null,
) {
    val statusText = when (status) {
        OnboardingPermissionStatus.Granted -> stringResource(R.string.onboarding_status_granted)
        OnboardingPermissionStatus.Partial -> stringResource(R.string.onboarding_status_partial)
        OnboardingPermissionStatus.Denied -> stringResource(R.string.onboarding_status_denied)
        OnboardingPermissionStatus.Blocked -> stringResource(R.string.onboarding_status_blocked)
        OnboardingPermissionStatus.NotRequested, OnboardingPermissionStatus.Unavailable -> null
    }
    RowSurface(Modifier.testTag("onboarding-permission-$tag")) {
        Column(Modifier.padding(GallerySpacing.Lg)) {
            Row(verticalAlignment = Alignment.Top) {
                IconBadge(icon, container, content)
                Spacer(Modifier.width(GallerySpacing.Lg))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {
                    if (statusText != null) stateDescription = statusText
                }) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (statusText != null) {
                        Row(Modifier.padding(top = GallerySpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
                            if (status == OnboardingPermissionStatus.Granted) {
                                Icon(
                                    GalleryIcons.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(GallerySpacing.Xs))
                            }
                            Text(statusText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (status == OnboardingPermissionStatus.Granted && grantedNote != null) {
                        Text(
                            grantedNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = GallerySpacing.Xs),
                        )
                    }
                    if (!enabled && disabledHint != null) {
                        Text(
                            disabledHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = GallerySpacing.Xs),
                        )
                    }
                }
            }
            val action: Pair<Int, () -> Unit>? = when (status) {
                OnboardingPermissionStatus.NotRequested, OnboardingPermissionStatus.Denied -> R.string.onboarding_allow to onRequest
                OnboardingPermissionStatus.Partial -> R.string.onboarding_add_more to onRequest
                OnboardingPermissionStatus.Blocked -> R.string.onboarding_open_settings to onOpenSettings
                OnboardingPermissionStatus.Granted, OnboardingPermissionStatus.Unavailable -> null
            }
            if (action != null) {
                Row(Modifier.fillMaxWidth().padding(top = GallerySpacing.Sm), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(
                        onClick = action.second,
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding-permission-$tag-action"),
                    ) { Text(stringResource(action.first)) }
                }
            }
        }
    }
}

@Composable
internal fun LinkRow(icon: ImageVector, title: String, body: String, tag: String, onClick: (() -> Unit)?) {
    RowSurface(
        Modifier
            .testTag("onboarding-link-$tag")
            .clip(MaterialTheme.shapes.large)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(GallerySpacing.Lg))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) Icon(GalleryIcons.ChevronForward, contentDescription = null)
        }
    }
}

@Composable
internal fun AnalysisStep(
    selected: Set<OnboardingAnalysisOption>,
    onChange: (Set<OnboardingAnalysisOption>) -> Unit,
) {
    OnboardingHero(
        icon = GalleryIcons.AutoAwesome,
        shapeIndex = 3,
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        height = 160,
    )
    OnboardingHeadline(
        stringResource(R.string.onboarding_analysis_title),
        stringResource(R.string.onboarding_analysis_body),
    )
    Spacer(Modifier.height(GallerySpacing.Lg))
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        listOf(
            Triple(OnboardingAnalysisOption.People, GalleryIcons.User, R.string.onboarding_analysis_people to R.string.onboarding_analysis_people_body),
            Triple(OnboardingAnalysisOption.Content, GalleryIcons.TextFields, R.string.onboarding_analysis_content to R.string.onboarding_analysis_content_body),
            Triple(OnboardingAnalysisOption.Pets, GalleryIcons.Pet, R.string.onboarding_analysis_pets to R.string.onboarding_analysis_pets_body),
            Triple(OnboardingAnalysisOption.Semantic, GalleryIcons.Search, R.string.onboarding_analysis_semantic to R.string.onboarding_analysis_semantic_body),
        ).forEach { (option, icon, text) ->
            val checked = option in selected
            fun toggle(value: Boolean) = onChange(if (value) selected + option else selected - option)
            RowSurface(
                Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable(role = Role.Switch) { toggle(!checked) }
                    .testTag("onboarding-analysis-${option.name}"),
            ) {
                Row(
                    Modifier.heightIn(min = 64.dp).padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(icon, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(GallerySpacing.Lg))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(text.first), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(text.second),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(GallerySpacing.Md))
                    // The whole row toggles; the switch mirrors state without a second focus stop.
                    Switch(checked = checked, onCheckedChange = null)
                }
            }
        }
    }
    Spacer(Modifier.height(GallerySpacing.Md))
    Text(
        stringResource(R.string.onboarding_analysis_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One feature explained under a walkthrough page's headline; [specs] are optional highlights shown as chips. */
private class FeatureItem(
    val icon: ImageVector,
    val title: Int,
    val body: Int,
    val container: Color,
    val content: Color,
    val specs: List<String> = emptyList(),
)

// Camera and format names are trademarks and read the same in every language.
private val LogProfileSpecs = listOf("Apple Log", "S-Log3", "Canon Log", "V-Log", "D-Log", "HDR10 · HLG")
private val RawSpecs = listOf("Camera RAW", "16-bit TIFF")

/** Creative tools listed under the Studio page's headline. */
@Composable
internal fun StudioFeatures() {
    val scheme = MaterialTheme.colorScheme
    FeatureItemList(
        listOf(
            FeatureItem(
                GalleryIcons.Tune, R.string.onboarding_studio_photo_title, R.string.onboarding_studio_photo_body,
                scheme.primaryContainer, scheme.onPrimaryContainer,
                listOf(stringResource(R.string.onboarding_studio_chip_filters)) + RawSpecs,
            ),
            FeatureItem(
                GalleryIcons.Video, R.string.onboarding_studio_video_title, R.string.onboarding_studio_video_body,
                scheme.tertiaryContainer, scheme.onTertiaryContainer, LogProfileSpecs,
            ),
            FeatureItem(
                GalleryIcons.PictureAsPdf, R.string.onboarding_studio_pdf_title, R.string.onboarding_studio_pdf_body,
                scheme.secondaryContainer, scheme.onSecondaryContainer,
            ),
            FeatureItem(
                GalleryIcons.GridView, R.string.onboarding_studio_collage_title, R.string.onboarding_studio_collage_body,
                scheme.primaryContainer, scheme.onPrimaryContainer,
            ),
            FeatureItem(
                GalleryIcons.Repeat, R.string.onboarding_studio_gif_title, R.string.onboarding_studio_gif_body,
                scheme.tertiaryContainer, scheme.onTertiaryContainer,
            ),
        ),
    )
}

/** Everything else, listed under the closing "And much more" page's headline. */
@Composable
internal fun MoreFeatures() {
    val scheme = MaterialTheme.colorScheme
    FeatureItemList(
        listOf(
            FeatureItem(
                GalleryIcons.Lock, R.string.onboarding_more_private_title, R.string.onboarding_more_private_body,
                scheme.primaryContainer, scheme.onPrimaryContainer,
            ),
            FeatureItem(
                GalleryIcons.Place, R.string.onboarding_more_people_title, R.string.onboarding_more_people_body,
                scheme.tertiaryContainer, scheme.onTertiaryContainer,
            ),
            FeatureItem(
                GalleryIcons.PhotoLibrary, R.string.onboarding_more_memories_title, R.string.onboarding_more_memories_body,
                scheme.secondaryContainer, scheme.onSecondaryContainer,
            ),
            FeatureItem(
                GalleryIcons.SwapHoriz, R.string.onboarding_more_transfer_title, R.string.onboarding_more_transfer_body,
                scheme.primaryContainer, scheme.onPrimaryContainer,
            ),
            FeatureItem(
                GalleryIcons.Folder, R.string.onboarding_more_sync_title, R.string.onboarding_more_sync_body,
                scheme.tertiaryContainer, scheme.onTertiaryContainer,
            ),
        ),
    )
}

@Composable
private fun FeatureItemList(items: List<FeatureItem>) {
    Column(Modifier.padding(top = GallerySpacing.Lg), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        items.forEach { item ->
            RowSurface(Modifier.semantics(mergeDescendants = true) {}) {
                Row(
                    Modifier.heightIn(min = 64.dp).padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Md),
                    verticalAlignment = Alignment.Top,
                ) {
                    IconBadge(item.icon, item.container, item.content)
                    Spacer(Modifier.width(GallerySpacing.Lg))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(item.title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(item.body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.specs.isNotEmpty()) {
                            FlowRow(
                                Modifier.padding(top = GallerySpacing.Sm),
                                horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
                                verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
                            ) { item.specs.forEach { StaticChip(icon = null, label = it) } }
                        }
                    }
                }
            }
        }
    }
}

/** Small live UI samples for the walkthrough pages; built from real components, not screenshots. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FeatureSample(page: Int, reducedMotion: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(if (LocalOnboardingShortWindow.current) 140.dp else 220.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.padding(GallerySpacing.Xl), contentAlignment = Alignment.Center) {
            when (page) {
                0 -> SearchSample()
                1 -> TimelineSample(reducedMotion)
                2 -> EditSample()
                StudioFeaturePage -> ShapeSample(MaterialShapes.Clover4Leaf, GalleryIcons.Palette)
                else -> ShapeSample(MaterialShapes.SoftBurst, GalleryIcons.Plus)
            }
        }
    }
}

@Composable
private fun SampleTile(container: Color, content: Color, size: androidx.compose.ui.unit.Dp, icon: ImageVector = GalleryIcons.Image) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 5)).background(container),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size / 2.6f)) }
}

@Composable
private fun SearchSample() {
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHighest, contentColor = scheme.onSurface) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = GallerySpacing.Lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(GalleryIcons.Search, contentDescription = null)
                Spacer(Modifier.width(GallerySpacing.Md))
                Text(stringResource(R.string.onboarding_sample_query), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(GallerySpacing.Lg))
        Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            SampleTile(scheme.primaryContainer, scheme.onPrimaryContainer, 72.dp)
            SampleTile(scheme.tertiaryContainer, scheme.onTertiaryContainer, 72.dp)
            SampleTile(scheme.secondaryContainer, scheme.onSecondaryContainer, 72.dp)
        }
    }
}

@Composable
private fun TimelineSample(reducedMotion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    var dense by remember { mutableStateOf(true) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        while (true) {
            delay(1_800)
            dense = !dense
        }
    }
    val tile by animateDpAsState(
        if (dense) 30.dp else 52.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "timeline-tile",
    )
    val palette = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlowRow(
            Modifier.weight(1f).clip(RoundedCornerShape(16.dp)),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxLines = if (dense) 5 else 3,
        ) {
            repeat(30) { index ->
                val (container, content) = palette[index % palette.size]
                SampleTile(container, content, tile)
            }
        }
        Spacer(Modifier.width(GallerySpacing.Md))
        Icon(GalleryIcons.ZoomIn, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun EditSample() {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Md)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SampleTile(scheme.surfaceContainerHighest, scheme.onSurface, 104.dp)
            Spacer(Modifier.height(GallerySpacing.Sm))
            StaticChip(GalleryIcons.Lock, stringResource(R.string.onboarding_sample_original))
        }
        Icon(GalleryIcons.ChevronForward, contentDescription = null, tint = scheme.primary)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SampleTile(scheme.tertiaryContainer, scheme.onTertiaryContainer, 104.dp, GalleryIcons.Tune)
            Spacer(Modifier.height(GallerySpacing.Sm))
            StaticChip(GalleryIcons.ContentCopy, stringResource(R.string.onboarding_sample_copy))
        }
    }
}

/** Hero-style sample for the list pages, whose rows below already show the features themselves. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShapeSample(shape: RoundedPolygon, icon: ImageVector) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(158.dp).rotate(24f).clip(shape.toShape()).background(scheme.secondaryContainer))
    Icon(icon, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(62.dp))
}
