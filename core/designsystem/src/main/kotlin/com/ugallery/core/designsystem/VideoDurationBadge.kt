package com.ugallery.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Compact, high-contrast marker used on video cells in media grids. */
@Composable
fun VideoDurationBadge(
    durationMillis: Long,
    modifier: Modifier = Modifier,
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
        Text(
            text = formatVideoDuration(durationMillis),
            color = GalleryOverlayTokens.Content,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
            ),
        )
    }
}

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
fun videoDurationDescription(durationMillis: Long): String = stringResource(
    R.string.video_duration_description,
    formatVideoDuration(durationMillis),
)
