package com.ugallery.app

import android.app.Application

/** Production/benchmark builds never enable the acceptance commit-pause fixture. */
object ManualMomentCommitProbe {
    @Suppress("UNUSED_PARAMETER")
    suspend fun afterCommit(application: Application, request: ManualMomentCreateRequest) = Unit
}
