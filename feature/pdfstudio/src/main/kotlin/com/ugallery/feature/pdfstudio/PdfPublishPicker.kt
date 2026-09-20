package com.ugallery.feature.pdfstudio

import androidx.lifecycle.SavedStateHandle

/** Outcome of asking the picker to start a CreateDocument request. */
internal sealed interface PublishStart {
    /** Nothing outstanding: the caller owns the request and must launch the picker. */
    object Launch : PublishStart

    /** A request is already recorded. The caller must clear it before launching again. */
    data class AlreadyPending(val request: Pair<String, String?>) : PublishStart
}

/** Pure decision behind [PdfPublishPicker.begin]; side-effect free so it can be unit tested. */
internal fun beginDecision(current: Pair<String, String?>?): PublishStart =
    current?.let(PublishStart::AlreadyPending) ?: PublishStart.Launch

/** One outstanding CreateDocument request. Android saves this with the ActivityResult registry. */
internal class PdfPublishPicker(private val saved: SavedStateHandle) {
    val pending = saved.getStateFlow<ArrayList<String>?>(KEY, null)
    val request: Pair<String, String?>?
        get() = pending.value?.let { it[0] to it.getOrNull(1) }

    fun begin(jobId: String): PublishStart {
        val decision = beginDecision(request)
        if (decision is PublishStart.Launch) saved[KEY] = arrayListOf(jobId)
        return decision
    }

    fun result(uri: String?): Boolean {
        val current = request ?: return false
        // A cancelled/absent result always clears, so process death can never wedge the button.
        if (uri == null) {
            saved[KEY] = null
            return false
        }
        if (current.second != null) return false // duplicate delivery while publishing
        saved[KEY] = arrayListOf(current.first, uri)
        return true
    }

    fun acknowledge(request: Pair<String, String?>) {
        if (this.request == request) saved[KEY] = null
    }

    /** Drop whatever is recorded, whether or not a result was ever delivered. */
    fun discard() {
        saved[KEY] = null
    }

    companion object {
        const val KEY = "pdfPublishPicker"
    }
}
