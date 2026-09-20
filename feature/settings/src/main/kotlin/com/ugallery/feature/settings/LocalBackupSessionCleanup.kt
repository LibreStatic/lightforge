package com.ugallery.feature.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Independent of the disposed composition: rollback finishes before its staging is removed. */
internal object LocalBackupSessionCleanup {
    fun closeAfterOperation(operation: Job?, retain: Boolean, close: () -> Unit): Job {
        operation?.cancel()
        return CoroutineScope(Dispatchers.IO).launch {
            operation?.join()
            if (!retain) close()
        }
    }
}
