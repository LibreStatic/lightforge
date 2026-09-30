package com.librestatic.lightforge

/** Why a collage could not be started. Each reason maps to its own user-facing explanation. */
internal enum class CollageRejection {
    NothingSelected,
    NotEnoughSelection,
    TooMany,
    NonImageSelected,
    DraftPending,
    SourceUnavailable,
}

/** Outcome of asking for a collage. */
internal sealed interface CollagePreparation {
    data object Ready : CollagePreparation

    data class Rejected(val reason: CollageRejection, val selectedCount: Int = 0) : CollagePreparation
}

/**
 * A collage rejection rendered as a string resource plus the optional count argument it formats.
 * Kept free of Android resource lookups so the mapping itself is unit-testable.
 */
internal data class CollageRejectionMessage(val stringRes: Int, val formatArg: Int? = null)

internal fun collageRejectionMessage(
    reason: CollageRejection,
    selectedCount: Int,
): CollageRejectionMessage = when (reason) {
    CollageRejection.NothingSelected ->
        CollageRejectionMessage(com.librestatic.lightforge.feature.collage.R.string.creation_collage_no_selection)
    CollageRejection.NotEnoughSelection ->
        CollageRejectionMessage(R.string.creation_collage_needs_two_images, selectedCount)
    CollageRejection.TooMany ->
        CollageRejectionMessage(R.string.creation_collage_too_many, selectedCount)
    CollageRejection.NonImageSelected ->
        CollageRejectionMessage(R.string.creation_collage_images_only)
    CollageRejection.DraftPending ->
        CollageRejectionMessage(R.string.creation_collage_draft_pending)
    CollageRejection.SourceUnavailable ->
        CollageRejectionMessage(R.string.creation_collage_sources_missing)
}
