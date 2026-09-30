package com.librestatic.lightforge

import com.librestatic.lightforge.core.ml.MlBackoffWait
import com.librestatic.lightforge.core.ml.MlCheckpoint
import com.librestatic.lightforge.core.ml.MlControlState
import com.librestatic.lightforge.core.ml.MlTaskType
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
    fun maintenanceWinsOverAnalysis() {
        assertEquals(
            LibraryBackgroundStatus(LibraryBackgroundWork.SearchIndex),
            libraryBackgroundStatus(LibraryMaintenance.SearchIndex, running(MlTaskType.FaceDetection), idle),
        )
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
}
