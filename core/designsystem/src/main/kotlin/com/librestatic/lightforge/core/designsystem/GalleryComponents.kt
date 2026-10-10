package com.librestatic.lightforge.core.designsystem

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

enum class GalleryMotionEdge { Top, Bottom, Start, End }

/**
 * Returns whether Android has disabled system animations. This observes the
 * platform animation-scale settings so an accessibility change takes effect
 * without restarting the activity.
 */
@Composable
fun rememberGalleryReducedMotion(): Boolean {
    val context = LocalContext.current
    var motionScale by remember(context) { mutableFloatStateOf(readGalleryMotionScale(context)) }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                motionScale = readGalleryMotionScale(context)
            }
        }
        GalleryMotionSettingNames.forEach { name ->
            resolver.registerContentObserver(Settings.Global.getUriFor(name), false, observer)
        }
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return motionScale <= 0f
}

private val GalleryMotionSettingNames = listOf(
    Settings.Global.ANIMATOR_DURATION_SCALE,
    Settings.Global.TRANSITION_ANIMATION_SCALE,
    Settings.Global.WINDOW_ANIMATION_SCALE,
)

private fun readGalleryMotionScale(context: android.content.Context): Float =
    GalleryMotionSettingNames.minOf { name ->
        runCatching {
            Settings.Global.getFloat(context.contentResolver, name, 1f)
        }.getOrDefault(1f)
    }.coerceAtLeast(0f)

@Composable
fun <S> GalleryAnimatedContent(
    targetState: S,
    modifier: Modifier = Modifier,
    contentKey: (S) -> Any? = { it },
    reducedMotion: Boolean? = null,
    content: @Composable androidx.compose.animation.AnimatedContentScope.(S) -> Unit,
) {
    val shouldReduceMotion = reducedMotion ?: rememberGalleryReducedMotion()
    val motionScheme = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        contentKey = contentKey,
        transitionSpec = {
            if (shouldReduceMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (
                    // Fade-through: the outgoing surface is nearly gone before the incoming one
                    // appears, so titles of two routes never overlap.
                    fadeIn(
                        animationSpec = tween(durationMillis = 210, delayMillis = 90),
                    ) + scaleIn(
                        initialScale = 0.985f,
                        animationSpec = motionScheme.defaultSpatialSpec(),
                    )
                ) togetherWith fadeOut(
                    animationSpec = motionScheme.fastEffectsSpec(),
                )
            }.using(SizeTransform(clip = false))
        },
        content = content,
    )
}

