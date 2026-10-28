package com.librestatic.lightforge.feature.photos

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryLoadingIndicator
import com.librestatic.lightforge.core.designsystem.GalleryProgressSlot
import com.librestatic.lightforge.core.designsystem.LocalGallerySuccessColors
import com.librestatic.lightforge.core.model.AnalysisProgress
import com.librestatic.lightforge.core.model.AnalysisEta
import java.text.NumberFormat
import kotlinx.coroutines.delay

/** A visible, operation-level status; thumbnails and animation frames are not live regions. */
/** Work that keeps running once the library is browsable, most relevant first. */
enum class LibraryBackgroundWork { SearchIndex, Moments, Faces, People, Pets, PetIdentity, MediaDetails }

/** An external condition holding analysis back; user-initiated pauses are not reported here. */
enum class LibraryBackgroundWait { Battery, Heat }

/** [waitingOn] is null while [work] runs and set while it is held back by the device; [progress] is set for analysis work. */
data class LibraryBackgroundStatus(
    val work: LibraryBackgroundWork,
    val waitingOn: LibraryBackgroundWait? = null,
    val progress: AnalysisProgress? = null,
)

/** Short passes (an unchanged Moments check) finish before this and never flash a hint. */
private const val BackgroundHintDelayMillis = 1_500L

/** A cold start over an already-indexed library, or a tiny rescan, ends before this and stays silent. */
internal const val LibraryPreparingGraceMillis = 700L

/** Every state of the card is at least this tall, so crossfading between them never moves the content below. */
private val CardMinHeight = 72.dp

/** What the one status slot shows; the slot crossfades between them without animating its size. */
private enum class StatusSlot { None, Preparing, Alert, Ready, SoftBusy, SoftIdle }

/** The part of the slot's lifecycle driven by [LibraryUiState]; [SoftBusy] and [SoftIdle] follow the background hint. */
private enum class StatusPhase { None, Preparing, Alert, Ready, Soft }

/** Coarse, human ETA: under a minute, whole minutes, then whole hours. */
internal sealed interface EtaBucket {
    data object UnderMinute : EtaBucket
    data class Minutes(val value: Int) : EtaBucket
    data class Hours(val value: Int) : EtaBucket
}

internal fun etaBucket(millis: Long): EtaBucket {
    if (millis < 60_000L) return EtaBucket.UnderMinute
    val rounded = AnalysisEta.roundCoarse(millis)
    return if (rounded < 3_600_000L) {
        EtaBucket.Minutes((rounded / 60_000L).toInt())
    } else {
        EtaBucket.Hours(((rounded + 1_800_000L) / 3_600_000L).toInt())
    }
}

@Composable
private fun etaText(millis: Long): String = when (val bucket = etaBucket(millis)) {
    EtaBucket.UnderMinute -> stringResource(R.string.library_eta_under_minute)
    is EtaBucket.Minutes -> pluralStringResource(R.plurals.library_eta_minutes, bucket.value, bucket.value)
    is EtaBucket.Hours -> pluralStringResource(R.plurals.library_eta_hours, bucket.value, bucket.value)
}

/** "4,210 of 13,600 · about 25 min", or null when the pipeline has no countable work. */
@Composable
private fun progressText(progress: AnalysisProgress?): String? {
    if (progress == null || progress.total <= 0) return null
    // Re-read when the locale changes; NumberFormat itself is cheap.
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val counts = stringResource(R.string.library_progress_of, numbers.format(progress.processed), numbers.format(progress.total))
    val eta = progress.etaMillis?.takeIf { progress.running && progress.waitReason == null && !progress.complete }
    return if (eta != null) stringResource(R.string.library_detail_join, counts, etaText(eta)) else counts
}

private fun taskNameRes(work: LibraryBackgroundWork): Int? = when (work) {
    LibraryBackgroundWork.Faces -> R.string.library_task_faces
    LibraryBackgroundWork.People -> R.string.library_task_people
    LibraryBackgroundWork.Pets -> R.string.library_task_pets
    LibraryBackgroundWork.PetIdentity -> R.string.library_task_pet_identity
    LibraryBackgroundWork.SearchIndex, LibraryBackgroundWork.Moments, LibraryBackgroundWork.MediaDetails -> null
}

/**
 * The library status, always in one slot. While preparing it is the neutral progress card; after a
 * shown preparation it is a success "Library ready" card until "Got it"; from then on, and on a cold
 * start over a ready library, it is a quiet information card that cannot be dismissed. States
 * crossfade without a size animation, and each is at least [CardMinHeight] tall, so nothing below
 * ever shifts. Hiding the card altogether is the caller's decision (the "show status" setting).
 */
