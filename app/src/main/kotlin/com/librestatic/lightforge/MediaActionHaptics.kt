package com.librestatic.lightforge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.librestatic.lightforge.core.mediastore.MediaAction
import com.librestatic.lightforge.core.mediastore.MediaActionPhase
import com.librestatic.lightforge.core.mediastore.MediaActionSnapshot

/**
 * A soft haptic when a media action settles: a toggle for favorites, a confirm for trash, restore
 * and delete, a reject when the system request fails. A user cancelling the system dialog stays
 * silent, and a snapshot restored with the activity does not replay its old outcome.
 */
@Composable
internal fun MediaActionHaptics(snapshot: MediaActionSnapshot?) {
    val haptics = LocalHapticFeedback.current
    val restored = remember { snapshot }
    LaunchedEffect(snapshot) {
        if (snapshot == null || snapshot === restored) return@LaunchedEffect
        val type = mediaActionHapticType(snapshot.progress.action, snapshot.phase) ?: return@LaunchedEffect
        haptics.performHapticFeedback(type)
    }
}

internal fun mediaActionHapticType(action: MediaAction, phase: MediaActionPhase): HapticFeedbackType? =
    when (phase) {
        MediaActionPhase.Complete -> when (action) {
            is MediaAction.Favorite -> if (action.enabled) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
            MediaAction.Write -> null
            else -> HapticFeedbackType.Confirm
        }
        is MediaActionPhase.RequestFailed -> HapticFeedbackType.Reject
        else -> null
    }
