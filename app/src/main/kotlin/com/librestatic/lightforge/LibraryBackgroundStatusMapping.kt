package com.librestatic.lightforge

import com.librestatic.lightforge.core.ml.MlBackoffWait
import com.librestatic.lightforge.core.ml.MlCheckpoint
import com.librestatic.lightforge.core.ml.MlControlState
import com.librestatic.lightforge.core.ml.MlTaskType
import com.librestatic.lightforge.core.model.AnalysisProgress
import com.librestatic.lightforge.core.model.AnalysisWait
import com.librestatic.lightforge.feature.photos.LibraryBackgroundStatus
import com.librestatic.lightforge.feature.photos.LibraryBackgroundWait
import com.librestatic.lightforge.feature.photos.LibraryBackgroundWork

/**
 * The single background activity the Photos status names. Running analysis wins, since it is the
 * long task whose progress and ETA the card carries; then index maintenance; then work the device
 * holds back. User pauses and queued-but-idle work stay silent.
 */
internal fun libraryBackgroundStatus(
    maintenance: LibraryMaintenance?,
    people: MlControlState,
    pets: MlControlState,
    peopleProgress: AnalysisProgress? = null,
    petProgress: AnalysisProgress? = null,
    petIdentityProgress: AnalysisProgress? = null,
): LibraryBackgroundStatus? {
    val analyses = listOf(
        people to if (people.activeTask == MlTaskType.FaceDetection) LibraryBackgroundWork.Faces else LibraryBackgroundWork.People,
        pets to LibraryBackgroundWork.Pets,
    ).filter { (state, _) -> state.consentGranted && !state.paused }
    fun progressOf(work: LibraryBackgroundWork) = when (work) {
        LibraryBackgroundWork.Faces -> peopleProgress
        LibraryBackgroundWork.Pets -> petProgress
        else -> null
    }
    analyses.firstOrNull { (state, _) -> state.status == MlCheckpoint.Status.Running }
        ?.let { (_, work) -> return LibraryBackgroundStatus(work, progress = progressOf(work)) }
    // Individual pets have no scheduler state: their progress alone says whether they run or are held back.
    petIdentityProgress?.takeIf { it.running && it.waitReason == null }
        ?.let { return LibraryBackgroundStatus(LibraryBackgroundWork.PetIdentity, progress = it) }
    when (maintenance) {
        LibraryMaintenance.SearchIndex -> return LibraryBackgroundStatus(LibraryBackgroundWork.SearchIndex)
        LibraryMaintenance.Moments -> return LibraryBackgroundStatus(LibraryBackgroundWork.Moments)
        LibraryMaintenance.MediaDetails, null -> Unit
    }
    // Reading file details is the quietest of the hints: analysis running or held back is named first.
    val waiting = analyses.firstNotNullOfOrNull { (state, work) ->
        val wait = state.waitReason?.takeIf { state.requested } ?: return@firstNotNullOfOrNull null
        LibraryBackgroundStatus(
            work,
            when (wait) {
                MlBackoffWait.Charging -> LibraryBackgroundWait.Battery
                MlBackoffWait.Thermal -> LibraryBackgroundWait.Heat
            },
            progressOf(work),
        )
    } ?: petIdentityProgress?.takeIf { it.running && it.waitReason != null }?.let {
        LibraryBackgroundStatus(
            LibraryBackgroundWork.PetIdentity,
            if (it.waitReason == AnalysisWait.Heat) LibraryBackgroundWait.Heat else LibraryBackgroundWait.Battery,
            it,
        )
    }
    return waiting ?: LibraryBackgroundStatus(LibraryBackgroundWork.MediaDetails)
        .takeIf { maintenance == LibraryMaintenance.MediaDetails }
}
