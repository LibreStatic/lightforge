package com.librestatic.lightforge.feature.places

import java.util.UUID

enum class OfflineMapFormat {
    PMTilesVector,
    PMTilesRaster,
    MBTilesVector,
    MBTilesRaster,
}

enum class OfflineMapTaskStatus {
    Queued,
    Copying,
    Downloading,
    Verifying,
    ReadyForReview,
    Installed,
    Paused,
    WaitingWifi,
    WaitingCharging,
    WaitingStorage,
    WaitingHardware,
    WaitingPermission,
    Failed,
    Cancelled,
}

data class OfflineMapPackage(
    val id: String,
    val name: String,
    val fileName: String,
    val format: OfflineMapFormat,
    val bytes: Long,
    val sha256: String,
    val bounds: PlaceBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val schema: String,
    val attribution: String,
    val installedAt: Long,
    val source: String,
    val edition: String = "",
)

data class OfflineMapTask(
    val id: String,
    val name: String,
    val source: String,
    val world: Boolean,
    val status: OfflineMapTaskStatus = OfflineMapTaskStatus.Queued,
    val copied: Long = 0,
    val total: Long = 0,
    val failure: String = "",
    val replaceId: String? = null,
    val etag: String = "",
    val ownsReadGrant: Boolean = false,
    val approved: Boolean = false,
)

data class OfflineWorldEdition(
    val id: String,
    val url: String,
    val bytes: Long,
    val blake3: String,
    val schemaVersion: String,
    val bounds: PlaceBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val attribution: String,
)

object OfflineMapCatalog {
    /** Publisher metadata and 127-byte HTTP range checked 2026-09-06. No planet bytes bundled. */
    val World =
        OfflineWorldEdition(
            "protomaps-20260906",
            "https://build.protomaps.com/20260906.pmtiles",
            137823988466L,
            "8bd7cdfb5dd0323fd0c0ae4012c7b70c524a2d9e2aba8e47b5506dfeb620322c",
            "4.15.2",
            PlaceBounds.World,
            0,
            15,
            "© OpenStreetMap contributors · Protomaps · ODbL",
        )
    const val MaxPackageBytes = 512L * 1024 * 1024 * 1024
    const val HeadroomBytes = 256L * 1024 * 1024

    fun validId(id: String) =
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
}

data class OfflineMapDeviceFacts(
    val freeBytes: Long,
    val totalRamBytes: Long,
    val lowRam: Boolean,
    val rendererCompatible: Boolean,
    val wifi: Boolean,
    val charging: Boolean,
)

object OfflineMapEligibility {
    /**
     * This threshold is an app admission policy, not a claim that every qualifying GPU is tested.
     */
    fun waiting(
        facts: OfflineMapDeviceFacts,
        remainingBytes: Long,
        world: Boolean,
    ): OfflineMapTaskStatus? {
        if (
            remainingBytes < 0 ||
                remainingBytes > OfflineMapCatalog.MaxPackageBytes ||
                facts.freeBytes < remainingBytes + OfflineMapCatalog.HeadroomBytes
        )
            return OfflineMapTaskStatus.WaitingStorage
        if (
            world &&
                (!facts.rendererCompatible ||
                    facts.lowRam ||
                    facts.totalRamBytes < 6L * 1024 * 1024 * 1024)
        )
            return OfflineMapTaskStatus.WaitingHardware
        if (world && !facts.wifi) return OfflineMapTaskStatus.WaitingWifi
        if (world && !facts.charging) return OfflineMapTaskStatus.WaitingCharging
        return null
    }
}
