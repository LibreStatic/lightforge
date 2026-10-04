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
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.TextFields
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
import com.librestatic.lightforge.core.model.LocationAccessState
import java.time.ZoneId

/**
 * Media details grouped as Captured, File, Camera and Location cards. Values are formatted by
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

        DetailsSection("camera", Icons.Rounded.PhotoCamera, stringResource(R.string.details_section_camera)) {
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
                    if (listOf(camera, lens, aperture, shutter, iso, focal).all { it == null }) {
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
                }
                exif is ExifLoadResult.NotAnImage -> SectionMessage(stringResource(R.string.details_metadata_not_image))
                exif is ExifLoadResult.CorruptOrUnsupported -> SectionMessage(stringResource(R.string.details_metadata_unavailable))
                else -> SectionMessage(stringResource(R.string.details_media_unavailable))
            }
        }

        if (exif != null && !isExifLoading) {
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
