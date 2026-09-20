package com.ugallery.app

/**
 * Shared ownership for both selection and saved-memory video preparers. Confine all calls to
 * the main dispatcher. Start before the first suspension, check immediately before publishing,
 * and invalidate on navigation. Cancelling a request does not clear an already-published draft.
 */
class MemoryVideoRequestEpoch {
    /** Identity rather than a reusable counter: a cancelled request can never become current again. */
    class Token internal constructor()

    private var active: Token? = null

    fun begin(): Token = Token().also { active = it }

    fun isCurrent(token: Token): Boolean = active === token

    /** Navigation/lifecycle invalidation, with no side effect on editor session state. */
    fun cancel() { active = null }

    /** Stale completion/cancellation handlers must not invalidate a newer request. */
    fun cancel(expected: Token): Boolean {
        if (!isCurrent(expected)) return false
        active = null
        return true
    }
}
