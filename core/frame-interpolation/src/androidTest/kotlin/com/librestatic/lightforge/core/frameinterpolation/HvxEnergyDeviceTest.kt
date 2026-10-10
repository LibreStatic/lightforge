package com.librestatic.lightforge.core.frameinterpolation

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Energy per guided frame on the DSP and on the GPU, from the battery fuel gauge. Opt-in (several minutes, needs
 * the phone unplugged): `-Pandroid.testInstrumentationRunnerArguments.hvxEnergy=true`. Results go to logcat with
 * the `LIGHTFORGE_HVX_ENERGY` tag.
 */
@RunWith(AndroidJUnit4::class)
class HvxEnergyDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val battery get() = context.getSystemService(BatteryManager::class.java)

    @Test
    fun energyPerGuidedFrame() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hvxEnergy") == "true")
        assumeTrue(runBlocking { FrameInterpolationEngines.isDspAvailable(context) })
        assumeTrue("Unplug the phone", !isCharging())
        val highA = texturedBitmap(1920, 1080, 0f)
        val highB = texturedBitmap(1920, 1080, 12f)
        val lowA = Bitmap.createScaledBitmap(highA, 512, 288, true)
        val lowB = Bitmap.createScaledBitmap(highB, 512, 288, true)
        RifeFrameInterpolator(context, engine = FrameInterpolationEngine.Dsp).use { dsp ->
            RifeFrameInterpolator(context, engine = FrameInterpolationEngine.Gpu).use { gpu ->
                assumeTrue(dsp.capability.backend == FrameInterpolationBackend.Hvx)
                assumeTrue(gpu.capability.backend == FrameInterpolationBackend.Vulkan)
                // Warm both paths so pipeline creation and weight upload stay out of the measurement.
                dsp.interpolateGuided(lowA, lowB, highA, highB, 0.5f).recycle()
                gpu.interpolateGuided(lowA, lowB, highA, highB, 0.5f).recycle()
                repeat(Rounds) { round ->
                    listOf("gpu" to gpu, "dsp" to dsp).forEach { (name, interpolator) ->
                        val idle = measure(IdleMillis) { SystemClock.sleep(50) }
                        var frames = 0
                        val busy = measure(BusyMillis) {
                            interpolator.interpolateGuided(lowA, lowB, highA, highB, 0.5f).recycle()
                            frames++
                        }
                        val netWatts = busy.watts - idle.watts
                        println(
                            "LIGHTFORGE_HVX_ENERGY round=$round backend=$name frames=$frames " +
                                "msPerFrame=${"%.1f".format(busy.seconds * 1000 / frames)} " +
                                "idleW=${"%.3f".format(idle.watts)} busyW=${"%.3f".format(busy.watts)} " +
                                "netW=${"%.3f".format(netWatts)} " +
                                "netMilliJoulePerFrame=${"%.1f".format(netWatts * busy.seconds * 1000 / frames)} " +
                                "totalMilliJoulePerFrame=${"%.1f".format(busy.watts * busy.seconds * 1000 / frames)} " +
                                "tempC=${temperature()}",
                        )
                    }
                }
            }
        }
        listOf(highA, highB, lowA, lowB).forEach(Bitmap::recycle)
    }

    private class Sample(val watts: Double, val seconds: Double)

    /** Runs [work] repeatedly for [millis] while a sampler integrates battery power. */
    private fun measure(millis: Long, work: () -> Unit): Sample {
        val running = AtomicBoolean(true)
        var sum = 0.0
        var count = 0
        val sampler = thread(name = "battery-sampler") {
            while (running.get()) {
                sum += watts()
                count++
                SystemClock.sleep(SampleMillis)
            }
        }
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < millis) work()
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
        running.set(false)
        sampler.join()
        return Sample(sum / count.coerceAtLeast(1), seconds)
    }

    /** Power drawn from the battery, in watts (discharge current is reported negative here). */
    private fun watts(): Double {
        val microAmps = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val milliVolts = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        return -microAmps / 1e6 * (milliVolts / 1e3)
    }

    private fun batteryIntent(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun isCharging(): Boolean = (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun temperature(): Double = (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

    private companion object {
        const val Rounds = 2
        const val IdleMillis = 30_000L
        const val BusyMillis = 60_000L
        const val SampleMillis = 250L
    }
}