@Composable
fun GalleryAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    edge: GalleryMotionEdge = GalleryMotionEdge.Bottom,
    reducedMotion: Boolean? = null,
    content: @Composable androidx.compose.animation.AnimatedVisibilityScope.() -> Unit,
) {
    val shouldReduceMotion = reducedMotion ?: rememberGalleryReducedMotion()
    val motionScheme = MaterialTheme.motionScheme
    val enter: EnterTransition
    val exit: ExitTransition
    if (shouldReduceMotion) {
        enter = EnterTransition.None
        exit = ExitTransition.None
    } else {
        val fadeEnter = fadeIn(
            animationSpec = motionScheme.defaultEffectsSpec(),
        )
        val fadeExit = fadeOut(
            animationSpec = motionScheme.fastEffectsSpec(),
        )
        enter = when (edge) {
            GalleryMotionEdge.Top -> fadeEnter + slideInVertically(
                animationSpec = motionScheme.defaultSpatialSpec(),
                initialOffsetY = { -it },
            )
            GalleryMotionEdge.Bottom -> fadeEnter + slideInVertically(
                animationSpec = motionScheme.defaultSpatialSpec(),
                initialOffsetY = { it },
            )
            GalleryMotionEdge.Start -> fadeEnter + slideInHorizontally(
                animationSpec = motionScheme.defaultSpatialSpec(),
                initialOffsetX = { -it },
            )
            GalleryMotionEdge.End -> fadeEnter + slideInHorizontally(
                animationSpec = motionScheme.defaultSpatialSpec(),
                initialOffsetX = { it },
            )
        }
        exit = when (edge) {
            GalleryMotionEdge.Top -> fadeExit + slideOutVertically(
                animationSpec = motionScheme.fastSpatialSpec(),
                targetOffsetY = { -it },
            )
            GalleryMotionEdge.Bottom -> fadeExit + slideOutVertically(
                animationSpec = motionScheme.fastSpatialSpec(),
                targetOffsetY = { it },
            )
            GalleryMotionEdge.Start -> fadeExit + slideOutHorizontally(
                animationSpec = motionScheme.fastSpatialSpec(),
                targetOffsetX = { -it },
            )
            GalleryMotionEdge.End -> fadeExit + slideOutHorizontally(
                animationSpec = motionScheme.fastSpatialSpec(),
                targetOffsetX = { it },
            )
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * Window insets [GalleryTopAppBar] applies by default. A host that already applied the status bar
 * inset (e.g. a bar drawn inside a content pane beside a navigation rail) provides zero insets.
 */
val LocalGalleryTopBarWindowInsets =
    androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.foundation.layout.WindowInsets?> { null }

@Composable
fun GalleryTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    navigationContentDescription: String? = null,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    windowInsets: androidx.compose.foundation.layout.WindowInsets =
        LocalGalleryTopBarWindowInsets.current ?: TopAppBarDefaults.windowInsets,
) {
    TopAppBar(
        windowInsets = windowInsets,
        title = {
            val titleContent: @Composable () -> Unit = {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (onTitleClick != null) {
                androidx.compose.material3.TextButton(onClick = onTitleClick) { titleContent() }
            } else titleContent()
        },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                GalleryExpressiveIconButton(onClick = onBack) {
                    Icon(
                        GalleryIcons.Back,
                        contentDescription = navigationContentDescription ?: title,
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

@Composable
fun GalleryResponsiveContainer(
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = GalleryContentWidths.Browsing,
    content: @Composable ColumnScope.(GalleryAdaptiveLayoutInfo) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val adaptiveInfo = galleryAdaptiveLayoutInfo(this.maxWidth)
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxSize()
                .padding(horizontal = adaptiveInfo.gutter),
        ) {
            content(adaptiveInfo)
        }
    }
}

/**
 * Lays out a list item's label and its current value on one line when both fit, and drops the
 * value onto its own line below the label otherwise, following Material's list anatomy where
 * content that doesn't fit the headline moves to a supporting line instead of squeezing it.
 *
 * Use it as the headline of a `ListItem` rather than putting the value in the trailing slot:
 * Material measures the trailing slot first, so a long value there starves the label.
 */
@Composable
fun GalleryLabelValueLayout(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf(label, value),
        modifier = modifier,
    ) { (labelMeasurables, valueMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = GallerySpacing.Lg.roundToPx()
        val lineGap = GallerySpacing.Xs.roundToPx()
        val labelPlaceable = labelMeasurables.first().measure(loose)
        val valuePlaceable = valueMeasurables.first().measure(loose)
        val inline = labelPlaceable.width + gap + valuePlaceable.width <= constraints.maxWidth
        if (inline) {
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else labelPlaceable.width + gap + valuePlaceable.width
            val height = maxOf(labelPlaceable.height, valuePlaceable.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
                valuePlaceable.placeRelative(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
            }
        } else {
            val width = maxOf(labelPlaceable.width, valuePlaceable.width).coerceAtLeast(constraints.minWidth)
            val height = (labelPlaceable.height + lineGap + valuePlaceable.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                labelPlaceable.placeRelative(0, 0)
                valuePlaceable.placeRelative(0, labelPlaceable.height + lineGap)
            }
        }
    }
}

/**
 * Pill that shows the current value of a setting next to its label. The text wraps to a second
 * line instead of growing past its parent; see [GalleryLabelValueLayout] for placing it in list rows.
 */
@Composable
fun GalleryValueChip(
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        // A full-radius shape stays a pill on one line and a rounded card on two.
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = GallerySpacing.Md, vertical = GallerySpacing.Xs),
        )
    }
}

@Composable
fun GallerySectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
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
}

@Composable
fun GalleryActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        enabled = enabled,
        modifier = modifier.height(72.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** High-emphasis Material 3 Expressive action with a pressed shape morph. */
@Composable
fun GalleryExpressiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        content = content,
    )
}

/** Material 3 Expressive icon action whose container morphs while pressed. */
@Composable
fun GalleryExpressiveIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        content = content,
    )
}

/**
 * Responsive single-choice group with Expressive press growth and automatic overflow.
 *
 * @param enabled per-item enabled state, matched to [labels] by index; a missing or short list
 *   defaults every item to enabled. A disabled item keeps its label (callers should pair it with
 *   a nearby helper text explaining why) but does not respond to taps and is announced as
 *   unavailable to assistive tech.
 */