@Composable
internal fun LibraryIndexStatus(
    state: LibraryUiState,
    modifier: Modifier = Modifier,
    background: LibraryBackgroundStatus? = null,
    /** Total items shown on the idle card; null leaves the count out. */
    libraryItemCount: Long? = null,
) {
    val hint by produceState<LibraryBackgroundStatus?>(null, background) {
        if (background != null && value == null) delay(BackgroundHintDelayMillis)
        value = background
    }
    val preparing = state == LibraryUiState.Starting || state == LibraryUiState.Indexing
    // A shown non-ready phase stays up as "Library ready" until the person acknowledges it: removing the
    // card on a timer would move the timeline under their finger. Saveable so recreation keeps the flag.
    var awaitingReady by rememberSaveable { mutableStateOf(false) }
    var phase by remember {
        mutableStateOf(
            when (state) {
                LibraryUiState.Starting, LibraryUiState.Indexing ->
                    if (awaitingReady) StatusPhase.Preparing else StatusPhase.None
                LibraryUiState.Error, LibraryUiState.PermissionRequired -> StatusPhase.Alert
                LibraryUiState.Ready -> if (awaitingReady) StatusPhase.Ready else StatusPhase.Soft
            },
        )
    }
    LaunchedEffect(state) {
        when (state) {
            LibraryUiState.Starting, LibraryUiState.Indexing -> {
                // Within the grace period the previous card (or the empty slot) stays as it was.
                if (phase == StatusPhase.None || phase == StatusPhase.Soft) delay(LibraryPreparingGraceMillis)
                phase = StatusPhase.Preparing
                awaitingReady = true
            }
            LibraryUiState.Error, LibraryUiState.PermissionRequired -> {
                phase = StatusPhase.Alert
                awaitingReady = true
            }
            // Opening an already-ready library, or one whose preparation was never shown, is quiet.
            LibraryUiState.Ready -> phase = if (awaitingReady) StatusPhase.Ready else StatusPhase.Soft
        }
    }
    val slot = when (phase) {
        StatusPhase.None -> StatusSlot.None
        StatusPhase.Preparing -> StatusSlot.Preparing
        StatusPhase.Alert -> StatusSlot.Alert
        StatusPhase.Ready -> StatusSlot.Ready
        StatusPhase.Soft -> if (hint != null) StatusSlot.SoftBusy else StatusSlot.SoftIdle
    }
    // The last hint outlives the hint itself so its card can fade out with its text.
    var lastHint by remember { mutableStateOf(hint) }
    if (hint != null) lastHint = hint
    AnimatedContent(
        targetState = slot,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            (fadeIn(tween(250, delayMillis = 60)) togetherWith fadeOut(tween(200)))
                .using(SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "library-status-slot",
    ) { target ->
        when (target) {
            StatusSlot.None -> Spacer(Modifier.fillMaxWidth().heightIn(min = CardMinHeight))
            StatusSlot.Preparing, StatusSlot.Alert -> PreparingCard(
                title = when (state) {
                    LibraryUiState.Error -> R.string.library_error_title
                    LibraryUiState.PermissionRequired -> R.string.permission_title
                    else -> R.string.library_loading_title
                },
                showProgress = preparing,
            )
            StatusSlot.Ready -> ReadyCard(
                busy = lastHint.takeIf { hint != null },
                onDismiss = {
                    awaitingReady = false
                    phase = StatusPhase.Soft
                },
            )
            StatusSlot.SoftBusy -> lastHint?.let { SoftBusyCard(it) }
            StatusSlot.SoftIdle -> SoftIdleCard(libraryItemCount)
        }
    }
}

@Composable
private fun PreparingCard(title: Int, showProgress: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = CardMinHeight),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                    .testTag("library-index-status")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            // The slot keeps its height in every state and the bar only fades. The bar sits at the top of the
            // slot, so the top padding keeps the wave's crests clear of the text.
            GalleryProgressSlot(
                visible = true,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            ) {
                AnimatedVisibility(
                    visible = showProgress,
                    enter = fadeIn(),
                    exit = fadeOut(tween(durationMillis = 400)),
                ) {
                    GalleryIndeterminateProgressIndicator(Modifier.testTag("library-index-progress"))
                }
            }
        }
    }
}

@Composable
private fun ReadyCard(busy: LibraryBackgroundStatus?, onDismiss: () -> Unit) {
    val success = LocalGallerySuccessColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = CardMinHeight),
        color = success.container,
        contentColor = success.onContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp)) {
                Text(
                    text = stringResource(R.string.library_ready_status),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("library-index-status")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.padding(top = 4.dp))
                if (busy == null) {
                    Text(
                        text = stringResource(R.string.library_ready_detail),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("library-ready-detail"),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (busy.isRunning()) {
                            GalleryLoadingIndicator(Modifier.size(20.dp), color = LocalContentColor.current)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = busyLine(busy),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("library-background-status"),
                        )
                    }
                }
            }
            // Beside the two lines, so the card keeps its height; the success pair gives the button its colour.
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                modifier = Modifier.padding(end = 4.dp).testTag("library-ready-dismiss"),
            ) {
                Text(stringResource(R.string.library_ready_dismiss))
            }
        }
    }
}

