package com.librestatic.lightforge

import android.content.Context
import android.net.Uri
import android.util.Log
import com.librestatic.lightforge.core.mediastore.LocalShareSanitizer
import com.librestatic.lightforge.core.mediastore.MediaActionTarget
import com.librestatic.lightforge.core.mediastore.PreparedShareAsset
import com.librestatic.lightforge.core.mediastore.ShareCandidate
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.RawDevelopmentSettings
import com.librestatic.lightforge.core.model.RawOutputFormat
import com.librestatic.lightforge.core.raw.DngByteSource
import com.librestatic.lightforge.core.raw.DngCameraFraming
import com.librestatic.lightforge.core.raw.RawDeveloper
import com.librestatic.lightforge.core.raw.RawExportFailureKind
import com.librestatic.lightforge.core.raw.RawExportOutcome
import com.librestatic.lightforge.core.raw.isRawMimeOrName
import com.librestatic.lightforge.core.raw.parseDngCameraFraming
import com.librestatic.lightforge.feature.details.VirtualCropInfo
import com.librestatic.lightforge.feature.privatealbum.PrivateVirtualCropWarning
import java.io.File
import java.io.IOException

/** What the share flow knows about one target before deciding whether it needs the virtual crop warning. */
internal data class RawShareProbe(val target: MediaActionTarget, val displayName: String?, val mimeType: String?)

internal data class VirtualCropShareItem(val target: MediaActionTarget, val framing: DngCameraFraming)

/** A share of originals waiting for the user's answer to the virtual crop dialog. */
internal data class VirtualCropSharePrompt(
    val candidates: List<ShareCandidate>,
    val crops: List<VirtualCropShareItem>,
    val working: Boolean = false,
)

internal fun VirtualCropShareItem.info() = VirtualCropInfo(
    fullWidth = framing.fullWidth,
    fullHeight = framing.fullHeight,
    cropWidth = framing.cropWidth,
    cropHeight = framing.cropHeight,
)

/**
 * The targets that are RAW photos whose DNG framing hides part of the sensor image. Only RAW
 * names or types are inspected, so a share of ordinary photos never opens a file.
 */
internal suspend fun virtualCropsAmong(
    probes: List<RawShareProbe>,
    framingOf: suspend (MediaActionTarget) -> DngCameraFraming?,
): List<VirtualCropShareItem> = probes
    .filter { it.target.kind == MediaKind.Image && isRawMimeOrName(it.mimeType, it.displayName) }
    .distinctBy { it.target.key }
    .mapNotNull { probe ->
        framingOf(probe.target)?.takeIf(DngCameraFraming::isMeaningful)?.let { VirtualCropShareItem(probe.target, it) }
    }

/**
 * The private album's portable export keeps full originals, so it warns about virtual crops too.
 * Items are read in memory through the album's decrypting reader; a non-DNG RAW has no DefaultCrop.
 */
internal fun privateVirtualCropWarning(dismissed: Boolean, onDismiss: () -> Unit) = PrivateVirtualCropWarning(
    dismissed = dismissed,
    onDismiss = onDismiss,
    isRaw = { displayName, mimeType -> isRawMimeOrName(mimeType, displayName) },
    hidesSensorArea = { reader ->
        parseDngCameraFraming(object : DngByteSource {
            override val size: Long get() = reader.size
            override fun read(offset: Long, length: Int): ByteArray? = reader.read(offset, length)
        })?.isMeaningful == true
    },
)

/** One asset per non-private candidate, in order: the [cropped] copy where there is one, else [original]. */
internal fun <T> assembleCroppedShare(
    candidates: List<ShareCandidate>,
    cropped: Map<MediaKey, T>,
    original: (ShareCandidate) -> T,
): List<T> = candidates
    .filterNot(ShareCandidate::isPrivate)
    .distinctBy { it.target.key }
    .map { cropped[it.target.key] ?: original(it) }

/** A failed cropped-JPEG develop; [kind] lets the UI explain a memory shortfall specifically. */
internal class CroppedRawJpegException(
    val kind: RawExportFailureKind,
    reason: String,
) : IOException(reason)

/** The localized message for a failed cropped-JPEG share. */
internal fun Context.croppedShareFailureMessage(failure: Throwable): String {
    Log.w("CroppedRawJpeg", "Cropped RAW JPEG share failed", failure)
    return if ((failure as? CroppedRawJpegException)?.kind == RawExportFailureKind.TooLargeForJpeg) {
        getString(R.string.share_cropped_too_large)
    } else {
        getString(R.string.share_cropped_failed)
    }
}

/** Develops a RAW with the default recipe into a metadata-free JPEG in the share cache. */
internal class CroppedRawJpegExporter(private val context: Context) {
    private val sanitizer = LocalShareSanitizer(context)

    suspend fun develop(source: Uri): PreparedShareAsset {
        val destination = sanitizer.allocateShareFile("jpg")
        val outcome = RawDeveloper(context.contentResolver, File(context.cacheDir, "raw-scratch"))
            .export(source, RawDevelopmentSettings(), RawOutputFormat.JpegSrgb, destination)
        return when (outcome) {
            is RawExportOutcome.Completed -> sanitizer.shareAsset(outcome.file, outcome.mimeType)
            is RawExportOutcome.Failure -> {
                destination.delete()
                throw CroppedRawJpegException(outcome.kind, outcome.reason)
            }
        }
    }
}
