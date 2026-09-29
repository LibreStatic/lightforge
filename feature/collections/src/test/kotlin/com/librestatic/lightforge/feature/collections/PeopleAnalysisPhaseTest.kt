package com.librestatic.lightforge.feature.collections

import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleAnalysisPhaseTest {
    @Test
    fun `complete analysis is never presented as pausable`() {
        val state = PeopleUiState(consentGranted = true, analysisStage = PeopleAnalysisStage.Complete)
        assertEquals(PeopleAnalysisPhase.Complete, state.analysisPhase())
    }

    @Test
    fun `paused wins over a stale running flag`() {
        val state = PeopleUiState(consentGranted = true, paused = true, running = true, analysisStage = PeopleAnalysisStage.FaceDetection)
        assertEquals(PeopleAnalysisPhase.Paused, state.analysisPhase())
    }

    @Test
    fun `waiting for the worker counts as running`() {
        val state = PeopleUiState(consentGranted = true, waiting = true, analysisStage = PeopleAnalysisStage.FaceEmbeddings)
        assertEquals(PeopleAnalysisPhase.Running, state.analysisPhase())
    }

    @Test
    fun `an idle library offers to start`() {
        assertEquals(PeopleAnalysisPhase.Ready, PeopleUiState(consentGranted = true).analysisPhase())
    }
}
