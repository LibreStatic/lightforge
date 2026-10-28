package com.librestatic.lightforge.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator
import com.librestatic.lightforge.core.model.AnalysisEta
import com.librestatic.lightforge.core.model.AnalysisProgress
import com.librestatic.lightforge.core.model.AnalysisWait
import java.text.NumberFormat

/** What the "Individual pets" row on the Local analysis page needs; [progress] is null while off or without a model. */
data class PetIdentitySettingsUi(
    val enabled: Boolean = false,
    val identityCount: Long = 0,
    val suggestedGroups: Int = 0,
    val photosWithPets: Long = 0,
    val progress: AnalysisProgress? = null,
)

/** Coarse ETA bucket: under a minute, whole minutes below an hour, then whole hours. */
internal sealed interface SettingsEta {
    data object UnderMinute : SettingsEta
    data class Minutes(val value: Int) : SettingsEta
    data class Hours(val value: Int) : SettingsEta
}

internal fun settingsEta(millis: Long): SettingsEta {
    if (millis < 60_000L) return SettingsEta.UnderMinute
    val rounded = AnalysisEta.roundCoarse(millis)
    return if (rounded < 3_600_000L) SettingsEta.Minutes((rounded / 60_000L).toInt())
    else SettingsEta.Hours(((rounded + 1_800_000L) / 3_600_000L).toInt())
}

@Composable
private fun etaText(millis: Long): String = when (val eta = settingsEta(millis)) {
    SettingsEta.UnderMinute -> stringResource(R.string.analysis_eta_under_minute)
    is SettingsEta.Minutes -> pluralStringResource(R.plurals.analysis_eta_minutes, eta.value, eta.value)
    is SettingsEta.Hours -> pluralStringResource(R.plurals.analysis_eta_hours, eta.value, eta.value)
}

@Composable
internal fun rememberIntegerFormat(): NumberFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { NumberFormat.getIntegerInstance(locale) }
}

/** "4,210 of 13,600 photos · 9,390 left · about 25 min", or the completion / wait line. */
@Composable
internal fun analysisProgressSummary(progress: AnalysisProgress, paused: Boolean): String {
    val format = rememberIntegerFormat()
    if (progress.complete) {
        return pluralStringResource(R.plurals.analysis_progress_complete, progress.total.toLimitedInt(), format.format(progress.total))
    }
    val parts = buildList {
        if (paused) add(stringResource(R.string.analysis_progress_paused))
        add(
            pluralStringResource(
                R.plurals.analysis_progress_counts,
                progress.total.toLimitedInt(),
                format.format(progress.processed),
                format.format(progress.total),
            ),
        )
        add(pluralStringResource(R.plurals.analysis_progress_left, progress.remaining.toLimitedInt(), format.format(progress.remaining)))
        when {
            paused -> Unit
            progress.waitReason == AnalysisWait.Battery -> add(stringResource(R.string.analysis_wait_battery))
            progress.waitReason == AnalysisWait.Heat -> add(stringResource(R.string.analysis_wait_heat))
            progress.etaMillis != null -> add(etaText(progress.etaMillis!!))
        }
    }
    return parts.joinToString(" · ")
}

private fun Long.toLimitedInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/**
 * Determinate bar plus the counts line for one pipeline. Without countable work yet it shows an
 * indeterminate bar (while [preparing]) and a preparing line instead of an empty bar.
 */
@Composable
internal fun AnalysisProgressBlock(
    progress: AnalysisProgress?,
    paused: Boolean,
    preparing: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progress == null || progress.total <= 0) {
            if (preparing && !paused) GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth().testTag("${tag}_indicator"))
            Text(
                stringResource(if (paused) R.string.analysis_progress_paused else R.string.analysis_progress_preparing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("${tag}_status"),
            )
        } else {
            if (!progress.complete) {
                GalleryProgressIndicator(
                    progress = { progress.processed.toFloat() / progress.total },
                    modifier = Modifier.testTag("${tag}_indicator"),
                )
            }
            Text(
                analysisProgressSummary(progress, paused),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("${tag}_status"),
            )
        }
    }
}

/** Supporting text of the "Individual pets" row. */
@Composable
internal fun petIdentitySummary(ui: PetIdentitySettingsUi): String {
    val format = rememberIntegerFormat()
    val progress = ui.progress
    return when {
        !ui.enabled -> stringResource(R.string.pet_identity_summary_not_set_up)
        progress != null && progress.running && progress.remaining > 0 -> pluralStringResource(
            R.plurals.pet_identity_summary_analyzing,
            progress.remaining.toLimitedInt(),
            format.format(progress.remaining),
        )
        else -> listOfNotNull(
            pluralStringResource(R.plurals.pet_identity_summary_pets, ui.identityCount.toLimitedInt(), format.format(ui.identityCount)),
            ui.suggestedGroups.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.pet_identity_summary_groups, it, format.format(it)) },
            ui.photosWithPets.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.pet_identity_summary_photos, it.toLimitedInt(), format.format(it)) },
        ).joinToString(" · ")
    }
}
