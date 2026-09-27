package com.librestatic.lightforge.feature.collage

import android.content.Context
import android.content.Intent

/** Production variants do not install an acceptance pause. */
object CreationGifCommitProbe {
    @Suppress("UNUSED_PARAMETER")
    fun afterHandoff(context: Context, sessionId: String, target: Intent, chooser: Intent) = Unit
    @Suppress("UNUSED_PARAMETER")
    suspend fun afterCommit(context: Context, receipt: CreationGifPublicationReceipt) = Unit
}
