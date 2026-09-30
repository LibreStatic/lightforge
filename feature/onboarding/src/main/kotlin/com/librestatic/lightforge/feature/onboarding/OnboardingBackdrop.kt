package com.librestatic.lightforge.feature.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.rememberGalleryReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** One floating shape. Positions are fractions of the backdrop; [depth] drives parallax and size. */
private class FloatingShape(
    val shape: Shape,
    val role: Int,
    val x: Float,
    val y: Float,
    val sizeDp: Float,
    val depth: Float,
    val spin: Float,
    val phase: Float,
    val wobble: Float,
) {
    // Building a polygon outline is costly, and the side never changes, so build it once.
    private var cached: Outline? = null

    fun outline(side: Float, direction: LayoutDirection, density: Density): Outline =
        cached ?: shape.createOutline(Size(side, side), direction, density).also { cached = it }
}

/** How far, in backdrop widths, one page move scrolls the shapes along their rail. */
private const val MoveLength = 0.55f

/** Horizontal span the rail wraps around, so shapes leaving one edge come back on the other. */
private const val WrapSpan = 1.5f

/**
 * The launcher star (`ic_launcher_foreground`, 108-unit viewport): five petals, each split into an
 * inner and an outer half with the brand colours the splash screen shows. The intro starts from
 * exactly these colours, so the hand-off from the splash is seamless, then tints them to the theme.
 */
private class LogoHalf(val path: String, val brand: Color, val inner: Boolean)

private val LogoHalves = listOf(
    LogoHalf("M54.00,50.60 L63.50,41.00 L44.50,41.00 Z", Color(0xFFFFC247), inner = true),
    LogoHalf("M44.50,41.00 L63.50,41.00 L54.00,26.60 Z", Color(0xFFF0A21E), inner = false),
    LogoHalf("M57.23,52.95 L69.30,59.02 L63.43,40.95 Z", Color(0xFFFF8A4C), inner = true),
    LogoHalf("M63.43,40.95 L69.30,59.02 L80.06,45.53 Z", Color(0xFFEB6A2C), inner = false),
    LogoHalf("M56.00,56.75 L53.96,70.10 L69.33,58.93 Z", Color(0xFFF2607A), inner = true),
    LogoHalf("M69.33,58.93 L53.96,70.10 L70.11,76.17 Z", Color(0xFFD4415C), inner = false),
    LogoHalf("M52.00,56.75 L38.67,58.93 L54.04,70.10 Z", Color(0xFFC45ED8), inner = true),
    LogoHalf("M54.04,70.10 L38.67,58.93 L37.89,76.17 Z", Color(0xFFA23FBA), inner = false),
    LogoHalf("M50.77,52.95 L44.57,40.95 L38.70,59.02 Z", Color(0xFF7B61F0), inner = true),
    LogoHalf("M38.70,59.02 L44.57,40.95 L27.94,45.53 Z", Color(0xFF5B43D4), inner = false),
)
private const val LogoViewport = 108f
private const val LogoStroke = 2.6f

/** Splash icons scale their 108-unit viewport so the 72-unit safe zone fills the icon view. */
private const val SplashIconViewportScale = 108f / 72f

/** Maps [value] from [start]..[end] to 0..1, clamped. */
private fun phase(value: Float, start: Float, end: Float) = ((value - start) / (end - start)).coerceIn(0f, 1f)

/** Degrees a shape rolls per page move, so travel reads as rolling rather than sliding. */
private const val MoveRoll = 90f

