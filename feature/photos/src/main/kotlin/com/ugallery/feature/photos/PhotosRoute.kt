package com.ugallery.feature.photos

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryColors
import com.ugallery.core.designsystem.GalleryGridMetrics
import com.ugallery.core.designsystem.GallerySpacing

private val densityColumns = intArrayOf(3, 4, 5, 7)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotosRoute(
    itemCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    var densityIndex by rememberSaveable { mutableIntStateOf(0) }
    var anchorIndex by rememberSaveable { mutableIntStateOf(0) }
    var accumulatedZoom by remember { mutableFloatStateOf(1f) }
    val gridState = rememberLazyGridState()

    fun changeDensity(delta: Int) {
        val next = (densityIndex + delta).coerceIn(densityColumns.indices)
        if (next != densityIndex) {
            anchorIndex = gridState.firstVisibleItemIndex
            densityIndex = next
        }
    }

    LaunchedEffect(densityIndex) {
        if (itemCount > 0) gridState.scrollToItem(anchorIndex.coerceIn(0, itemCount - 1))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .semantics { testTagsAsResourceId = true }
            .padding(WindowInsets.safeDrawing.asPaddingValues()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Xl),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.library_local), style = MaterialTheme.typography.labelSmall, color = GalleryColors.Muted)
                Text(stringResource(R.string.photos_title), style = MaterialTheme.typography.headlineLarge)
            }
            Button(
                onClick = { changeDensity(1).also { if (densityIndex == densityColumns.lastIndex) changeDensity(-densityColumns.lastIndex) } },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.semantics { contentDescription = "Change grid density" },
            ) {
                Text("${densityColumns[densityIndex]}×")
            }
        }

        if (itemCount == 0) {
            EmptyLibrary(modifier = Modifier.fillMaxSize())
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(densityColumns[densityIndex]),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(GalleryGridMetrics.Gap),
                verticalArrangement = Arrangement.spacedBy(GalleryGridMetrics.Gap),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("timeline_grid")
                    .pointerInput(densityIndex) {
                        detectTransformGestures { _, _, zoom, _ ->
                            accumulatedZoom *= zoom
                            when {
                                accumulatedZoom > 1.22f -> {
                                    changeDensity(-1)
                                    accumulatedZoom = 1f
                                }
                                accumulatedZoom < 0.82f -> {
                                    changeDensity(1)
                                    accumulatedZoom = 1f
                                }
                            }
                        }
                    },
            ) {
                items(count = itemCount, key = { it }) { index ->
                    BenchmarkMediaCell(index)
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(GallerySpacing.Xxl), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(GallerySpacing.Xl))
            Text(stringResource(R.string.empty_library_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(GallerySpacing.Sm))
            Text(stringResource(R.string.empty_library_body), color = GalleryColors.Muted)
        }
    }
}

@Composable
private fun BenchmarkMediaCell(index: Int) {
    val palette = remember {
        listOf(
            Color(0xFF7593A8), Color(0xFFB7A48A), Color(0xFF6C8464), Color(0xFF907B8E),
            Color(0xFF557A83), Color(0xFFA77D61), Color(0xFF6F7890), Color(0xFF8A926C),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(palette[index % palette.size])
            .aspectRatio(1f)
            .testTag("media_$index"),
    )
}
