package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Media tiles that leave a grid (trash, restore, delete) fade out while the rest slide into the
 * gap, instead of vanishing. Tiles do not fade in, so paging in a new page or a placeholder turning
 * into its photo stays instant; reduced motion turns the animation off.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LazyGridItemScope.galleryGridItemAnimation(): Modifier {
    if (rememberGalleryReducedMotion()) return Modifier
    val motion = MaterialTheme.motionScheme
    return Modifier.animateItem(
        fadeInSpec = null,
        placementSpec = motion.defaultSpatialSpec(),
        fadeOutSpec = motion.defaultEffectsSpec(),
    )
}
