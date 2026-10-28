package com.librestatic.lightforge.core.search

import android.content.Context
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface SearchDocumentSource {
    suspend fun page(afterExclusive: MediaKey?, limit: Int): List<MediaSearchDocument>
}

/**
 * Durable record of the media whose search document may be stale. Entries carry a monotonically
 * increasing sequence number; one that is re-dirtied while a drain runs gets a higher number than
 * the drain's snapshot and therefore survives the acknowledgement.
 */
interface SearchIndexJournal {
    /** The highest sequence number, or 0 when the journal is empty. */
    suspend fun maxSeq(): Long
    suspend fun keys(throughSeq: Long, limit: Int): List<MediaKey>
    /** Current documents of [keys]; keys without an entry are no longer eligible for search. */
    suspend fun documents(keys: List<MediaKey>): List<MediaSearchDocument>
    suspend fun acknowledge(throughSeq: Long, keys: List<MediaKey>)
    suspend fun clear(throughSeq: Long)
    /** Number of rows the index should hold. */
    suspend fun eligibleCount(): Int
}

data class SearchRebuildCheckpoint(
    val afterExclusive: MediaKey?,
    val indexedCount: Long,
)

interface SearchRebuildStateStore {
    fun read(): SearchRebuildCheckpoint?
    fun write(checkpoint: SearchRebuildCheckpoint)
    fun clear()
}

class SharedPreferencesSearchRebuildStateStore(
    context: Context,
    databaseName: String = MediaSearchIndex.DefaultDatabase,
) : SearchRebuildStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "search-rebuild-$databaseName",
        Context.MODE_PRIVATE,
    )

    override fun read(): SearchRebuildCheckpoint? {
        if (!preferences.getBoolean(Key.Running, false)) return null
        val volume = preferences.getString(Key.Volume, null)
        val id = preferences.getLong(Key.Id, Long.MIN_VALUE)
        return SearchRebuildCheckpoint(
            if (volume == null || id == Long.MIN_VALUE) null else MediaKey(volume, id),
            preferences.getLong(Key.Count, 0),
        )
    }

    override fun write(checkpoint: SearchRebuildCheckpoint) {
        preferences.edit()
            .putBoolean(Key.Running, true)
            .putString(Key.Volume, checkpoint.afterExclusive?.volumeName)
            .putLong(Key.Id, checkpoint.afterExclusive?.mediaStoreId ?: Long.MIN_VALUE)
            .putLong(Key.Count, checkpoint.indexedCount)
            .commit()
    }

    override fun clear() {
        preferences.edit().clear().commit()
    }

    private object Key {
        const val Running = "running"
        const val Volume = "volume"
        const val Id = "id"
        const val Count = "count"
    }
}

/** What [SearchIndexCoordinator.synchronize] did. */
data class SearchSyncResult(
    val drainedKeys: Int,
    /** Why the index was rebuilt from scratch instead of updated, or null when it was not. */
    val rebuildReason: String?,
    /** True when the document count was compared (or a rebuild made it moot). */
    val verified: Boolean = rebuildReason != null,
)

sealed interface SearchRebuildResult {
    data class Complete(val indexedCount: Long) : SearchRebuildResult
    data class Paused(val checkpoint: SearchRebuildCheckpoint) : SearchRebuildResult
}

