package com.ugallery.core.ml

import android.content.Context
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.NetworkType

data class MlWorkPolicy(
    val chunkSize: Int,
    val constraints: Constraints,
) {
    init { require(chunkSize in 50..200) }

    companion object {
        fun forMode(mode: MlRunMode): MlWorkPolicy = MlWorkPolicy(
            chunkSize = if (mode == MlRunMode.Recent) 50 else 100,
            constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                // Full-library analysis is explicitly started by the user. Requiring both
                // charging and device-idle made the action appear broken while the app was
                // open. Battery, storage and thermal guards still protect the device.
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .build(),
        )
    }
}

fun interface ThermalStatusProvider { fun status(): Int }

class AndroidThermalStatusProvider(context: Context) : ThermalStatusProvider {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)
    override fun status(): Int = power.currentThermalStatus
}

enum class MlExecutionDecision { Run, ConsentRequired, Paused, PermissionLost, ThermalBackoff }

class MlExecutionController(
    private val thermal: ThermalStatusProvider,
) {
    fun decide(consent: Boolean, paused: Boolean, permission: Boolean): MlExecutionDecision = when {
        !consent -> MlExecutionDecision.ConsentRequired
        paused -> MlExecutionDecision.Paused
        !permission -> MlExecutionDecision.PermissionLost
        thermal.status() >= PowerManager.THERMAL_STATUS_MODERATE -> MlExecutionDecision.ThermalBackoff
        else -> MlExecutionDecision.Run
    }
}
