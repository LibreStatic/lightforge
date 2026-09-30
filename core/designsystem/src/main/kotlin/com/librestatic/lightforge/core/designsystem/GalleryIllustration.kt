package com.librestatic.lightforge.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decorative header art built from Material expressive shapes. Every colour is a semantic role
 * paired with its own `on*` role, so it follows light, dark and dynamic themes without assets.
 *
 * Themes whose container roles are nearly identical (muted dynamic palettes, monochrome) would
 * fuse the three shapes into one blob, so each shape is cut out with a stroke in [backdrop] — the
 * colour of whatever surface the art sits on — which keeps them legible whatever their tones.
 *
 * With [animateEntrance] the art assembles once when it first composes: the main shape springs in
 * and the two behind it follow a beat later. It is meant for empty-state placeholders, and is
 * skipped entirely when the system animations are turned off.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GalleryShapeIllustration(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    backdrop: Color = MaterialTheme.colorScheme.surface,
    animateEntrance: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val reducedMotion = rememberGalleryReducedMotion()
    val animated = animateEntrance && !reducedMotion
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val front = remember { Animatable(if (animated) 0f else 1f) }
    val clover = remember { Animatable(if (animated) 0f else 1f) }
    val pill = remember { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        launch { front.animateTo(1f, spec) }
        launch { delay(70); clover.animateTo(1f, spec) }
        launch { delay(140); pill.animateTo(1f, spec) }
    }
    val stroke = (size.value * 0.03f).coerceAtLeast(2f).dp
    val density = LocalDensity.current
    val travel = with(density) { (size * 0.12f).toPx() }

    fun Modifier.cutShape(color: Color, shape: Shape, progress: Animatable<Float, *>, dx: Float, dy: Float) =
        graphicsLayer {
            val p = progress.value
            alpha = p.coerceIn(0f, 1f)
            val scale = 0.55f + 0.45f * p
            scaleX = scale
            scaleY = scale
            // The two back shapes drift out from behind the main one while they settle.
            translationX = dx * (1f - p)
            translationY = dy * (1f - p)
        }.background(backdrop, shape).padding(stroke).background(color, shape)

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size * 0.62f).offset(x = -size * 0.2f, y = size * 0.2f)
                .cutShape(colors.tertiaryContainer, MaterialShapes.Clover4Leaf.toShape(), clover, travel, -travel),
        )
        Box(
            Modifier.size(size * 0.42f).offset(x = size * 0.3f, y = -size * 0.28f)
                .cutShape(colors.secondaryContainer, MaterialShapes.Pill.toShape(), pill, -travel, travel),
        )
        Box(
            Modifier.size(size * 0.72f).cutShape(colors.primaryContainer, MaterialShapes.Cookie9Sided.toShape(), front, 0f, 0f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(size * 0.32f))
        }
    }
}

/**
 * Soft arrival for text and controls of an empty state: a short fade with a few dp of upward
 * travel. With system animations off it is shown immediately, so nothing is ever hidden.
 */
fun Modifier.galleryFadeRise(delayMillis: Int = 0): Modifier = composed {
    val reducedMotion = rememberGalleryReducedMotion()
    val progress = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            progress.snapTo(1f)
        } else {
            delay(delayMillis.toLong())
            progress.animateTo(1f, tween(durationMillis = 320))
        }
    }
    val rise = with(LocalDensity.current) { 10.dp.toPx() }
    graphicsLayer {
        alpha = progress.value
        translationY = rise * (1f - progress.value)
    }
}
