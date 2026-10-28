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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryBackgroundStatusMappingTest {
    private val idle = MlControlState(consentGranted = true, paused = false, completedItems = 0, status = MlCheckpoint.Status.Complete)

    private fun running(task: MlTaskType) = idle.copy(status = MlCheckpoint.Status.Running, requested = true, activeTask = task)

    private fun waiting(task: MlTaskType, wait: MlBackoffWait) =
        idle.copy(status = MlCheckpoint.Status.Ready, requested = true, activeTask = task, waitReason = wait)

    @Test
    fun runningAnalysisWinsOverMaintenance() {
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.Faces),
            libraryBackgroundStatus(LibraryMaintenance.Moments, running(MlTaskType.FaceDetection), idle),
        )
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.SearchIndex),
            libraryBackgroundStatus(LibraryMaintenance.SearchIndex, idle, idle),
        )
    }

    @Test
    fun maintenanceWinsOverHeldBackAnalysis() {
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.Moments),
            libraryBackgroundStatus(LibraryMaintenance.Moments, waiting(MlTaskType.FaceDetection, MlBackoffWait.Thermal), idle),
        )
    }

    @Test
    fun mediaDetailsIsTheQuietestHint() {
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.MediaDetails),
            libraryBackgroundStatus(LibraryMaintenance.MediaDetails, idle, idle),
        )
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.Faces),
            libraryBackgroundStatus(LibraryMaintenance.MediaDetails, running(MlTaskType.FaceDetection), idle),
        )
        assertEquals(null, libraryBackgroundStatus(null, idle, idle))
    }

    @Test
    fun faceDetectionAndLaterPeopleStagesAreNamedApart() {
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.Faces),
            libraryBackgroundStatus(null, running(MlTaskType.FaceDetection), idle),
        )
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.People),
            libraryBackgroundStatus(null, running(MlTaskType.PersonClustering), idle),
        )
    }

    @Test
    fun runningWorkWinsOverWorkHeldBackByTheDevice() {
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.Pets),
            libraryBackgroundStatus(null, waiting(MlTaskType.FaceDetection, MlBackoffWait.Thermal), running(MlTaskType.ImageLabels)),
        )
    }

    @Test
    fun externalWaitsNameTheirReason() {
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.Faces, LibraryBackgroundWait.Heat),
            libraryBackgroundStatus(null, waiting(MlTaskType.FaceDetection, MlBackoffWait.Thermal), idle),
        )
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.Pets, LibraryBackgroundWait.Battery),
            libraryBackgroundStatus(null, idle, waiting(MlTaskType.ImageLabels, MlBackoffWait.Charging)),
        )
    }

    @Test
    fun userPausesAndMissingConsentStaySilent() {
        assertNull(libraryBackgroundStatus(null, running(MlTaskType.FaceDetection).copy(paused = true), idle))
        assertNull(libraryBackgroundStatus(null, idle, running(MlTaskType.ImageLabels).copy(consentGranted = false)))
        assertNull(libraryBackgroundStatus(null, idle, idle))
    }

    private val faceProgress = AnalysisProgress(4_210, 13_600, 9_390, etaMillis = 1_500_000, running = true)

    @Test
    fun runningAnalysisCarriesItsOwnProgress() {
        val petProgress = AnalysisProgress(10, 20, 10, running = true)
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.Faces, progress = faceProgress),
            libraryBackgroundStatus(null, running(MlTaskType.FaceDetection), running(MlTaskType.ImageLabels), faceProgress, petProgress),
        )
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.Pets, progress = petProgress),
            libraryBackgroundStatus(null, idle, running(MlTaskType.ImageLabels), faceProgress, petProgress),
        )
    }

    @Test
    fun heldBackAnalysisKeepsItsProgress() {
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.Pets, LibraryBackgroundWait.Heat, faceProgress),
            libraryBackgroundStatus(null, idle, waiting(MlTaskType.ImageLabels, MlBackoffWait.Thermal), petProgress = faceProgress),
        )
    }

    @Test
    fun individualPetsAreNamedAfterSchedulerAnalysis() {
        val identity = AnalysisProgress(5, 50, 45, running = true)
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.PetIdentity, progress = identity),
            libraryBackgroundStatus(null, idle, idle, petIdentityProgress = identity),
        )
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.Faces, progress = faceProgress),
            libraryBackgroundStatus(null, running(MlTaskType.FaceDetection), idle, faceProgress, null, identity),
        )
        val held = identity.copy(waitReason = AnalysisWait.Battery)
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.PetIdentity, LibraryBackgroundWait.Battery, held),
            libraryBackgroundStatus(null, idle, idle, petIdentityProgress = held),
        )
        assertNull(libraryBackgroundStatus(null, idle, idle, petIdentityProgress = identity.copy(running = false)))
    }
}