/** The quiet card: never dismissable, no live region, neutral container. */
@Composable
private fun SoftCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = CardMinHeight),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun SoftBusyCard(status: LibraryBackgroundStatus) {
    val taskRes = taskNameRes(status.work)
    // Two lines at most, like the ready card, so the header never grows: a held-back task says why in the detail.
    val progress = progressText(status.progress)
    val detail = status.waitingOn?.takeIf { taskRes != null }?.let { wait ->
        val reason = shortWaitText(wait)
        if (progress != null) stringResource(R.string.library_detail_join, reason, progress) else reason
    } ?: progress
    SoftCard {
        if (status.isRunning()) {
            GalleryLoadingIndicator(Modifier.size(28.dp), color = LocalContentColor.current)
        } else {
            Icon(GalleryIcons.Info, contentDescription = null, Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.testTag("library-soft-status")) {
            // Analysis names its task and counts; maintenance and held-back work say it in one sentence.
            Text(
                text = if (taskRes != null) stringResource(taskRes) else backgroundHint(status),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SoftIdleCard(itemCount: Long?) {
    SoftCard {
        Icon(GalleryIcons.CheckCircle, contentDescription = null, Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        val locale = LocalConfiguration.current.locales[0]
        val text = if (itemCount != null && itemCount > 0) {
            val formatted = remember(locale, itemCount) { NumberFormat.getIntegerInstance(locale).format(itemCount) }
            pluralStringResource(R.plurals.library_up_to_date_items, itemCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), formatted)
        } else {
            stringResource(R.string.library_up_to_date)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("library-soft-status"),
        )
    }
}

/** True while the work is actually progressing, as opposed to held back by battery or heat. */
private fun LibraryBackgroundStatus.isRunning(): Boolean =
    waitingOn == null && progress?.waitReason == null

/** The single labelSmall line of the ready card: "Face detection · 4,210 of 13,600 · about 25 min". */
@Composable
private fun busyLine(status: LibraryBackgroundStatus): String {
    val taskRes = taskNameRes(status.work)
    val detail = progressText(status.progress)
    if (taskRes == null) return backgroundHint(status)
    val wait = status.waitingOn
    if (wait != null) return stringResource(R.string.library_detail_join, stringResource(taskRes), shortWaitText(wait))
    if (detail == null) return backgroundHint(status)
    return stringResource(R.string.library_detail_join, stringResource(taskRes), detail)
}

@Composable
private fun shortWaitText(wait: LibraryBackgroundWait): String = stringResource(
    when (wait) {
        LibraryBackgroundWait.Battery -> R.string.library_wait_battery_short
        LibraryBackgroundWait.Heat -> R.string.library_wait_heat_short
    },
)

@Composable
private fun backgroundHint(status: LibraryBackgroundStatus): String {
    // Only analysis can be held back by the device; library maintenance always reads as running.
    val pausedTask = when (status.work) {
        LibraryBackgroundWork.Faces -> R.string.library_task_faces
        LibraryBackgroundWork.People -> R.string.library_task_people
        LibraryBackgroundWork.Pets -> R.string.library_task_pets
        LibraryBackgroundWork.PetIdentity -> R.string.library_task_pet_identity
        LibraryBackgroundWork.SearchIndex, LibraryBackgroundWork.Moments, LibraryBackgroundWork.MediaDetails -> null
    }
    val wait = status.waitingOn
    if (wait != null && pausedTask != null) {
        return stringResource(
            when (wait) {
                LibraryBackgroundWait.Battery -> R.string.library_paused_battery
                LibraryBackgroundWait.Heat -> R.string.library_paused_heat
            },
            stringResource(pausedTask),
        )
    }
    return stringResource(
        when (status.work) {
            LibraryBackgroundWork.SearchIndex -> R.string.library_background_search
            LibraryBackgroundWork.Moments -> R.string.library_background_moments
            LibraryBackgroundWork.Faces -> R.string.library_background_faces
            LibraryBackgroundWork.People -> R.string.library_background_people
            LibraryBackgroundWork.Pets -> R.string.library_background_pets
            LibraryBackgroundWork.PetIdentity -> R.string.library_background_pet_identity
            LibraryBackgroundWork.MediaDetails -> R.string.library_background_media_details
        },
    )
}
