package com.librestatic.lightforge.feature.details

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.CheapMediaDetails
import com.librestatic.lightforge.core.model.ExifLoadResult
import com.librestatic.lightforge.core.model.ExifMediaDetails
import com.librestatic.lightforge.core.model.LocationAccessState
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaLocation

// Debug-only, zero-argument entry points for tools/compose-driver and Android Studio previews.
// Each one feeds fake details into the real DetailsContent.

/** Fake details shared with the viewer's driver previews. */
object DetailsPreviewData {
    val photo = CheapMediaDetails(
        key = MediaKey("external_primary", 1),
        displayName = "PXL_20260903_185600123.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 3_481_000,
        width = 4032,
        height = 3024,
        durationMillis = 0,
        timelineSortMillis = 1_788_465_360_000,
        relativePath = "DCIM/Camera/",
    )

    val video = CheapMediaDetails(
        key = MediaKey("external_primary", 2),
        displayName = "VID_20260903_190112.mp4",
        mimeType = "video/mp4",
        sizeBytes = 48_200_000,
        width = 1920,
        height = 1080,
        durationMillis = 13_400,
        timelineSortMillis = 1_788_465_672_000,
        relativePath = "DCIM/Camera/",
    )

    private val camera = ExifMediaDetails(
        orientation = 1,
        dateTimeOriginal = "2026:09:03 15:56:00",
        offsetTimeOriginal = "-03:00",
        make = "Google",
        model = "Pixel 8",
        lensModel = "Pixel 8 back camera 6.9mm f/1.68",
        focalLength = "6900/1000",
        aperture = "1.68",
        exposureTime = "0.008333",
        iso = 64,
        location = MediaLocation(-34.603722, -58.381592),
        locationState = LocationAccessState.Available,
    )

    val withLocation: ExifLoadResult = ExifLoadResult.Ready(camera, fromCache = true)

    val withoutLocation: ExifLoadResult = ExifLoadResult.Ready(
        camera.copy(location = null, locationState = LocationAccessState.Missing),
        fromCache = true,
    )
}

/** A phone photo with camera settings and a location. */
@Preview
@Composable
fun DetailsPhotoPreview() = DetailsFrame { DetailsContent(DetailsPreviewData.photo, DetailsPreviewData.withLocation, false, {}, placeName = "Buenos Aires") }

/** No location in the file: the Location card shows its empty state. */
@Preview
@Composable
fun DetailsNoLocationPreview() = DetailsFrame { DetailsContent(DetailsPreviewData.photo, DetailsPreviewData.withoutLocation, false, {}, detectedText = "Café Tortoni\nAv. de Mayo 825") }

/** A video: no EXIF, so the Camera card explains why and Location is empty. */
@Preview
@Composable
fun DetailsVideoPreview() = DetailsFrame { DetailsContent(DetailsPreviewData.video, ExifLoadResult.NotAnImage, false, {}) }

/** EXIF still loading. */
@Preview
@Composable
fun DetailsLoadingPreview() = DetailsFrame { DetailsContent(DetailsPreviewData.photo, null, true, {}) }

@Composable
private fun DetailsFrame(content: @Composable () -> Unit) {
    LightforgeTheme {
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) { content() }
    }
}
