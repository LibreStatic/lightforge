package com.librestatic.lightforge.feature.motionphotos

import android.content.Context
import android.content.Intent

/** Production has no acceptance pause or observer side effects. */
@Suppress("UNUSED_PARAMETER")
object MotionPhotoCommitProbe {
    suspend fun afterCommit(context: Context, receipt: MotionPhotoPublicationReceipt) = Unit
    fun afterHandoff(context: Context, publicationId: String, target: Intent, chooser: Intent) = Unit
}
