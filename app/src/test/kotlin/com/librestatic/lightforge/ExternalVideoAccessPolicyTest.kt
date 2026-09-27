package com.librestatic.lightforge

import org.junit.Assert.*
import org.junit.Test

class ExternalVideoAccessPolicyTest {
    @Test fun readableFreshSessionStaysAvailable() = assertFalse(externalVideoAccessBlocked(false, true, false))
    @Test fun permissionLossBlocksSession() = assertTrue(externalVideoAccessBlocked(false, false, false))
    @Test fun foregroundRegrantRemainsBlocked() = assertTrue(externalVideoAccessBlocked(true, true, false))
    @Test fun repeatedDeniedForegroundRemainsBlocked() = assertTrue(externalVideoAccessBlocked(true, false, false))
    @Test fun explicitRetryWithoutGrantRemainsBlocked() = assertTrue(externalVideoAccessBlocked(true, false, true))
    @Test fun explicitRetryWithGrantUnblocks() = assertFalse(externalVideoAccessBlocked(true, true, true))
    @Test fun retryNeverBypassesUnreadableSource() = assertTrue(externalVideoAccessBlocked(false, false, true))
    @Test fun explicitReadableCheckDoesNotBlockFreshSession() = assertFalse(externalVideoAccessBlocked(false, true, true))
}
