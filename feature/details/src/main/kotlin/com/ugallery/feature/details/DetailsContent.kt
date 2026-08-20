package com.ugallery.feature.details

import android.text.format.DateFormat
import android.text.format.Formatter
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ugallery.core.model.CheapMediaDetails
import com.ugallery.core.model.ExifLoadResult
import com.ugallery.core.model.LocationAccessState
import java.util.Date

@Composable
fun DetailsContent(
    cheap: CheapMediaDetails,
    exif: ExifLoadResult?,
    isExifLoading: Boolean,
    onLoadExif: () -> Unit,
    detectedText: String? = null,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    Column(
        modifier = modifier
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.details_title), style = MaterialTheme.typography.headlineSmall)
        DetailRow(stringResource(R.string.details_name), cheap.displayName ?: stringResource(R.string.details_unknown))
        DetailRow(stringResource(R.string.details_type), cheap.mimeType ?: stringResource(R.string.details_unknown))
        DetailRow(stringResource(R.string.details_size), Formatter.formatFileSize(context, cheap.sizeBytes))
        DetailRow(stringResource(R.string.details_dimensions), "${cheap.width} × ${cheap.height}")
        DetailRow(
            stringResource(R.string.details_date),
            DateFormat.getMediumDateFormat(context).format(Date(cheap.timelineSortMillis)),
        )
        cheap.relativePath?.let { DetailRow(stringResource(R.string.details_folder), it) }
        detectedText?.let { text ->
            val detectedTextLabel = stringResource(R.string.details_detected_text)
            Text(detectedTextLabel, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(detectedTextLabel, text))
                },
            ) { Text(stringResource(R.string.details_copy_text)) }
        }

        when {
            isExifLoading -> CircularProgressIndicator()
            exif == null -> Button(onClick = onLoadExif) { Text(stringResource(R.string.details_load_metadata)) }
            exif is ExifLoadResult.Ready -> {
                val value = exif.details
                value.make?.let { DetailRow(stringResource(R.string.details_camera_make), it) }
                value.model?.let { DetailRow(stringResource(R.string.details_camera_model), it) }
                value.lensModel?.let { DetailRow(stringResource(R.string.details_lens), it) }
                value.iso?.let { DetailRow(stringResource(R.string.details_iso), it.toString()) }
                value.exposureTime?.let { DetailRow(stringResource(R.string.details_exposure), it) }
                value.dateTimeOriginal?.let {
                    DetailRow(
                        stringResource(R.string.details_original_time),
                        listOfNotNull(it, value.offsetTimeOriginal).joinToString(" "),
                    )
                }
                when (value.locationState) {
                    LocationAccessState.Available -> value.location?.let {
                        DetailRow(
                            stringResource(R.string.details_location),
                            String.format(locale, "%.5f, %.5f", it.latitude, it.longitude),
                        )
                    }
                    LocationAccessState.Missing -> DetailRow(
                        stringResource(R.string.details_location),
                        stringResource(R.string.details_location_missing),
                    )
                    LocationAccessState.PermissionRequired -> DetailRow(
                        stringResource(R.string.details_location),
                        stringResource(R.string.details_location_permission),
                    )
                }
            }
            exif is ExifLoadResult.NotAnImage -> Text(stringResource(R.string.details_metadata_not_image))
            exif is ExifLoadResult.CorruptOrUnsupported -> Text(stringResource(R.string.details_metadata_unavailable))
            exif is ExifLoadResult.MediaUnavailable -> Text(stringResource(R.string.details_media_unavailable))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.labelLarge)
        Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}
