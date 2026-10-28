package com.librestatic.lightforge.feature.photos

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryEtaBucketTest {
    @Test fun underAMinuteIsCoarse() = assertEquals(EtaBucket.UnderMinute, etaBucket(59_999))

    @Test fun minutesAreRounded() {
        assertEquals(EtaBucket.Minutes(1), etaBucket(60_000))
        assertEquals(EtaBucket.Minutes(25), etaBucket(24 * 60_000L + 40_000))
    }

    @Test fun hoursAreWholeAndNeverZero() {
        assertEquals(EtaBucket.Hours(1), etaBucket(60 * 60_000L))
        assertEquals(EtaBucket.Hours(2), etaBucket(90 * 60_000L))
        assertEquals(EtaBucket.Hours(3), etaBucket(3 * 60 * 60_000L))
    }
}
