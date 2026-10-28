package com.librestatic.lightforge.core.designsystem

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Settings' "Show video duration". Off keeps the play marker so video cells stay recognisable. */
val LocalShowVideoDuration = staticCompositionLocalOf { true }

/**
 * Compact, high-contrast marker used on video cells in media grids. While [pending] and the duration is
 * still unknown, three pulsing dots hold the place of the text (same pill and size as a "0:00"
 * duration, so the usual m:ss result does not jump) and crossfade to the duration once it is read.
 */
@Composable
fun VideoDurationBadge(
    durationMillis: Long,
    modifier: Modifier = Modifier,
    pending: Boolean = false,
) {
    Row(
        modifier = modifier
            .clearAndSetSemantics { }
            .clip(CircleShape)
            .background(GalleryOverlayTokens.DurationSurface)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = GalleryIcons.Play,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = GalleryOverlayTokens.Content,
        )
        if (LocalShowVideoDuration.current) Crossfade(
            targetState = pending && durationMillis <= 0L,
            animationSpec = tween(200),
            label = "video duration",
        ) { reading ->
            if (reading) PendingDots() else DurationText(formatVideoDuration(durationMillis))
        }
    }
}

@Composable
private fun DurationText(text: String, color: Color = GalleryOverlayTokens.Content) {
    Text(
        text = text,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 12.sp,
        ),
    )
}

/** Sized by an invisible "0:00", the width of most durations, so the pill does not resize when it resolves. */
@Composable
private fun PendingDots() {
    val still = rememberGalleryReducedMotion()
    val pulse = if (still) null else rememberInfiniteTransition(label = "reading duration")
    Box(contentAlignment = Alignment.Center) {
        DurationText(PendingWidthSample, color = Color.Transparent)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { index ->
                val alpha = pulse?.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = DotCycleMillis
                            0.4f at 0
                            1f at DotCycleMillis / 4
                            0.4f at DotCycleMillis / 2
                            0.4f at DotCycleMillis
                        },
                        repeatMode = RepeatMode.Restart,
                        initialStartOffset = StartOffset(index * DotCycleMillis / 6),
                    ),
                    label = "dot $index",
                )?.value ?: StillDotAlpha
                Box(
                    Modifier
                        .size(4.dp)
                        .graphicsLayer { this.alpha = alpha }
                        .background(GalleryOverlayTokens.Content, CircleShape),
                )
            }
        }
    }
}

private const val DotCycleMillis = 1_200
private const val PendingWidthSample = "0:00"
private const val StillDotAlpha = 0.7f

/**
 * Formats a MediaStore duration for compact display, rounded to the nearest second. Any non-zero
 * clip reads at least "0:01" so a sub-second video never looks empty.
 */
fun formatVideoDuration(durationMillis: Long): String {
    if (durationMillis <= 0L) return "--:--"
    val totalSeconds = ((durationMillis + 500L) / 1_000L).coerceAtLeast(1L)
    val seconds = totalSeconds % 60L
    val totalMinutes = totalSeconds / 60L
    return if (totalMinutes < 60L) {
        String.format(Locale.ROOT, "%d:%02d", totalMinutes, seconds)
    } else {
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    }
}

/** Localized TalkBack label for a video cell. */
@Composable
fun videoDurationDescription(durationMillis: Long, pending: Boolean = false): String =
    if (pending && durationMillis <= 0L) stringResource(R.string.video_duration_pending_description)
    else stringResource(R.string.video_duration_description, formatVideoDuration(durationMillis))
