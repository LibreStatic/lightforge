package com.librestatic.lightforge.feature.semanticsearch

import android.content.Context
import android.os.CancellationSignal
import androidx.annotation.WorkerThread
import java.io.File

/** Blocking local-package import. Only immutable, signed catalog packages may activate. */
object SemanticModelPackages {
    @WorkerThread
    fun installVerified(context: Context, modelId: String, archive: File, signal: CancellationSignal = CancellationSignal()) {
        val descriptor = requireNotNull(SemanticModelCatalog.models.singleOrNull { it.id == modelId })
        val cancellation = SemanticDownloadCancellation()
        signal.setOnCancelListener { cancellation.cancel() }
        try {
            cancellation.checkCurrent()
            SemanticModelStorage(context).installVerified(descriptor, archive, cancellation)
        } finally { signal.setOnCancelListener(null) }
    }
}
