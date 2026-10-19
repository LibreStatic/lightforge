package com.librestatic.lightforge.feature.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.librestatic.lightforge.core.designsystem.GalleryEmptyState
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar

/**
 * Full-screen state for an item the viewer cannot open: an expired temporary grant, or a file that
 * was moved or deleted. Leaving is the top bar's back arrow, so no loose button sits in the body.
 */
@Composable
fun ViewerMediaUnavailable(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize().testTag("viewer-media-unavailable"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize()) {
            GalleryTopAppBar(
                title = "",
                onBack = onClose,
                navigationContentDescription = stringResource(R.string.viewer_back),
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                GalleryEmptyState(
                    title = stringResource(R.string.viewer_unavailable_title),
                    body = stringResource(R.string.viewer_unavailable_body),
                    icon = GalleryIcons.BrokenImage,
                )
            }
        }
    }
}