/**
 * Decorative field of Material expressive shapes behind the first-run wizard, mostly to fill the
 * empty sides of large screens. Shapes drift and turn slowly, roll along a rail as [progress]
 * advances (one unit per page move) and give a light outward burst
 * whenever [beat] changes.
 *
 * [intro] runs 0 to 1 once on first launch: the app's star logo in the theme colour pops, its
 * petals scatter and fade while the shapes emerge from the centre to their places. At 1 (or with
 * no intro) only the shapes are drawn. [logoBounds] (window pixels) places the logo exactly where
 * the splash screen left its icon; without it the logo is centred at the splash's 240dp size. Both are
 * lambdas read inside the backdrop, so pager scrolling never recomposes the wizard. While
 * [dispersed] is set they fly out from the centre and fade, then [onDispersed] runs; clearing it
 * brings them back.
 *
 * Colours are container roles at low alpha with nothing drawn on top, so they never carry
 * content. With system animations off the field is static and the exit finishes immediately.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OnboardingBackdrop(
    beat: () -> Int,
    progress: () -> Float,
    intro: () -> Float,
    logoBounds: Rect?,
    dispersed: Boolean,
    onDispersed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberGalleryReducedMotion()
    val colors = MaterialTheme.colorScheme
    val palette = listOf(colors.primaryContainer, colors.secondaryContainer, colors.tertiaryContainer)
    val logoInner = colors.primary
    val logoOuter = colors.secondary
    val halves = remember { LogoHalves.map { PathParser().parsePathString(it.path).toPath() } }
    val seed = rememberSaveable { Random.nextInt() }
    val finish by rememberUpdatedState(onDispersed)
    // Tracked as state so the long-lived collectors below always call the latest lambdas.
    val currentProgress by rememberUpdatedState(progress)
    val currentBeat by rememberUpdatedState(beat)

    // Thresholds far below the defaults: the rail is multiplied by several screen widths, so the
    // default 0.01 cut-off snapped the last few dozen pixels in one frame when a move settled.
    val rail = remember { Animatable(0f, visibilityThreshold = 0.0001f) }
    LaunchedEffect(reducedMotion) {
        snapshotFlow { currentProgress() }.collectLatest { target ->
            if (reducedMotion) {
                rail.snapTo(0f)
            } else {
                // A new target mid-move keeps the current velocity, so quick taps chain smoothly.
                rail.animateTo(target, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow, visibilityThreshold = 0.0001f))
            }
        }
    }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1_000_000_000f }
    }
    val burst = remember { Animatable(0f, visibilityThreshold = 0.0005f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        // No burst for the page the wizard opens on, only for the user's own moves.
        snapshotFlow { currentBeat() }.distinctUntilChanged().drop(1).collectLatest {
            // An impulse from rest, not a jump: the shapes swell out and bounce back continuously.
            burst.animateTo(
                0f,
                spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow, visibilityThreshold = 0.0005f),
                initialVelocity = 4f,
            )
        }
    }
    val exit = remember { Animatable(0f, visibilityThreshold = 0.0005f) }
    LaunchedEffect(dispersed, reducedMotion) {
        val target = if (dispersed) 1f else 0f
        if (reducedMotion) exit.snapTo(target) else exit.animateTo(target, tween(durationMillis = 800, easing = FastOutSlowInEasing))
        if (dispersed) finish()
    }

    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier.fillMaxSize().clearAndSetSemantics {}.onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        val count = when {
            maxWidth >= 840.dp -> 16
            maxWidth >= 600.dp -> 12
            else -> 6
        }
        val catalogue = listOf(
            MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.SoftBurst,
            MaterialShapes.Flower, MaterialShapes.Pill, MaterialShapes.Arch, MaterialShapes.Sunny,
            MaterialShapes.Gem, MaterialShapes.Cookie6Sided, MaterialShapes.Puffy,
        ).map { it.toShape() }
        // Keyed on density too: each shape caches an outline built at its pixel size.
        val shapes = remember(seed, count, LocalDensity.current) {
            val random = Random(seed)
            List(count) {
                val depth = 0.4f + random.nextFloat() * 0.6f
                FloatingShape(
                    shape = catalogue[random.nextInt(catalogue.size)],
                    role = random.nextInt(3),
                    x = random.nextFloat() * WrapSpan,
                    y = random.nextFloat(),
                    sizeDp = 40f + 120f * depth,
                    depth = depth,
                    spin = (6f + random.nextFloat() * 14f) * if (random.nextBoolean()) 1f else -1f,
                    phase = random.nextFloat() * 2f * PI.toFloat(),
                    wobble = 8f + random.nextFloat() * 16f,
                )
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val diagonal = hypot(size.width, size.height)
            val e = exit.value
            val b = burst.value
            val i = intro()
            // Shapes grow out of the logo's centre while its petals scatter.
            val emerge = FastOutSlowInEasing.transform(phase(i, 0.3f, 1f))
            shapes.forEach { s ->
                val side = s.sizeDp.dp.toPx()
                // Rail travel with parallax: nearer (bigger) shapes move further.
                val along = ((s.x - rail.value * MoveLength * s.depth) % WrapSpan + WrapSpan) % WrapSpan
                val wobble = s.wobble.dp.toPx()
                var cx = (along - (WrapSpan - 1f) / 2f) * size.width + sin(time * 0.35f + s.phase) * wobble
                var cy = s.y * size.height + cos(time * 0.27f + s.phase * 1.3f) * wobble
                val dx = cx - center.x
                val dy = cy - center.y
                val distance = hypot(dx, dy).coerceAtLeast(1f)
                // Bursts and the exit both push outwards along the line from the centre.
                val push = b * 40.dp.toPx() * s.depth + e * diagonal * (0.6f + s.depth)
                cx += dx / distance * push
                cy += dy / distance * push
                if (emerge < 1f) {
                    cx = center.x + (cx - center.x) * emerge
                    cy = center.y + (cy - center.y) * emerge
                }
                val scale = (1f + 0.15f * b + 0.4f * e) * emerge
                val roll = rail.value * MoveRoll * s.depth * if (s.spin > 0f) 1f else -1f
                val angle = s.phase * 57.3f + time * s.spin + roll + e * s.spin * 12f + (1f - emerge) * s.spin * 10f
                val alpha = (0.12f + 0.12f * s.depth) * (1f - e) * emerge
                translate(cx - side / 2f, cy - side / 2f) {
                    val pivot = Offset(side / 2f, side / 2f)
                    scale(scale, pivot) {
                        rotate(angle, pivot) {
                            drawOutline(
                                s.outline(side, layoutDirection, this),
                                color = palette[s.role],
                                alpha = alpha,
                            )
                        }
                    }
                }
            }
            if (i < 1f) {
                val anchor = logoBounds?.translate(-origin)
                val logoCenter = anchor?.center ?: center
                // The splash icon view is 240dp, but like an adaptive icon it fits the 72-unit safe
                // zone to the view, so the full 108-unit viewport spans 1.5 times that.
                val logoSize = (anchor?.width ?: 240.dp.toPx()) * SplashIconViewportScale
                drawLogo(halves, logoInner, logoOuter, i, logoCenter, logoSize)
            }
        }
    }
}

/**
 * The star logo for intro progress [i]: it starts in the brand colours, blends to the theme's
 * [inner] and [outer] colours during a short pop, then each petal (both halves together) flies
 * outwards along its own direction, turning and fading, so the mark breaks up into the shapes.
 */
