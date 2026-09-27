package com.librestatic.lightforge.core.ml

import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.NetworkType
import com.librestatic.lightforge.core.preferences.GallerySettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first

data class MlWorkPolicy(
    val mode: MlRunMode,
    val chunkSize: Int,
    val constraints: Constraints,
) {
    init { require(chunkSize in 50..200) }

    companion object {
        fun forMode(mode: MlRunMode): MlWorkPolicy {
            val fullLibrary = mode == MlRunMode.FullLibrary
            return MlWorkPolicy(
                mode = mode,
                chunkSize = if (fullLibrary) 100 else 50,
                constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    // Charging OR (foreground AND configured battery threshold) cannot be
                    // represented by WorkManager constraints, so the worker enforces that gate.
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .build(),
            )
        }
    }
}

data class AnalysisBatteryState(val percent: Int, val charging: Boolean)

fun interface AnalysisBatteryStateProvider { fun current(): AnalysisBatteryState }

class AndroidAnalysisBatteryStateProvider(context: Context) : AnalysisBatteryStateProvider {
    private val appContext = context.applicationContext

    override fun current(): AnalysisBatteryState {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return AnalysisBatteryState(
            percent = percent,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0,
        )
    }
}

/** Process-local visibility used only to grant the optional unplugged full-analysis path. */
object LocalAnalysisForegroundState {
    private val foreground = AtomicBoolean(false)
    fun setForeground(value: Boolean) = foreground.set(value)
    fun isForeground(): Boolean = foreground.get()
}

fun interface FullAnalysisEligibility { suspend fun isEligible(): Boolean }

class AndroidFullAnalysisEligibility(
    context: Context,
    private val battery: AnalysisBatteryStateProvider = AndroidAnalysisBatteryStateProvider(context),
    private val foreground: () -> Boolean = LocalAnalysisForegroundState::isForeground,
    private val minimumPercent: suspend () -> Int = {
        GallerySettingsRepository(context.applicationContext).settings.first()
            .analysis.fullAnalysisMinimumBatteryPercent
    },
) : FullAnalysisEligibility {
    override suspend fun isEligible(): Boolean {
        val state = battery.current()
        return state.charging || foreground() && state.percent >= minimumPercent()
    }
}

fun interface ThermalStatusProvider { fun status(): Int }

class AndroidThermalStatusProvider(context: Context) : ThermalStatusProvider {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)
    override fun status(): Int = power.currentThermalStatus
}

enum class MlExecutionDecision {
    Run, ConsentRequired, Paused, PermissionLost, PowerBackoff, ThermalBackoff, UserPriority,
}

class MlExecutionController(
    private val thermal: ThermalStatusProvider,
    private val fullAnalysisEligibility: FullAnalysisEligibility = FullAnalysisEligibility { true },
    private val userWorkloadActive: () -> Boolean = UserHardwareWorkloadGate::isActive,
) {
    suspend fun decide(
        consent: Boolean,
        paused: Boolean,
        permission: Boolean,
        mode: MlRunMode,
    ): MlExecutionDecision = when {
        !consent -> MlExecutionDecision.ConsentRequired
        paused -> MlExecutionDecision.Paused
        userWorkloadActive() -> MlExecutionDecision.UserPriority
        !permission -> MlExecutionDecision.PermissionLost
        mode == MlRunMode.FullLibrary && !fullAnalysisEligibility.isEligible() -> MlExecutionDecision.PowerBackoff
        thermal.status() >= PowerManager.THERMAL_STATUS_MODERATE -> MlExecutionDecision.ThermalBackoff
        else -> MlExecutionDecision.Run
    }
}
