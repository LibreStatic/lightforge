package com.ugallery.app

import com.ugallery.core.model.MediaKey

/**
 * The library row a verified move retires. [VerifiedMovePhase.Completed] is only reached after the
 * original is confirmed gone from MediaStore, so the index still holding that row is what leaves
 * the timeline - and the viewer pager reading it - reporting a stale item count.
 */
internal fun retiredMoveRow(entry: VerifiedMoveEntry?): MediaKey? =
    entry?.takeIf { it.phase == VerifiedMovePhase.Completed }
        ?.let { MediaKey(it.proof.targetVolume, it.proof.targetId) }
