package com.librestatic.lightforge

import com.librestatic.lightforge.core.designsystem.GalleryFoldInfo
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass

/** Which shared-view entries the viewer menu offers. */
internal data class SharedViewOffers(val faceToFace: Boolean, val outerDisplay: Boolean)

internal fun sharedViewOffers(
    trashContext: Boolean,
    foldInfo: GalleryFoldInfo?,
    windowClass: GalleryWindowClass,
    outerDisplayAvailable: Boolean,
    outerDisplayActive: Boolean,
): SharedViewOffers = SharedViewOffers(
    faceToFace = !trashContext && (foldInfo?.isSeparating == true || windowClass != GalleryWindowClass.Compact),
    outerDisplay = !trashContext && outerDisplayAvailable && !outerDisplayActive,
)
