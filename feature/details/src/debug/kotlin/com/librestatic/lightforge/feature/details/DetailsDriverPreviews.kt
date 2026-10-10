package com.librestatic.lightforge.feature.details

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.AudioStream
import com.librestatic.lightforge.core.model.CheapMediaDetails
import com.librestatic.lightforge.core.model.ColorRange
import com.librestatic.lightforge.core.model.ColorStandard
import com.librestatic.lightforge.core.model.ColorTransfer
import com.librestatic.lightforge.core.model.ContainerInfo
import com.librestatic.lightforge.core.model.ImageTechnicalInfo
import com.librestatic.lightforge.core.model.ExifLoadResult
import com.librestatic.lightforge.core.model.ExifMediaDetails
import com.librestatic.lightforge.core.model.LocationAccessState
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaLocation
import com.librestatic.lightforge.core.model.OtherStream
import com.librestatic.lightforge.core.model.TechnicalMediaDetails
import com.librestatic.lightforge.core.model.VideoStream

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

    /** A 4K HEVC Main 10 HLG clip with stereo AAC and a camera-motion track. */
    val videoTechnical = TechnicalMediaDetails(
        container = ContainerInfo(
            mimeType = "video/mp4",
            bitrate = 49_800_000,
            durationMillis = 13_400,
            captureFrameRate = 59.94f,
            encoder = "Google Pixel 8 Pro camera, com.google.android.GoogleCamera/Pixel 8 Pro",
            trackCount = 3,
        ),
        videoStreams = listOf(
            VideoStream(
                trackIndex = 0,
                mimeType = "video/hevc",
                profile = "Main 10",
                level = "5.1 (High tier)",
                width = 3840,
                height = 2160,
                rotationDegrees = 90,
                frameRate = 29.97f,
                bitrate = 48_600_000,
                colorStandard = ColorStandard.Bt2020,
                colorTransfer = ColorTransfer.Hlg,
                colorRange = ColorRange.Limited,
                hasHdrStaticInfo = true,
                bitDepth = 10,
                language = "eng",
            ),
        ),
        audioStreams = listOf(
            AudioStream(
                trackIndex = 1,
                mimeType = "audio/mp4a-latm",
                profile = "LC",
                channels = 2,
                sampleRate = 48_000,
                bitrate = 256_000,
                language = "eng",
            ),
        ),
        otherStreams = listOf(OtherStream(trackIndex = 2, mimeType = "application/x-camera-motion")),
    )

    /** A Display P3 photo with flash, white balance and metering. */
    val photoTechnical = TechnicalMediaDetails(
        image = ImageTechnicalInfo(
            whiteBalance = 0,
            flash = 0x19,
            exposureProgram = 2,
            meteringMode = 2,
            exposureBias = -0.67,
            focalLength35mm = 24,
            digitalZoomRatio = 1.5,
            sceneCaptureType = 0,
            lensMake = "Google",
            software = "HDR+ 1.0.612345678zd",
            artist = "Facundo",
            copyright = "© 2026 LibreStatic",
            description = "Plaza de Mayo at golden hour, looking towards the Casa Rosada",
            dateTimeDigitized = "2026:09:03 15:56:00",
            subsecondTime = "123",
            gpsAltitudeMeters = 25.0,
            xResolution = 72.0,
            yResolution = 72.0,
            resolutionUnit = 2,
            compression = 6,
            exifColorSpace = 65535,
            orientation = 6,
            decodedColorSpace = "Display P3",
            bitDepth = 8,
            hasGainMap = true,
        ),
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
fun DetailsPhotoPreview() = DetailsFrame { DetailsContent(DetailsPreviewData.photo, DetailsPreviewData.withLocation, false, {}, technical = DetailsPreviewData.photoTechnical, placeName = "Buenos Aires") }

/** No location in the file: the Location card shows its empty state. */
@Preview
@Composable
fun DetailsNoLocationPreview() = DetailsFrame { DetailsContent(DetailsPreviewData.photo, DetailsPreviewData.withoutLocation, false, {}, detectedText = "Café Tortoni\nAv. de Mayo 825") }

/** A video: no EXIF, so the Camera card explains why and Location is empty. */
@Preview
@Composable
fun DetailsVideoPreview() = DetailsFrame { DetailsContent(DetailsPreviewData.video, ExifLoadResult.NotAnImage, false, {}, technical = DetailsPreviewData.videoTechnical) }

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
