package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.database.LibraryDao
import com.librestatic.lightforge.core.database.PendingProbeRow
import com.librestatic.lightforge.core.database.ProbeResultRow
import com.librestatic.lightforge.core.mediastore.MediaFileProbe
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** The slice of the index the background probe pass reads and writes. */
interface PendingProbeStore {
    /** The newest accessible rows still waiting for their file to be read. */
    suspend fun pendingProbeRows(limit: Int): List<PendingProbeRow>
    suspend fun pendingProbeCount(): Int

    /** Applies a whole batch in one transaction; returns how many rows were still at the probed generation. */
    suspend fun applyProbeResults(results: List<ProbeResultRow>): Int
}

class RoomPendingProbeStore(private val dao: LibraryDao) : PendingProbeStore {
    override suspend fun pendingProbeRows(limit: Int) = dao.pendingProbeRows(limit)
    override suspend fun pendingProbeCount() = dao.pendingProbeCount()
    override suspend fun applyProbeResults(results: List<ProbeResultRow>) = dao.applyProbeResults(results)
}

/**
 * Second indexing phase: pages are indexed without opening any file, and this pass reads the video
 * size/duration and image headers of the rows left flagged, newest first (what the screen shows),
 * a few files at a time. Each batch is written in one transaction, guarded by the generation that was
 * probed. It takes no refresh lock: a row rewritten meanwhile simply keeps its new state.
 */
class PendingMediaProber(
    private val store: PendingProbeStore,
    private val probe: MediaFileProbe,
    private val batchSize: Int = 64,
    parallelism: Int = 4,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init { require(batchSize > 0 && parallelism > 0) }

    private val dispatcher = ioDispatcher.limitedParallelismCompat(parallelism)
    private val running = Mutex()

    /** Whether a run is in progress; a second [run] returns immediately. */
    val isRunning: Boolean get() = running.isLocked

    /** Returns when nothing is pending, access is lost, or the caller cancels. */
    suspend fun run(onProgress: (done: Int, remaining: Int) -> Unit = { _, _ -> }) {
        if (!running.tryLock()) return
        try {
            var done = 0
            while (true) {
                coroutineContext.ensureActive()
                val rows = store.pendingProbeRows(batchSize)
                if (rows.isEmpty()) return
                var permissionLost = false
                val outcomes = coroutineScope {
                    rows.map { row -> async(dispatcher) { probeOne(row) } }.awaitAll()
                }
                val results = ArrayList<ProbeResultRow>(rows.size)
                outcomes.forEach { outcome ->
                    if (outcome == null) permissionLost = true else results += outcome
                }
                val applied = if (results.isEmpty()) 0 else withContext(NonCancellable) {
                    store.applyProbeResults(results)
                }
                done += results.size
                onProgress(done, store.pendingProbeCount())
                // Nothing applied means every row changed under us; stop instead of spinning on the same rows.
                if (permissionLost || applied == 0) return
            }
        } finally {
            running.unlock()
        }
    }

    /** Null means access was lost: the row keeps its flag. Any other failure still clears it. */
    private fun probeOne(row: PendingProbeRow): ProbeResultRow? {
        val found = try {
            probe.probe(
                MediaKey(row.volumeName, row.mediaStoreId),
                if (row.mediaType == 3) MediaKind.Video else MediaKind.Image,
                row.mimeType, row.dateModifiedSeconds, row.durationMillis, row.width, row.height,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return null
        } catch (_: Exception) {
            null
        }
        return ProbeResultRow(
            row.volumeName, row.mediaStoreId, row.generationModified,
            found?.durationMillis ?: 0, found?.width ?: 0, found?.height ?: 0, found?.isAnimated ?: false,
        )
    }
}

@Suppress("OPT_IN_USAGE")
private fun CoroutineDispatcher.limitedParallelismCompat(parallelism: Int): CoroutineDispatcher =
    limitedParallelism(parallelism)
