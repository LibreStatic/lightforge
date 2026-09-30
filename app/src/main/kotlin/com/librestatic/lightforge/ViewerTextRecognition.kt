package com.librestatic.lightforge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.librestatic.lightforge.core.ml.InteractiveTextRecognizer
import com.librestatic.lightforge.feature.viewer.textselect.ViewerTextRecognizer

/** One on-demand ML Kit recognizer per viewer, released when the viewer leaves composition. */
@Composable
internal fun rememberViewerTextRecognizer(): ViewerTextRecognizer {
    val recognizer = remember { InteractiveTextRecognizer() }
    DisposableEffect(recognizer) { onDispose { recognizer.close() } }
    return remember(recognizer) { { bitmap -> recognizer.recognize(bitmap) } }
}
