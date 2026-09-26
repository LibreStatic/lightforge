package com.ugallery.feature.privatealbum

/**
 * Decides when a legacy (unauthenticated-key) vault is moved to authenticated keys without an
 * explicit tap. The user has just authenticated to unlock, so the existing resumable
 * "Protect media and index keys" migration runs once per unlock. A [PrivateKeyProtectionStatus.Pending]
 * journal left by process death resumes the same way; a failure leaves the legacy vault usable
 * and is retried on the next unlock.
 */
internal object PrivateLegacyMigrationPolicy {
    fun shouldMigrateOnUnlock(
        unlocked: Boolean,
        indexReady: Boolean,
        setupComplete: Boolean,
        status: PrivateKeyProtectionStatus,
        attemptedThisUnlock: Boolean,
        protectionInProgress: Boolean,
    ): Boolean = unlocked && indexReady && setupComplete && !attemptedThisUnlock && !protectionInProgress &&
        status != PrivateKeyProtectionStatus.Protected
}
