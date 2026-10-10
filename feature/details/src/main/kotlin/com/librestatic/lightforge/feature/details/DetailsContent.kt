package com.librestatic.lightforge.feature.details

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryLoadingIndicator
import com.librestatic.lightforge.core.designsystem.rememberGalleryReducedMotion
import com.librestatic.lightforge.core.model.CheapMediaDetails
import com.librestatic.lightforge.core.model.ExifLoadResult
import com.librestatic.lightforge.core.model.AudioStream
import com.librestatic.lightforge.core.model.ColorRange
import com.librestatic.lightforge.core.model.ImageTechnicalInfo
import com.librestatic.lightforge.core.model.LocationAccessState
import com.librestatic.lightforge.core.model.OtherStream
import com.librestatic.lightforge.core.model.TechnicalMediaDetails
import com.librestatic.lightforge.core.model.VideoStream
import java.time.ZoneId

/**
 * Media details grouped as Captured, File, Camera, Image, Container, stream and Location cards. Values are formatted by
 * [DetailsFormatting]; anything the file does not carry is left out, and a missing location
 * says so instead of showing an empty row.
 */
@Composable
fun DetailsContent(
    cheap: CheapMediaDetails,
    exif: ExifLoadResult?,
    isExifLoading: Boolean,
    onLoadExif: () -> Unit,
    detectedText: String? = null,
    technical: TechnicalMediaDetails? = null,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    placeName: String? = null,
    showTitle: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val ready = (exif as? ExifLoadResult.Ready)?.details
    val unknown = stringResource(R.string.details_unknown)
    val image = technical?.image
    val moment = remember(ready, cheap.timelineSortMillis) {
        DetailsFormatting.exifMoment(ready?.dateTimeOriginal, ready?.offsetTimeOriginal)
            ?: DetailsFormatting.fileMoment(cheap.timelineSortMillis, ZoneId.systemDefault())
    }

    Column(
        modifier = modifier
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showTitle) {
            Text(
                stringResource(R.string.details_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }

        if (moment != null) {
            DetailsSection("captured", Icons.Rounded.Event, stringResource(R.string.details_section_captured)) {
                DetailRow(stringResource(R.string.details_date), DetailsFormatting.date(moment, locale))
                DetailRow(stringResource(R.string.details_time), DetailsFormatting.time(moment, locale))
                if (ready?.dateTimeOriginal != null) {
                    DetailsFormatting.preciseTime(moment, image?.subsecondTime)?.let {
                        DetailRow(stringResource(R.string.details_precise_time), it)
                    }
                }
            }
        }

        DetailsSection("file", Icons.Rounded.Description, stringResource(R.string.details_section_file)) {
            DetailRow(stringResource(R.string.details_name), cheap.displayName ?: unknown)
            DetailRow(
                stringResource(R.string.details_format),
                DetailsFormatting.formatName(cheap.mimeType, cheap.displayName) ?: unknown,
            )
            if (cheap.sizeBytes > 0) {
                DetailRow(stringResource(R.string.details_size), Formatter.formatShortFileSize(context, cheap.sizeBytes))
            }
            DetailsFormatting.dimensions(cheap.width, cheap.height, locale)?.let {
                DetailRow(stringResource(R.string.details_dimensions), it)
            }
            DetailsFormatting.duration(cheap.durationMillis)?.let {
                DetailRow(stringResource(R.string.details_duration), it)
            }
            cheap.relativePath?.takeIf { it.isNotBlank() }?.let {
                DetailRow(stringResource(R.string.details_folder), it.trimEnd('/'))
            }
        }

        // Videos carry no EXIF; their container and stream sections replace Camera and Location.
        val isVideo = exif is ExifLoadResult.NotAnImage
        if (!isVideo) DetailsSection("camera", Icons.Rounded.PhotoCamera, stringResource(R.string.details_section_camera)) {
            when {
                isExifLoading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    GalleryLoadingIndicator()
                }
                exif == null -> GalleryExpressiveButton(onClick = onLoadExif) {
                    Text(stringResource(R.string.details_load_metadata))
                }
                ready != null -> {
                    val camera = DetailsFormatting.cameraName(ready.make, ready.model)
                    val aperture = DetailsFormatting.aperture(ready.aperture, locale)
                    val shutter = DetailsFormatting.exposure(ready.exposureTime, locale)
                    val iso = DetailsFormatting.iso(ready.iso)
                    val focal = DetailsFormatting.focalLength(ready.focalLength, locale)
                    val lens = ready.lensModel?.trim()?.takeIf { it.isNotEmpty() }
                    val extras = image?.let { cameraExtras(it, locale) }.orEmpty()
                    if (extras.isEmpty() && listOf(camera, lens, aperture, shutter, iso, focal).all { it == null }) {
                        SectionMessage(stringResource(R.string.details_camera_empty))
                    }
                    // cameraName falls back to the maker alone, which is not a model.
                    val cameraLabel = if (ready.model.isNullOrBlank()) R.string.details_camera_make else R.string.details_camera_model
                    camera?.let { DetailRow(stringResource(cameraLabel), it) }
                    lens?.let { DetailRow(stringResource(R.string.details_lens), it) }
                    aperture?.let { DetailRow(stringResource(R.string.details_aperture), it) }
                    shutter?.let { DetailRow(stringResource(R.string.details_shutter), it) }
                    iso?.let { DetailRow(stringResource(R.string.details_iso), it.removePrefix("ISO ")) }
                    focal?.let { DetailRow(stringResource(R.string.details_focal_length), it) }
                    extras.forEach { (label, value) -> DetailRow(stringResource(label), value) }
                }
                exif is ExifLoadResult.NotAnImage -> SectionMessage(stringResource(R.string.details_metadata_not_image))
                exif is ExifLoadResult.CorruptOrUnsupported -> SectionMessage(stringResource(R.string.details_metadata_unavailable))
                else -> SectionMessage(stringResource(R.string.details_media_unavailable))
            }
        }

        if (image != null) ImageSection(image, locale)
        if (technical != null) TechnicalSections(technical, cheap.mimeType, cheap.displayName, locale)

        if (exif != null && !isExifLoading && !isVideo) {
            DetailsSection("location", Icons.Rounded.Place, stringResource(R.string.details_location)) {
                val location = ready?.location
                when {
                    ready?.locationState == LocationAccessState.PermissionRequired ->
                        SectionMessage(stringResource(R.string.details_location_permission))
                    location != null && ready.locationState == LocationAccessState.Available -> {
                        placeName?.let { DetailRow(stringResource(R.string.details_place), stringResource(R.string.details_place_near, it)) }
                        DetailRow(
                            stringResource(R.string.details_coordinates),
                            DetailsFormatting.coordinates(location.latitude, location.longitude),
                        )
                        DetailsFormatting.altitude(image?.gpsAltitudeMeters, locale)?.let {
                            DetailRow(stringResource(R.string.details_altitude), it)
                        }
                    }
                    else -> EmptyLocation()
                }
            }
        }

        detectedText?.takeIf { it.isNotBlank() }?.let { text ->
            val detectedTextLabel = stringResource(R.string.details_detected_text)
            DetailsSection("text", Icons.Rounded.TextFields, detectedTextLabel) {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(detectedTextLabel, text))
                    },
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.details_copy_text), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Camera-card rows beyond the cached EXIF fields; resolved to (label, value) pairs. */
@Composable
private fun cameraExtras(image: ImageTechnicalInfo, locale: java.util.Locale): List<Pair<Int, String>> = buildList {
    image.lensMake?.let { add(R.string.details_lens_make to it) }
    DetailsFormatting.focalLength35(image.focalLength35mm)?.let { add(R.string.details_focal_length_35 to it) }
    DetailsFormatting.exposureBias(image.exposureBias, locale)?.let { add(R.string.details_exposure_bias to it) }
    DetailsFormatting.exposureProgramLabel(image.exposureProgram)?.let { add(R.string.details_exposure_program to stringResource(it)) }
    DetailsFormatting.meteringLabel(image.meteringMode)?.let { add(R.string.details_metering to stringResource(it)) }
    DetailsFormatting.flashLabel(image.flash)?.let { add(R.string.details_flash to stringResource(it)) }
    DetailsFormatting.whiteBalanceLabel(image.whiteBalance)?.let { add(R.string.details_white_balance to stringResource(it)) }
    DetailsFormatting.digitalZoom(image.digitalZoomRatio, locale)?.let { add(R.string.details_digital_zoom to it) }
    DetailsFormatting.sceneCaptureLabel(image.sceneCaptureType)?.let { add(R.string.details_scene_type to stringResource(it)) }
}

@Composable
private fun ImageSection(image: ImageTechnicalInfo, locale: java.util.Locale) {
    val rows = buildList<Pair<Int, String>> {
        image.software?.let { add(R.string.details_software to it) }
        image.artist?.let { add(R.string.details_artist to it) }
        image.copyright?.let { add(R.string.details_copyright to it) }
        image.description?.let { add(R.string.details_description to it) }
        DetailsFormatting.exifMoment(image.dateTimeDigitized, null)?.let {
            add(R.string.details_digitized to DetailsFormatting.dateTime(it, locale))
        }
        val colorSpace = image.decodedColorSpace ?: when (image.exifColorSpace) {
            1 -> "sRGB"
            65535 -> stringResource(R.string.details_color_space_uncalibrated)
            else -> null
        }
        colorSpace?.let { add(R.string.details_color_space to it) }
        image.bitDepth?.let { add(R.string.details_bit_depth to stringResource(R.string.details_bit_depth_value, it)) }
        if (image.hasGainMap) add(R.string.details_gain_map to stringResource(R.string.details_yes))
        DetailsFormatting.printResolution(image.xResolution, image.yResolution, image.resolutionUnit, locale)?.let {
            add(R.string.details_print_resolution to it)
        }
        (DetailsFormatting.compressionName(image.compression)
            ?: DetailsFormatting.compressionLabel(image.compression)?.let { stringResource(it) })?.let {
            add(R.string.details_compression to it)
        }
        DetailsFormatting.orientation(image.orientation)?.let { info ->
            val text = when {
                info.mirrored -> stringResource(R.string.details_orientation_mirrored)
                info.rotationDegrees == 0 -> stringResource(R.string.details_orientation_normal)
                else -> stringResource(R.string.details_orientation_rotated, info.rotationDegrees)
            }
            add(R.string.details_orientation to text)
        }
    }
    if (rows.isEmpty()) return
    DetailsSection("image", Icons.Rounded.Image, stringResource(R.string.details_section_image)) {
        rows.forEach { (label, value) -> DetailRow(stringResource(label), value) }
    }
}

@Composable
private fun TechnicalSections(
    technical: TechnicalMediaDetails,
    fileMime: String?,
    fileName: String?,
    locale: java.util.Locale,
) {
    val container = technical.container
    if (container != null) {
        val fileFormat = DetailsFormatting.formatName(fileMime, fileName)
        val format = DetailsFormatting.formatName(container.mimeType, null)?.takeIf { it != fileFormat }
        val bitrate = DetailsFormatting.bitrate(container.bitrate, locale)
        val capture = DetailsFormatting.frameRate(container.captureFrameRate, locale)
        val tracks = container.trackCount?.takeIf { it > 0 }
        if (listOf(format, bitrate, capture, container.encoder, tracks).any { it != null }) {
            DetailsSection("container", Icons.Rounded.Inventory2, stringResource(R.string.details_section_container)) {
                format?.let { DetailRow(stringResource(R.string.details_format), it) }
                bitrate?.let { DetailRow(stringResource(R.string.details_bitrate), it) }
                capture?.let { DetailRow(stringResource(R.string.details_capture_frame_rate), it) }
                container.encoder?.let { DetailRow(stringResource(R.string.details_encoder), it) }
                tracks?.let { DetailRow(stringResource(R.string.details_tracks), it.toString()) }
            }
        }
    }
    technical.videoStreams.forEachIndexed { position, stream ->
        val title = if (technical.videoStreams.size > 1) {
            stringResource(R.string.details_section_video_n, position + 1)
        } else stringResource(R.string.details_section_video)
        DetailsSection("video-${stream.trackIndex}", Icons.Rounded.Videocam, title) { VideoStreamRows(stream, locale) }
    }
    technical.audioStreams.forEachIndexed { position, stream ->
        val title = if (technical.audioStreams.size > 1) {
            stringResource(R.string.details_section_audio_n, position + 1)
        } else stringResource(R.string.details_section_audio)
        DetailsSection("audio-${stream.trackIndex}", Icons.Rounded.GraphicEq, title) { AudioStreamRows(stream, locale) }
    }
    technical.otherStreams.forEachIndexed { position, stream ->
        val title = if (technical.otherStreams.size > 1) {
            stringResource(R.string.details_section_other_n, position + 1)
        } else stringResource(R.string.details_section_other)
        val icon = if (DetailsFormatting.isSubtitleMime(stream.mimeType)) Icons.Rounded.Subtitles else Icons.Rounded.DataObject
        DetailsSection("other-${stream.trackIndex}", icon, title) { OtherStreamRows(stream, locale) }
    }
}

@Composable
private fun VideoStreamRows(stream: VideoStream, locale: java.util.Locale) {
    DetailRow(stringResource(R.string.details_track), (stream.trackIndex + 1).toString())
    DetailsFormatting.codecName(stream.mimeType)?.let { DetailRow(stringResource(R.string.details_codec), it) }
    stream.profile?.let { DetailRow(stringResource(R.string.details_profile), it) }
    stream.level?.let { DetailRow(stringResource(R.string.details_level), it) }
    DetailsFormatting.dimensions(stream.width ?: 0, stream.height ?: 0, locale)?.let {
        DetailRow(stringResource(R.string.details_resolution), it)
    }
    stream.rotationDegrees?.let { DetailRow(stringResource(R.string.details_rotation), "$it°") }
    DetailsFormatting.frameRate(stream.frameRate, locale)?.let { DetailRow(stringResource(R.string.details_frame_rate), it) }
    DetailsFormatting.bitrate(stream.bitrate, locale)?.let { DetailRow(stringResource(R.string.details_bitrate), it) }
    stream.bitDepth?.let { DetailRow(stringResource(R.string.details_bit_depth), stringResource(R.string.details_bit_depth_value, it)) }
    stream.colorStandard?.let { DetailRow(stringResource(R.string.details_color_primaries), DetailsFormatting.colorStandardName(it)) }
    stream.colorTransfer?.let { transfer ->
        val name = DetailsFormatting.colorTransferName(transfer) ?: stringResource(R.string.details_transfer_linear)
        DetailRow(stringResource(R.string.details_color_transfer), name)
    }
    stream.colorRange?.let {
        val name = stringResource(if (it == ColorRange.Full) R.string.details_range_full else R.string.details_range_limited)
        DetailRow(stringResource(R.string.details_color_range), name)
    }
    if (stream.hasHdrStaticInfo) DetailRow(stringResource(R.string.details_hdr_static_info), stringResource(R.string.details_present))
    DetailsFormatting.languageName(stream.language, locale)?.let { DetailRow(stringResource(R.string.details_language), it) }
}

@Composable
private fun AudioStreamRows(stream: AudioStream, locale: java.util.Locale) {
    DetailRow(stringResource(R.string.details_track), (stream.trackIndex + 1).toString())
    DetailsFormatting.codecName(stream.mimeType)?.let { DetailRow(stringResource(R.string.details_codec), it) }
    stream.profile?.let { DetailRow(stringResource(R.string.details_profile), it) }
    stream.channels?.let { count ->
        val text = when (DetailsFormatting.channelLayout(count)) {
            DetailsFormatting.ChannelLayout.Mono -> stringResource(R.string.details_channels_mono)
            DetailsFormatting.ChannelLayout.Stereo -> stringResource(R.string.details_channels_stereo)
            DetailsFormatting.ChannelLayout.Surround51 -> "5.1"
            DetailsFormatting.ChannelLayout.Surround71 -> "7.1"
            DetailsFormatting.ChannelLayout.Other -> pluralStringResource(R.plurals.details_channels_count, count, count)
        }
        DetailRow(stringResource(R.string.details_channels), text)
    }
    DetailsFormatting.sampleRate(stream.sampleRate, locale)?.let { DetailRow(stringResource(R.string.details_sample_rate), it) }
    DetailsFormatting.bitrate(stream.bitrate, locale)?.let { DetailRow(stringResource(R.string.details_bitrate), it) }
    stream.bitDepth?.let { DetailRow(stringResource(R.string.details_bit_depth), stringResource(R.string.details_bit_depth_value, it)) }
    DetailsFormatting.languageName(stream.language, locale)?.let { DetailRow(stringResource(R.string.details_language), it) }
}

@Composable
private fun OtherStreamRows(stream: OtherStream, locale: java.util.Locale) {
    DetailRow(stringResource(R.string.details_track), (stream.trackIndex + 1).toString())
    DetailsFormatting.otherStreamName(stream.mimeType)?.let { DetailRow(stringResource(R.string.details_type), it) }
    DetailsFormatting.languageName(stream.language, locale)?.let { DetailRow(stringResource(R.string.details_language), it) }
}

/** A collapsible tonal card; the header icon sits on a secondary-container badge. */
@Composable
private fun DetailsSection(
    key: String,
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(true) }
    val reducedMotion = rememberGalleryReducedMotion()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (reducedMotion) Modifier else Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())),
    ) {
        Column {
            val actionLabel = stringResource(if (expanded) R.string.details_collapse_section else R.string.details_expand_section, title)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = expanded, role = Role.Button, onValueChange = { expanded = it })
                    .semantics { heading() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = actionLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { content() }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The label keeps its natural width (capped) so long unbroken values such as file names
        // get the rest of the row instead of wrapping mid-word.
        Text(
            label,
            modifier = Modifier.widthIn(max = 152.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun SectionMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyLocation() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Rounded.LocationOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text(stringResource(R.string.details_location_missing), style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current)
            Text(
                stringResource(R.string.details_location_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
