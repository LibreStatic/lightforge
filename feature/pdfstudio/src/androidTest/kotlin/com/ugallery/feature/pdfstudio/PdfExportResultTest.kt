package com.ugallery.feature.pdfstudio

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase B item 6: the watched export's Published/Failed outcome is edge-triggered and, once
 * dismissed, must never resurface for that same job (the whole point of keeping the dismissed id
 * around instead of just clearing a transient flag).
 */
class PdfExportResultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao = PdfProjectDatabase.get(context).exports()

    private fun row(phase: PdfExportPhase, destination: String? = "content://fixture/${newId()}"): PdfExportJob {
        val now = System.currentTimeMillis()
        return PdfExportJob(
            newId(),
            newId(),
            "Result test",
            "{}",
            false,
            phase.name,
            1,
            1,
            now,
            now,
            destination = destination,
        )
    }

    @Test
    fun dismissedPublishedResultNeverResurfacesOnTheSameStudio(): Unit = runBlocking {
        val job = row(PdfExportPhase.Published)
        val store = ViewModelStore()
        try {
            dao.put(job)
            val saved = SavedStateHandle(mapOf("watchedExport" to job.id))
            val vm =
                withContext(Dispatchers.Main) {
                    PdfStudioViewModel(context.applicationContext as Application, saved).also {
                        store.put("pdf", it)
                    }
                }
            withTimeout(10_000) { while (vm.state.value.resultJobId != job.id) delay(20) }
            vm.dismissResult()
            assertNull(vm.state.value.resultJobId)
            // A later emission of the same (unchanged) jobs list must not resurrect it: give the
            // Room Flow collector another lap to prove that.
            delay(300)
            assertNull(vm.state.value.resultJobId)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            dao.delete(job.id)
        }
    }

    @Test
    fun retryingAFailedResultClearsItImmediately(): Unit = runBlocking {
        val job = row(PdfExportPhase.Failed, destination = null)
        val store = ViewModelStore()
        try {
            dao.put(job)
            val saved = SavedStateHandle(mapOf("watchedExport" to job.id))
            val vm =
                withContext(Dispatchers.Main) {
                    PdfStudioViewModel(context.applicationContext as Application, saved).also {
                        store.put("pdf", it)
                    }
                }
            withTimeout(10_000) { while (vm.state.value.resultJobId != job.id) delay(20) }
            vm.retryExport(job.id)
            // retryExport clears the stale Failed result card as soon as its (async, viewModelScope)
            // operation runs, well before the re-queued job could reach any new terminal phase.
            withTimeout(5_000) { while (vm.state.value.resultJobId != null) delay(10) }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            val current = dao.get(job.id)
            if (current != null) {
                if (current.phase !in setOf(PdfExportPhase.Published, PdfExportPhase.Cancelled)) {
                    PdfExportQueue(context).cancel(job.id)
                }
                PdfExportQueue(context).remove(job.id)
            }
        }
    }
}
