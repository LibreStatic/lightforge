package com.ugallery.core.data

import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class MediaStoreChangeBatch(
    val rowHints: Set<MediaKey>,
    /** Always true: row hints accelerate visibility, generations still advance the durable checkpoint. */
    val requiresVolumeGenerationSync: Boolean,
    /** Collection-only notifications cannot identify deleted rows, so they require reconciliation. */
    val requiresFullVolumeReconciliation: Boolean,
)

internal class ChangeBurstCoalescer<T>(
    private val scope: CoroutineScope,
    private val quietWindowMillis: Long,
    private val onBatch: suspend (Set<T>) -> Unit,
) {
    private val lock = Any()
    private val pending = linkedSetOf<T>()
    private var drainJob: Job? = null

    init { require(quietWindowMillis >= 0) }

    fun submit(item: T) = synchronized(lock) {
        pending += item
        drainJob?.cancel()
        drainJob = scope.launch {
            delay(quietWindowMillis)
            val batch = synchronized(lock) {
                pending.toSet().also {
                    pending.clear()
                    drainJob = null
                }
            }
            onBatch(batch)
        }
    }

    fun cancel() = synchronized(lock) {
        drainJob?.cancel()
        drainJob = null
        pending.clear()
    }
}

/** Registers one descendant observer and collapses camera bursts before scheduling sync work. */
class MediaStoreChangeMonitor(
    private val resolver: android.content.ContentResolver,
    scope: CoroutineScope,
    quietWindowMillis: Long = 350,
    onBatch: suspend (MediaStoreChangeBatch) -> Unit,
) : AutoCloseable {
    private val coalescer = ChangeBurstCoalescer<Uri?>(scope, quietWindowMillis) { uris ->
        onBatch(mediaStoreChangeBatch(uris))
    }
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = coalescer.submit(uri)
        override fun onChange(selfChange: Boolean) = coalescer.submit(null)
    }
    private var registered = false

    fun start() {
        if (registered) return
        resolver.registerContentObserver(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            true,
            observer,
        )
        registered = true
    }

    override fun close() {
        if (registered) resolver.unregisterContentObserver(observer)
        registered = false
        coalescer.cancel()
    }
}

internal fun mediaStoreChangeBatch(uris: Set<Uri?>): MediaStoreChangeBatch {
    return mediaStoreChangeBatchStrings(uris.map { it?.toString() }.toSet())
}

internal fun mediaStoreChangeBatchStrings(uris: Set<String?>): MediaStoreChangeBatch {
    val parsed = uris.mapNotNull(::mediaKeyFromObserverUriString).toSet()
    return MediaStoreChangeBatch(
        rowHints = parsed,
        requiresVolumeGenerationSync = true,
        requiresFullVolumeReconciliation = uris.isEmpty() || uris.any {
            mediaKeyFromObserverUriString(it) == null
        },
    )
}

internal fun mediaKeyFromObserverUri(uri: Uri?): MediaKey? {
    return mediaKeyFromObserverUriString(uri?.toString())
}

internal fun mediaKeyFromObserverUriString(raw: String?): MediaKey? {
    val uri = raw?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return null
    if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return null
    val segments = uri.path.split('/').filter(String::isNotBlank)
    val volume = segments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
    // `external` is the synthetic aggregate volume, never a stable identity namespace.
    if (volume == MediaStore.VOLUME_EXTERNAL) return null
    val id = segments.lastOrNull()?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    return MediaKey(volume, id)
}
