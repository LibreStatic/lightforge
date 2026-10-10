package com.librestatic.lightforge.feature.viewer

import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Switches the host activity window to [ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT] while [enabled]
 * so Display P3 bitmaps are not clipped to sRGB, and restores the previous mode afterwards.
 * Wide-gamut windows render in FP16 (more GPU memory and power), so only wrap the surfaces that
 * show photos. Does nothing without an activity or on screens that are not wide-gamut.
 */
@Composable
fun WideColorGamutWindowEffect(enabled: Boolean) {
    val context = LocalContext.current
    val activity = context.findViewerActivity()
    val wideGamutScreen = LocalConfiguration.current.isScreenWideColorGamut
    DisposableEffect(activity, enabled, wideGamutScreen) {
        val window = activity?.window
        if (!enabled || !wideGamutScreen || window == null) return@DisposableEffect onDispose {}
        val previous = window.colorMode
        window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        onDispose { window.colorMode = previous }
    }
}
