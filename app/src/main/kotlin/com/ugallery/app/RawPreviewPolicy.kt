package com.ugallery.app

import android.content.Context
import com.ugallery.core.thumbnail.DeviceHardwareProfile
import com.ugallery.core.thumbnail.DevicePerformanceClassifier
import com.ugallery.core.thumbnail.DevicePerformanceTier

internal data class RawPreviewHardware(
    val mediaPerformanceClass: Int,
    val processors: Int,
    val totalMemoryBytes: Long,
    val lowRam: Boolean,
)

internal data class RawPreviewProfile(
    val maxDimension: Int,
    val minimumIntervalMillis: Long,
    val continuous: Boolean,
)

internal object RawPreviewPolicy {
    fun detect(context: Context): RawPreviewProfile {
        val hardware = DevicePerformanceClassifier.detect(context)
        return select(
            RawPreviewHardware(
                mediaPerformanceClass = hardware.mediaPerformanceClass,
                processors = hardware.processors,
                totalMemoryBytes = hardware.totalMemoryBytes,
                lowRam = hardware.lowRam,
            ),
        )
    }

    fun select(hardware: RawPreviewHardware): RawPreviewProfile {
        val capable = DevicePerformanceClassifier.classify(
            DeviceHardwareProfile(
                mediaPerformanceClass = hardware.mediaPerformanceClass,
                processors = hardware.processors,
                totalMemoryBytes = hardware.totalMemoryBytes,
                lowRam = hardware.lowRam,
            ),
        ) == DevicePerformanceTier.Flagship
        return when {
            capable -> RawPreviewProfile(maxDimension = 1_024, minimumIntervalMillis = 0, continuous = true)
            hardware.lowRam -> RawPreviewProfile(maxDimension = 512, minimumIntervalMillis = 500, continuous = false)
            else -> RawPreviewProfile(maxDimension = 640, minimumIntervalMillis = 250, continuous = false)
        }
    }
}
