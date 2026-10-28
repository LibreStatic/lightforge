package com.librestatic.lightforge.feature.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryOverlayTokens

private class LastValue<T : Any> { var value: T? = null }

/** The latest non-null [value], so an exit animation keeps drawing what just disappeared. */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastValue<T>() }
    if (value != null) holder.value = value
    return value ?: holder.value
}

/** Which state the last two-finger or double tap put the video in. */
internal data class PlayPausePulse(val playing: Boolean, val generation: Int)

/** Speed badge of a fast-forward or rewind hold, in the slot the slow-motion badge uses. */
@Composable
internal fun BoxScope.FastHoldPill(indicator: FastHoldIndicator?) {
    val shown = rememberLastNonNull(indicator)
    AnimatedVisibility(
        visible = indicator != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        val current = shown ?: return@AnimatedVisibility
        // Same sign convention as the slow-motion badge: a minus sign means backwards.
        val label = slowMotionRateLabel(if (current.forward) current.speed else -current.speed)
        val description = stringResource(
            if (current.forward) R.string.viewer_fast_forward_speed else R.string.viewer_fast_rewind_speed,
            label,
        )
        Row(
            modifier = Modifier
                .slowMotionOverlayTopPadding()
                .background(GalleryOverlayTokens.TimelineSurface, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (current.forward) GalleryIcons.FastForward else GalleryIcons.FastRewind,
                contentDescription = null,
                tint = GalleryOverlayTokens.Content,
                modifier = Modifier.size(20.dp),
            )
            Text(label, color = GalleryOverlayTokens.Content, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** "« 30 s" / "30 s »" at the edge of the zone the repeated seek taps land in. */
@Composable
internal fun BoxScope.SeekAccumulationIndicator(accumulation: SeekAccumulation?) {
    val current = rememberLastNonNull(accumulation)
    AnimatedVisibility(
        visible = accumulation != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(if (current?.direction == -1) Alignment.CenterStart else Alignment.CenterEnd),
    ) {
        val value = current ?: return@AnimatedVisibility
        val back = value.direction < 0
        val resources = LocalContext.current.resources
        val description = resources.getQuantityString(
            if (back) R.plurals.viewer_seek_back_description else R.plurals.viewer_seek_forward_description,
            value.totalSeconds,
            value.totalSeconds,
        )
        Text(
            stringResource(
                if (back) R.string.viewer_seek_back_total else R.string.viewer_seek_forward_total,
                value.totalSeconds,
            ),
            color = GalleryOverlayTokens.Content,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .background(GalleryOverlayTokens.FeedbackSurface, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { contentDescription = description },
        )
    }
}

/** Brief centered play or pause icon confirming a two-finger or double tap toggle. */
@Composable
internal fun BoxScope.PlayPausePulseIndicator(pulse: PlayPausePulse?) {
    val shown = rememberLastNonNull(pulse)
    AnimatedVisibility(
        visible = pulse != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center),
    ) {
        val playing = shown?.playing ?: return@AnimatedVisibility
        val label = stringResource(if (playing) R.string.viewer_play else R.string.viewer_pause)
        Icon(
            if (playing) GalleryIcons.Play else GalleryIcons.Pause,
            contentDescription = label,
            tint = GalleryOverlayTokens.Content,
            modifier = Modifier
                .background(GalleryOverlayTokens.FeedbackSurface, CircleShape)
                .padding(16.dp)
                .size(40.dp),
        )
    }
}
