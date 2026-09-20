package com.ugallery.app

/** Permission recovery is sticky until the user explicitly checks the current source grant. */
internal fun externalVideoAccessBlocked(previouslyBlocked: Boolean, readable: Boolean, explicitRetry: Boolean): Boolean =
    !readable || (previouslyBlocked && !explicitRetry)