private fun DrawScope.drawLogo(
    halves: List<Path>,
    inner: Color,
    outer: Color,
    i: Float,
    center: Offset,
    logo: Float,
) {
    val unit = logo / LogoViewport
    val pop = 1f + 0.08f * sin(PI.toFloat() * phase(i, 0f, 0.3f))
    val scatter = FastOutLinearInEasing.transform(phase(i, 0.25f, 0.85f))
    val fade = 1f - phase(i, 0.35f, 0.8f)
    val travel = hypot(size.width, size.height) * 0.3f
    val tint = FastOutSlowInEasing.transform(phase(i, 0.06f, 0.3f))
    halves.forEachIndexed { index, half ->
        val spec = LogoHalves[index]
        val petal = index / 2
        // Both halves of a petal share its direction, so each petal leaves in one piece.
        val bounds = halves[petal * 2].getBounds()
        val dx = bounds.center.x - LogoViewport / 2f
        val dy = bounds.center.y - LogoViewport / 2f
        val length = hypot(dx, dy).coerceAtLeast(0.01f)
        val offset = Offset(dx / length, dy / length) * (travel * scatter)
        withTransform({
            translate(center.x + offset.x, center.y + offset.y)
            scale(pop * (1f - 0.3f * scatter), pivot = Offset.Zero)
            rotate((if (petal % 2 == 0) 1f else -1f) * 120f * scatter, pivot = Offset.Zero)
            scale(unit, pivot = Offset.Zero)
            translate(-LogoViewport / 2f, -LogoViewport / 2f)
        }) {
            val color = lerp(spec.brand, if (spec.inner) inner else outer, tint)
            drawPath(half, color, alpha = fade)
            drawPath(half, color, alpha = fade, style = Stroke(width = LogoStroke, join = StrokeJoin.Round))
        }
    }
}
