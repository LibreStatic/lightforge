package com.librestatic.lightforge.feature.privatealbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateLegacyMigrationPolicyTest {
    private fun decide(
        unlocked: Boolean = true,
        indexReady: Boolean = true,
        setupComplete: Boolean = true,
        status: PrivateKeyProtectionStatus = PrivateKeyProtectionStatus.Legacy,
        attempted: Boolean = false,
        inProgress: Boolean = false,
    ) = PrivateLegacyMigrationPolicy.shouldMigrateOnUnlock(unlocked, indexReady, setupComplete, status, attempted, inProgress)

    @Test fun legacyVaultMigratesRightAfterASuccessfulUnlock() = assertTrue(decide())

    @Test fun interruptedMigrationJournalResumesOnUnlock() =
        assertTrue(decide(status = PrivateKeyProtectionStatus.Pending))

    @Test fun protectedVaultNeverMigrates() =
        assertFalse(decide(status = PrivateKeyProtectionStatus.Protected))

    @Test fun lockedOrUnreadyVaultWaits() {
        assertFalse(decide(unlocked = false))
        assertFalse(decide(indexReady = false))
        assertFalse(decide(setupComplete = false))
    }

    @Test fun runsOncePerUnlockSoAFailureRetriesOnlyOnTheNextUnlock() {
        assertFalse(decide(attempted = true))
        assertFalse(decide(inProgress = true))
    }
}
