package com.librestatic.lightforge

import com.librestatic.lightforge.core.ml.MlBackoffWait
import com.librestatic.lightforge.core.ml.MlCheckpoint
import com.librestatic.lightforge.core.ml.MlControlState
import com.librestatic.lightforge.core.ml.MlTaskType
import com.librestatic.lightforge.feature.photos.LibraryBackgroundStatus
import com.librestatic.lightforge.feature.photos.LibraryBackgroundWait
import com.librestatic.lightforge.feature.photos.LibraryBackgroundWork

/**
 * The single background activity the Photos status names. Running work wins over work the
 * device holds back; user pauses and queued-but-idle work stay silent.
 */
internal fun libraryBackgroundStatus(
    maintenance: LibraryMaintenance?,
    people: MlControlState,
    pets: MlControlState,
): LibraryBackgroundStatus? {
    when (maintenance) {
        LibraryMaintenance.SearchIndex -> return LibraryBackgroundStatus(LibraryBackgroundWork.SearchIndex)
        LibraryMaintenance.Moments -> return LibraryBackgroundStatus(LibraryBackgroundWork.Moments)
        null -> Unit
    }
    val analyses = listOf(
        people to if (people.activeTask == MlTaskType.FaceDetection) LibraryBackgroundWork.Faces else LibraryBackgroundWork.People,
        pets to LibraryBackgroundWork.Pets,
    ).filter { (state, _) -> state.consentGranted && !state.paused }
    analyses.firstOrNull { (state, _) -> state.status == MlCheckpoint.Status.Running }
        ?.let { (_, work) -> return LibraryBackgroundStatus(work) }
    return analyses.firstNotNullOfOrNull { (state, work) ->
        val wait = state.waitReason?.takeIf { state.requested } ?: return@firstNotNullOfOrNull null
        LibraryBackgroundStatus(
            work,
            when (wait) {
                MlBackoffWait.Charging -> LibraryBackgroundWait.Battery
                MlBackoffWait.Thermal -> LibraryBackgroundWait.Heat
            },
        )
    }
}
