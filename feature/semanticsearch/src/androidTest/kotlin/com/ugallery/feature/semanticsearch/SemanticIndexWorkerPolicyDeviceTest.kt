package com.ugallery.feature.semanticsearch

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticIndexWorkerPolicyDeviceTest {
    @Test fun runtimeGateControlsFullIndexWhileWorkManagerKeepsBatteryAndStorageGuards() {
        val full = SemanticIndexWorker.request("model", "full", SemanticIndexMode.FullLibrary)
        val incremental = SemanticIndexWorker.request("model", "incremental", SemanticIndexMode.Incremental)

        assertFalse(full.workSpec.constraints.requiresCharging())
        assertTrue(full.workSpec.constraints.requiresBatteryNotLow())
        assertTrue(full.workSpec.constraints.requiresStorageNotLow())
        assertFalse(incremental.workSpec.constraints.requiresCharging())
        assertTrue(incremental.workSpec.constraints.requiresBatteryNotLow())
        assertTrue(incremental.workSpec.constraints.requiresStorageNotLow())
    }
}