class SearchIndexCoordinator(
    private val index: MediaSearchIndex,
    private val source: SearchDocumentSource,
    private val state: SearchRebuildStateStore,
    private val journal: SearchIndexJournal? = null,
) {
    // Drains and rebuilds write the same documents, so they never overlap.
    private val lock = Mutex()

    suspend fun onMediaChanged(documents: List<MediaSearchDocument>) {
        documents.chunked(MediaSearchIndex.MaxBatchSize).forEach { index.put(it) }
    }

    /** True when an earlier full rebuild was interrupted and can be resumed. */
    fun hasInterruptedRebuild(): Boolean = state.read() != null

    suspend fun rebuild(
        restart: Boolean = false,
        shouldContinue: suspend () -> Boolean = { true },
    ): SearchRebuildResult = lock.withLock { rebuildLocked(restart, shouldContinue) }

    /**
     * Applies the journalled changes to the index in batches. Each batch is written to the index
     * before its journal rows are acknowledged, so a crash only repeats idempotent work.
     * Returns the number of distinct keys processed.
     */
    suspend fun drain(): Int = lock.withLock { drainLocked() }

    /**
     * Brings the index up to date incrementally. The index is rebuilt in full only when the
     * incremental path fails, or, when [verifyCount] is set, when the drained index holds a
     * different number of documents than the library has eligible rows. [onRebuild] is told the
     * reason before a rebuild starts.
     */
    suspend fun synchronize(
        verifyCount: Boolean,
        onRebuild: suspend (reason: String) -> Unit = {},
    ): SearchSyncResult = lock.withLock {
        val active = journal ?: error("A journal is required to synchronize")
        suspend fun recover(reason: String): SearchSyncResult {
            onRebuild(reason)
            // A restarted rebuild resets the index and its checkpoint before writing anything.
            rebuildLocked(restart = true, shouldContinue = { true })
            return SearchSyncResult(0, reason)
        }
        val drained = try {
            drainLocked()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return@withLock recover("incremental update failed: ${failure.message}")
        }
        if (verifyCount && active.maxSeq() == 0L) {
            val expected = active.eligibleCount()
            val actual = try {
                index.documentCount()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return@withLock recover("index unreadable: ${failure.message}")
            }
            if (expected != actual) return@withLock recover("document count $actual differs from library $expected")
        }
        SearchSyncResult(drained, null, verified = verifyCount && active.maxSeq() == 0L)
    }

    private suspend fun drainLocked(): Int {
        val active = journal ?: return 0
        val through = active.maxSeq()
        if (through == 0L) return 0
        index.ensureSchema()
        var processed = 0
        while (true) {
            val keys = active.keys(through, MediaSearchIndex.MaxBatchSize)
            if (keys.isEmpty()) return processed
            val documents = active.documents(keys)
            val present = documents.mapTo(HashSet()) { it.key }
            if (documents.isNotEmpty()) index.put(documents)
            val absent = keys.filter { it !in present }
            if (absent.isNotEmpty()) index.remove(absent)
            active.acknowledge(through, keys)
            processed += keys.size
        }
    }

    private suspend fun rebuildLocked(
        restart: Boolean,
        shouldContinue: suspend () -> Boolean,
    ): SearchRebuildResult {
        index.ensureSchema()
        val restored = state.read()
        var checkpoint: SearchRebuildCheckpoint
        // Changes up to this point are covered by the pages read below; later ones stay journalled.
        // A resumed rebuild cannot vouch for the pages it already wrote, so it clears nothing.
        var coveredThrough: Long? = null
        if (restart || restored == null) {
            coveredThrough = journal?.maxSeq()
            index.clear()
            checkpoint = SearchRebuildCheckpoint(null, 0)
            state.write(checkpoint)
        } else checkpoint = restored
        while (shouldContinue()) {
            val page = source.page(checkpoint.afterExclusive, MediaSearchIndex.MaxBatchSize)
            if (page.isEmpty()) {
                coveredThrough?.let { journal?.clear(it) }
                state.clear()
                return SearchRebuildResult.Complete(checkpoint.indexedCount)
            }
            index.put(page)
            checkpoint = SearchRebuildCheckpoint(
                page.last().key,
                checkpoint.indexedCount + page.size,
            )
            state.write(checkpoint)
        }
        return SearchRebuildResult.Paused(checkpoint)
    }

    suspend fun onPermissionRevoked(keys: List<MediaKey>) {
        keys.chunked(MediaSearchIndex.MaxBatchSize).forEach { index.remove(it) }
    }

    suspend fun onVolumeUnavailable(volumeName: String) = index.purgeVolume(volumeName)

    suspend fun deleteAndReset() {
        index.clear()
        state.clear()
    }
}
