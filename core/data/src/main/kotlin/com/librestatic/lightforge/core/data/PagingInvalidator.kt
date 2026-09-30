package com.librestatic.lightforge.core.data

import androidx.paging.PagingSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the live sources a pager created so a caller can invalidate them explicitly, instead of
 * relying only on Room's table observers (a notification missed during the initial scan would
 * otherwise leave a stale first page until the next media change).
 */
class PagingInvalidator {
    private val live = ConcurrentHashMap.newKeySet<PagingSource<*, *>>()

    fun <K : Any, V : Any> track(factory: () -> PagingSource<K, V>): () -> PagingSource<K, V> = {
        factory().also { source ->
            live += source
            source.registerInvalidatedCallback { live -= source }
        }
    }

    fun invalidate() = live.toList().forEach { it.invalidate() }
}
