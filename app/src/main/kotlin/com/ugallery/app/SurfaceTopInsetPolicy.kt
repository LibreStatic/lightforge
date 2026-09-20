package com.ugallery.app

/**
 * Routes that paint their own full-bleed surface behind the system bars, so the scaffold gives
 * them no window insets at all.
 */
internal fun surfaceIsFullBleed(route: SurfaceRoute): Boolean = when (route) {
    SurfaceRoute.Viewer,
    SurfaceRoute.PhotoEditor,
    SurfaceRoute.VideoEditor,
    SurfaceRoute.PrivateAlbum,
    SurfaceRoute.PrivateAlbumPicker,
    -> true
    else -> false
}

/**
 * Routes that host their own top app bar inside the content, so the scaffold withholds the top
 * inset and lets that bar consume it.
 */
internal fun surfaceOwnsTopBar(route: SurfaceRoute): Boolean = when (route) {
    SurfaceRoute.PublicationRecoveries,
    SurfaceRoute.Settings,
    SurfaceRoute.About,
    SurfaceRoute.People,
    SurfaceRoute.Moment,
    SurfaceRoute.ManualMoment,
    SurfaceRoute.MemoryVideo,
    SurfaceRoute.MomentParticipants,
    SurfaceRoute.MotionPhoto,
    SurfaceRoute.CreationGif,
    SurfaceRoute.Collage,
    SurfaceRoute.MemoriesBrowser,
    -> true
    else -> false
}

/**
 * Controls are drawn above the route's content, before any top bar the route owns, so whenever the
 * scaffold withholds the top inset the control has to apply it itself or it paints over the status
 * bar. True for both full-bleed routes and routes that own their top bar.
 */
internal fun surfaceControlsNeedTopInset(route: SurfaceRoute): Boolean =
    surfaceIsFullBleed(route) || surfaceOwnsTopBar(route)
