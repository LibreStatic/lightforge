@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.librestatic.lightforge.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.preferences.*
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator

/**
 * Explicit independent settings operation: no media restore, worker, or permission side effects.
 */
@Composable
fun PortablePreferencesContent(
    port: PortablePreferencesPort,
    bytes: ByteArray,
    operationId: String,
    onBack: () -> Unit,
    onApplied: (PortablePreferencesResult) -> Unit = {},
) {
    val digest =
        remember(bytes) {
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        }
    var selectedMask by rememberSaveable(operationId, digest) { mutableIntStateOf(0) }
    var expandedMask by rememberSaveable(operationId, digest) { mutableIntStateOf(0) }
    var confirmation by rememberSaveable(operationId, digest) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var review by
        remember(port, operationId, digest) { mutableStateOf<PortablePreferencesReview?>(null) }
    var failure by
        remember(port, operationId, digest) { mutableStateOf<PortablePreferencesFailure?>(null) }
    var loading by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var result by remember(operationId, digest) { mutableStateOf<PortablePreferencesResult?>(null) }
    val scope = rememberCoroutineScope()
    fun selected() =
        PortablePreferenceGroup.entries.filter { selectedMask and (1 shl it.ordinal) != 0 }.toSet()
    LaunchedEffect(port, operationId, digest, reload) {
        loading = true
        failure = null
        review = null
        try {
            review = port.review(bytes, operationId)
            if (review?.alreadyApplied == true) confirmation = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure =
                (error as? PortablePreferencesException)?.reason
                    ?: PortablePreferencesFailure.Storage
        } finally {
            loading = false
        }
    }
    BackHandler { if (!applying) onBack() }
    Surface(
        Modifier.fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag("portable-preferences-screen")
    ) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .widthIn(max = 840.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.portable_preferences_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(stringResource(R.string.portable_preferences_body))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    stringResource(R.string.portable_preferences_preserved),
                    Modifier.padding(16.dp),
                )
            }
            if (loading || applying)
                GalleryIndeterminateProgressIndicator(
                    Modifier.fillMaxWidth().testTag("portable-preferences-progress")
                )
            val current = review
            when {
                result != null || current?.alreadyApplied == true -> {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            stringResource(
                                if (result?.alreadyApplied == false)
                                    R.string.portable_preferences_applied
                                else R.string.portable_preferences_replayed
                            ),
                            Modifier.padding(16.dp).testTag("portable-preferences-result"),
                        )
                    }
                }
                current != null -> {
                    if (current.availableGroups.isEmpty())
                        Text(
                            stringResource(R.string.portable_preferences_empty),
                            Modifier.testTag("portable-preferences-empty"),
                        )
                    PortablePreferenceGroup.entries
                        .filter { it in current.availableGroups }
                        .forEach { group ->
                            val groupTitle = stringResource(groupLabel(group))
                            val bit = 1 shl group.ordinal
                            val differences = current.differences.filter { it.field.group == group }
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                stringResource(groupLabel(group)),
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            Text(
                                                differences.count { it.changed }.let { changed ->
                                                    stringResource(
                                                        R.string.portable_preferences_changes,
                                                        pluralStringResource(R.plurals.portable_preferences_changed, changed, changed),
                                                        pluralStringResource(R.plurals.portable_preferences_fields, differences.size, differences.size),
                                                    )
                                                }
                                            )
                                        }
                                        Checkbox(
                                            checked = selectedMask and bit != 0,
                                            onCheckedChange = { checked ->
                                                selectedMask =
                                                    if (checked) selectedMask or bit
                                                    else selectedMask and bit.inv()
                                            },
                                            enabled = !applying && failure == null,
                                            modifier =
                                                Modifier.semantics {
                                                        contentDescription = groupTitle
                                                    }
                                                    .testTag(
                                                        "portable-preferences-select-${group.name}"
                                                    ),
                                        )
                                    }
                                    TextButton(
                                        onClick = { expandedMask = expandedMask xor bit },
                                        modifier =
                                            Modifier.testTag(
                                                "portable-preferences-details-${group.name}"
                                            ),
                                    ) {
                                        Text(
                                            stringResource(
                                                if (expandedMask and bit != 0)
                                                    R.string.portable_preferences_hide
                                                else R.string.portable_preferences_details
                                            )
                                        )
                                    }
                                    if (expandedMask and bit != 0)
                                        differences.forEach { difference ->
                                            Text(
                                                stringResource(fieldLabel(difference.field)),
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                            Text(
                                                stringResource(
                                                    R.string.portable_preferences_difference,
                                                    displayValue(difference.currentValue),
                                                    displayValue(difference.importedValue),
                                                )
                                            )
                                        }
                                }
                            }
                        }
                    Button(
                        onClick = { confirmation = true },
                        enabled = !applying && selected().isNotEmpty() && failure == null,
                        modifier = Modifier.testTag("portable-preferences-review-apply"),
                    ) {
                        Text(stringResource(R.string.portable_preferences_apply))
                    }
                }
            }
            failure?.let { reason ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        stringResource(errorLabel(reason)),
                        Modifier.padding(16.dp).testTag("portable-preferences-error"),
                    )
                }
                if (
                    reason == PortablePreferencesFailure.Conflict ||
                        reason == PortablePreferencesFailure.Storage
                )
                    OutlinedButton(
                        onClick = {
                            selectedMask = 0
                            confirmation = false
                            reload++
                        },
                        enabled = !applying,
                        modifier = Modifier.testTag("portable-preferences-reload"),
                    ) {
                        Text(stringResource(R.string.portable_preferences_reload))
                    }
            }
            OutlinedButton(
                onClick = onBack,
                enabled = !applying,
                modifier = Modifier.testTag("portable-preferences-back"),
            ) {
                Text(stringResource(R.string.portable_preferences_back))
            }
        }
    }
    if (
        confirmation &&
            review != null &&
            failure == null &&
            result == null &&
            review?.alreadyApplied != true
    ) {
        AlertDialog(
            onDismissRequest = { if (!applying) confirmation = false },
            modifier =
                Modifier.semantics { testTagsAsResourceId = true }
                    .testTag("portable-preferences-confirmation"),
            title = { Text(stringResource(R.string.portable_preferences_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.portable_preferences_confirm_body))
                    selected().forEach { Text(stringResource(groupLabel(it))) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !applying,
                    modifier = Modifier.testTag("portable-preferences-confirm"),
                    onClick = {
                        val checkedReview = review ?: return@TextButton
                        val checkedSelection = selected()
                        applying = true
                        scope.launch {
                            try {
                                result = port.apply(bytes, checkedReview, checkedSelection)
                                confirmation = false
                                onApplied(checkNotNull(result))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                failure =
                                    (error as? PortablePreferencesException)?.reason
                                        ?: PortablePreferencesFailure.Storage
                                confirmation = false
                            } finally {
                                applying = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.portable_preferences_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = false }, enabled = !applying) {
                    Text(stringResource(R.string.portable_preferences_cancel))
                }
            },
        )
    }
}

private fun groupLabel(group: PortablePreferenceGroup) =
    when (group) {
        PortablePreferenceGroup.Presentation -> R.string.portable_preferences_presentation
        PortablePreferenceGroup.Playback -> R.string.portable_preferences_playback
        PortablePreferenceGroup.Gestures -> R.string.portable_preferences_gestures
    }

private fun errorLabel(reason: PortablePreferencesFailure) =
    when (reason) {
        PortablePreferencesFailure.Conflict -> R.string.portable_preferences_conflict
        PortablePreferencesFailure.OperationMismatch -> R.string.portable_preferences_mismatch
        PortablePreferencesFailure.ReceiptLimit -> R.string.portable_preferences_limit
        PortablePreferencesFailure.InvalidPayload -> R.string.portable_preferences_invalid
        PortablePreferencesFailure.Storage -> R.string.portable_preferences_storage
    }

@Composable
private fun displayValue(value: String): String =
    when (value) {
        "true" -> stringResource(R.string.portable_preferences_on)
        "false" -> stringResource(R.string.portable_preferences_off)
        else -> valueLabel(value)?.let { stringResource(it) } ?: value
    }

private fun fieldLabel(field: PortablePreferenceField): Int =
    when (field) {
        PortablePreferenceField.CollectionOrder -> R.string.portable_preferences_field_collection_order
        PortablePreferenceField.HiddenCollections -> R.string.portable_preferences_field_hidden_collections
        PortablePreferenceField.Sort -> R.string.portable_preferences_field_sort
        PortablePreferenceField.Ascending -> R.string.portable_preferences_field_ascending
        PortablePreferenceField.Filter -> R.string.portable_preferences_field_filter
        PortablePreferenceField.Grouping -> R.string.portable_preferences_field_grouping
        PortablePreferenceField.Crop -> R.string.portable_preferences_field_crop
        PortablePreferenceField.Animate -> R.string.portable_preferences_field_animate
        PortablePreferenceField.Duration -> R.string.portable_preferences_field_duration
        PortablePreferenceField.FileType -> R.string.portable_preferences_field_filetype
        PortablePreferenceField.Favorites -> R.string.portable_preferences_field_favorites
        PortablePreferenceField.Columns -> R.string.portable_preferences_field_columns
        PortablePreferenceField.Autoplay -> R.string.portable_preferences_field_autoplay
        PortablePreferenceField.Muted -> R.string.portable_preferences_field_muted
        PortablePreferenceField.Loop -> R.string.portable_preferences_field_loop
        PortablePreferenceField.RememberPosition ->
            R.string.portable_preferences_field_rememberposition
        PortablePreferenceField.MaximumBrightness ->
            R.string.portable_preferences_field_maximumbrightness
        PortablePreferenceField.Scrubbing -> R.string.portable_preferences_field_scrubbing
        PortablePreferenceField.DoubleTap -> R.string.portable_preferences_field_doubletap
        PortablePreferenceField.Pinch -> R.string.portable_preferences_field_pinch
        PortablePreferenceField.SwipeDown -> R.string.portable_preferences_field_swipedown
        PortablePreferenceField.PhotoBrightness ->
            R.string.portable_preferences_field_photobrightness
        PortablePreferenceField.VideoBrightness ->
            R.string.portable_preferences_field_videobrightness
        PortablePreferenceField.VideoVolume -> R.string.portable_preferences_field_videovolume
        PortablePreferenceField.VideoSeek -> R.string.portable_preferences_field_videoseek
        PortablePreferenceField.Rotate -> R.string.portable_preferences_field_rotate
        PortablePreferenceField.PhotoZoom -> R.string.portable_preferences_field_photozoom
        PortablePreferenceField.VideoZoom -> R.string.portable_preferences_field_videozoom
        PortablePreferenceField.SkipSeconds -> R.string.portable_preferences_field_skipseconds
        PortablePreferenceField.SwipeUp -> R.string.portable_preferences_field_swipeup
    }

private fun valueLabel(value: String): Int? =
    when (value) {
        "DateTaken" -> R.string.portable_preferences_value_datetaken
        "DateModified" -> R.string.portable_preferences_value_datemodified
        "Name" -> R.string.portable_preferences_value_name
        "Size" -> R.string.portable_preferences_value_size
        "All" -> R.string.portable_preferences_value_all
        "Images" -> R.string.portable_preferences_value_images
        "Videos" -> R.string.portable_preferences_value_videos
        "Animated" -> R.string.portable_preferences_value_animated
        "Raw" -> R.string.portable_preferences_value_raw
        "Day" -> R.string.portable_preferences_value_day
        "Month" -> R.string.portable_preferences_value_month
        "Year" -> R.string.portable_preferences_value_year
        "None" -> R.string.portable_preferences_value_none
        "LegacySeekBar" -> R.string.portable_preferences_value_legacyseekbar
        "Filmstrip" -> R.string.portable_preferences_value_filmstrip
        else -> null
    }
