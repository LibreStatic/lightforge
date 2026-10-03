package com.librestatic.lightforge.feature.ownsync

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.ReadableContentMaxWidth

/**
 * List padding that keeps a form's content in a centred column no wider than
 * [ReadableContentMaxWidth], while the list itself still spans (and scrolls from) the whole
 * window width.
 */
internal fun readableListPadding(availableWidth: Dp, gutter: Dp = 16.dp): PaddingValues {
    val side = maxOf(gutter, (availableWidth - ReadableContentMaxWidth) / 2)
    return PaddingValues(start = side, top = gutter, end = side, bottom = gutter)
}
