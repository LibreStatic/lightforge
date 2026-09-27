package com.librestatic.lightforge.feature.privatealbum

import com.librestatic.lightforge.core.security.PrivateAlbumCrypto
import kotlinx.coroutines.CancellationException

/** [interrupted]: authentication was not renewed or access ended, so the rest was not attempted. */
data class PrivateImportBatchResult<T>(val successful: List<T>, val interrupted: Boolean)

/**
 * Authenticated master keys are usable for 30 s after the last prompt, and choosing items or
 * encrypting a large batch can outlast that. An expired window asks [reauthenticate] once per
 * item and resumes with a freshly resolved key instead of revoking the session and dropping
 * the selection.
 */
suspend fun <T, K> importPrivateBatch(
    items: List<T>,
    resolveKey: suspend () -> K,
    importOne: suspend (T, K) -> Boolean,
    reauthenticate: suspend () -> Boolean,
    hasAccess: () -> Boolean,
    onProgress: (completed: Int) -> Unit = {},
    requiresAuthentication: (Throwable) -> Boolean = PrivateAlbumCrypto::requiresAuthentication,
): PrivateImportBatchResult<T> {
    val successful = mutableListOf<T>()
    var key: K? = null
    var renewedAt = -1
    var index = 0
    while (index < items.size) {
        if (!hasAccess()) return PrivateImportBatchResult(successful, interrupted = true)
        try {
            val current = key ?: resolveKey().also { key = it }
            onProgress(index + 1)
            if (importOne(items[index], current)) successful += items[index]
            index++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!requiresAuthentication(failure)) {
                // Only the key resolution escapes here; a vault without a usable key adds nothing more.
                return PrivateImportBatchResult(successful, interrupted = false)
            }
            if (renewedAt == index || !reauthenticate() || !hasAccess()) {
                return PrivateImportBatchResult(successful, interrupted = true)
            }
            renewedAt = index
            key = null
        }
    }
    return PrivateImportBatchResult(successful, interrupted = false)
}