@Composable
fun GalleryExpressiveChoiceGroup(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?> = emptyList(),
    minimumItemWidth: Dp? = null,
    enabled: List<Boolean> = emptyList(),
    wrap: Boolean = false,
) {
    fun isEnabled(index: Int) = enabled.getOrNull(index) ?: true
    if (minimumItemWidth != null) {
        val chips: @Composable () -> Unit = {
            labels.forEachIndexed { index, label ->
                val icon = icons.getOrNull(index)
                // M3 chips lay their content out from the start edge, so a short label sat left in
                // a chip stretched to minimumItemWidth. Icon + label live together in the label
                // slot instead, in a Row with the chip's own horizontal padding (16dp a side with
                // no leadingIcon) subtracted from the minimum width, centered when there is slack.
                FilterChip(
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    label = {
                        Row(
                            modifier = Modifier.widthIn(min = (minimumItemWidth - 32.dp).coerceAtLeast(0.dp)),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (icon != null) {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(label, maxLines = 1)
                        }
                    },
                    enabled = isEnabled(index),
                    modifier = Modifier.widthIn(min = minimumItemWidth).heightIn(min = 48.dp),
                )
            }
        }
        // In narrow panes a scrolling row hides options with no affordance; wrap keeps every
        // choice visible (and reachable by accessibility services) on as many lines as needed.
        if (wrap)
            FlowRow(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { chips() }
        else
            Row(
                modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { chips() }
        return
    }
    ButtonGroup(
        overflowIndicator = { state -> ButtonGroupDefaults.OverflowIndicator(state) },
        modifier = modifier.fillMaxWidth(),
    ) {
        labels.forEachIndexed { index, label ->
            val icon = icons.getOrNull(index)
            toggleableItem(
                checked = selectedIndex == index,
                label = label,
                onCheckedChange = { onSelect(index) },
                enabled = isEnabled(index),
                icon = icon?.let { imageVector ->
                    { Icon(imageVector, contentDescription = null) }
                },
                weight = 1f,
            )
        }
    }
}

/** Official Material You shape-morphing indicator for indeterminate work. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GalleryLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LoadingIndicator(modifier = modifier, color = color)
}

/** Expressive wavy progress for long-running determinate work. */
@Composable
fun GalleryProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    LinearWavyProgressIndicator(
        progress = progress,
        modifier = modifier.fillMaxWidth(),
        color = color,
        trackColor = trackColor,
    )
}

/** Expressive wavy progress for long-running work without a known completion fraction. */
@Composable
fun GalleryIndeterminateProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    LinearWavyProgressIndicator(
        modifier = modifier.fillMaxWidth(),
        color = color,
        trackColor = trackColor,
    )
}

/**
 * Expressive wavy ring for progress drawn around an icon (navigation badges, small status marks).
 * Indeterminate when [progress] is null.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GalleryCircularProgressIndicator(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = Color.Transparent,
    strokeWidth: Dp = 3.dp,
) {
    val stroke = with(LocalDensity.current) { Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round) }
    if (progress == null) {
        CircularWavyProgressIndicator(
            modifier = modifier,
            color = color,
            trackColor = trackColor,
            stroke = stroke,
            trackStroke = stroke,
        )
    } else {
        CircularWavyProgressIndicator(
            progress = progress,
            modifier = modifier,
            color = color,
            trackColor = trackColor,
            stroke = stroke,
            trackStroke = stroke,
        )
    }
}

/**
 * Reserves the height of a wavy progress bar whether or not it is shown. Toggling a busy state
 * inside a scrolling or stacked layout therefore never shifts the content around it.
 */
@Composable
fun GalleryProgressSlot(
    visible: Boolean,
    modifier: Modifier = Modifier,
    indicator: @Composable () -> Unit = { GalleryIndeterminateProgressIndicator() },
) {
    Box(modifier.fillMaxWidth().heightIn(min = GalleryProgressSlotHeight)) {
        if (visible) indicator()
    }
}

private val GalleryProgressSlotHeight = 16.dp

@Composable
fun GalleryStateContent(
    title: String,
    body: String,
    illustrationDescription: String,
    modifier: Modifier = Modifier,
    illustration: @Composable () -> Unit = {
        Icon(
            imageVector = GalleryIcons.Image,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
        )
    },
    action: (@Composable () -> Unit)? = null,
    /** When set, replaces the icon plate with the decorative shape illustration (full-screen states). */
    heroIcon: ImageVector? = null,
) {
    BoxWithConstraints(modifier = modifier.padding(GallerySpacing.Xxl), contentAlignment = Alignment.Center) {
        val heroSize = if (maxHeight < 480.dp) 112.dp else 168.dp
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (heroIcon != null) {
                GalleryShapeIllustration(
                    heroIcon,
                    size = heroSize,
                    animateEntrance = true,
                    modifier = Modifier.semantics { contentDescription = illustrationDescription },
                )
            } else Surface(
                modifier = Modifier
                    .size(72.dp)
                    .galleryFadeRise()
                    .semantics { contentDescription = illustrationDescription },
                shape = MaterialTheme.shapes.extraExtraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { illustration() }
            }
            Spacer(Modifier.height(GallerySpacing.Xl))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.galleryFadeRise(delayMillis = 100).semantics { heading() },
            )
            Spacer(Modifier.height(GallerySpacing.Sm))
            Text(
                body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.galleryFadeRise(delayMillis = 180),
            )
            action?.let {
                Spacer(Modifier.height(GallerySpacing.Xl))
                Box(Modifier.galleryFadeRise(delayMillis = 260)) { it() }
            }
        }
    }
}

@Composable
private fun AdaptiveStatePreview() {
    LightforgeTheme(darkTheme = false) {
        GalleryStateContent(
            title = "Your library is empty",
            body = "Available photos and videos will appear here.",
            illustrationDescription = "Empty library placeholder",
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
}

@Preview(name = "Compact", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun CompactStatePreview() = AdaptiveStatePreview()

@Preview(name = "Medium", widthDp = 700, heightDp = 900, showBackground = true)
@Composable
private fun MediumStatePreview() = AdaptiveStatePreview()

@Preview(name = "Expanded", widthDp = 1_000, heightDp = 800, showBackground = true)
@Composable
private fun ExpandedStatePreview() = AdaptiveStatePreview()
