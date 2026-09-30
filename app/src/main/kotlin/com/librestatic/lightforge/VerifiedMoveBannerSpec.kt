package com.librestatic.lightforge

/** How the verified-move panel should read: a clean move is a success, not a warning. */
internal enum class VerifiedMoveTone { Success, Progress, Attention }

internal data class VerifiedMoveBannerSpec(
    val tone: VerifiedMoveTone,
    val showReview: Boolean,
    val showRetry: Boolean,
    val showAccess: Boolean,
    val showForget: Boolean,
    val showDone: Boolean,
)

/**
 * [VerifiedMovePhase.Completed] is only reached after the destination is verified and the original is
 * confirmed gone, so on its own it is a clean success and offers a single dismissal. A degraded
 * outcome (a failed attempt, or an unreadable journal record) still reads as attention and keeps the
 * recovery actions, even when the entry already completed.
 */
internal fun verifiedMoveBanner(
    phase: VerifiedMovePhase?,
    failed: Boolean,
    unreadable: Boolean,
): VerifiedMoveBannerSpec {
    val hasEntry = phase != null
    val completed = phase == VerifiedMovePhase.Completed
    val degraded = failed || unreadable
    return when {
        degraded -> VerifiedMoveBannerSpec(
            tone = VerifiedMoveTone.Attention,
            showReview = unreadable,
            showRetry = hasEntry && !completed,
            showAccess = hasEntry && !completed,
            showForget = hasEntry,
            showDone = false,
        )
        completed -> VerifiedMoveBannerSpec(
            tone = VerifiedMoveTone.Success,
            showReview = false,
            showRetry = false,
            showAccess = false,
            showForget = false,
            showDone = true,
        )
        else -> VerifiedMoveBannerSpec(
            tone = VerifiedMoveTone.Progress,
            showReview = false,
            showRetry = hasEntry,
            showAccess = hasEntry,
            showForget = hasEntry,
            showDone = false,
        )
    }
}
