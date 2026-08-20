package com.ugallery.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun GalleryStateContent(
    title: String,
    body: String,
    illustrationDescription: String,
    modifier: Modifier = Modifier,
    illustration: @Composable () -> Unit = {
        Icon(
            imageVector = GalleryIcons.Image,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    },
) {
    Box(modifier = modifier.padding(GallerySpacing.Xxl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(GalleryRadii.Large))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .semantics { contentDescription = illustrationDescription },
                contentAlignment = Alignment.Center,
            ) { illustration() }
            Spacer(Modifier.height(GallerySpacing.Xl))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(GallerySpacing.Sm))
            Text(
                body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AdaptiveStatePreview() {
    UGalleryTheme(darkTheme = false) {
        GalleryStateContent(
            title = "Your library is empty",
            body = "Available photos and videos will appear here.",
            illustrationDescription = "Empty library placeholder",
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
}

@Preview(name = "Compact", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun CompactStatePreview() = AdaptiveStatePreview()

@Preview(name = "Medium", widthDp = 700, heightDp = 900, showBackground = true)
@Composable
private fun MediumStatePreview() = AdaptiveStatePreview()

@Preview(name = "Expanded", widthDp = 1_000, heightDp = 800, showBackground = true)
@Composable
private fun ExpandedStatePreview() = AdaptiveStatePreview()
