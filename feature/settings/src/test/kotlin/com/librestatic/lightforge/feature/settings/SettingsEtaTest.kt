package com.librestatic.lightforge.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsEtaTest {
    @Test fun `under a minute stays under a minute`() = assertEquals(SettingsEta.UnderMinute, settingsEta(59_000L))

    @Test fun `minutes round coarsely`() = assertEquals(SettingsEta.Minutes(25), settingsEta(24 * 60_000L))

    @Test fun `an hour or more reports whole hours`() = assertEquals(SettingsEta.Hours(2), settingsEta(110 * 60_000L))
}
