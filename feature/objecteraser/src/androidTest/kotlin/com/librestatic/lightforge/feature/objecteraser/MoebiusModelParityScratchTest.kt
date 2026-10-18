package com.librestatic.lightforge.feature.objecteraser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import kotlin.math.abs

/** Scratch: GPU vs CPU for every model in files/parity/ (random inputs, output "output_0"). Not for commit. */
@RunWith(AndroidJUnit4::class)
class MoebiusModelParityScratchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun run(file: File, accelerator: Accelerator, seed: Long): FloatArray {
        val env = Environment.create(context)
        val model = CompiledModel.create(file.absolutePath, CompiledModel.Options(accelerator), env)
        val random = Random(seed)
        val names = listOf("x", "t", "cond", "temb", "rgb", "latent")
            .filter { runCatching { model.getInputTensorType(it, "serving_default") }.isSuccess }
        val ins = names.associateWith { name: String ->
            val size = requireNotNull(model.getInputTensorType(name, "serving_default").layout).dimensions.fold(1) { a, b -> a * b }
            model.createInputBuffer(name, "serving_default").also { buffer ->
                buffer.writeFloat(FloatArray(size) { when (name) { "t" -> 500f; "rgb" -> random.nextFloat() * 2 - 1; else -> random.nextGaussian().toFloat() } })
            }
        }
        val out = model.createOutputBuffer("output_0", "serving_default")
        model.run(ins, mapOf("output_0" to out), "serving_default")
        val result = out.readFloat()
        ins.values.forEach { it.close() }; out.close(); model.close(); env.close()
        return result
    }

    @Test
    fun perModel() {
        val dir = requireNotNull(context.getExternalFilesDir(null))
        val models = listOf("moebius-vae-encoder", "moebius-unet", "moebius-vae-decoder")
            .map { File(dir, if (it.startsWith("moebius")) "$it.tflite" else "parity_$it.tflite") }.filter { it.canRead() }
        assumeTrue(models.isNotEmpty())
        val report = StringBuilder()
        for (file in models) {
            val line = runCatching {
                val a = run(file, Accelerator.GPU, 3)
                val b = run(file, Accelerator.CPU, 3)
                var max = 0.0; var sum = 0.0; var scale = 0.0; var nan = 0; var first = -1
                for (i in a.indices) { if (a[i].isNaN()) { nan++; if (first < 0) first = i; continue }; val d = abs(a[i] - b[i]).toDouble(); max = maxOf(max, d); sum += d; scale = maxOf(scale, abs(b[i]).toDouble()) }
                "${file.name}: max %.4f mean %.5f scale %.3f nan %d/%d first %d".format(max, sum / a.size, scale, nan, a.size, first)
            }.getOrElse { "${file.name}: FAILED ${it.message}" }
            report.appendLine(line)
        }
        File(dir, "report.txt").writeText(report.toString())
        throw AssertionError("PARITY\n$report")
    }

    @Test
    fun pipelineImage() {
        val dir = requireNotNull(context.getExternalFilesDir(null))
        val files = InpaintModelCatalog.Generative.files.map { File(dir, it.name) }
        val size = InpaintPlan.ModelSize
        val pixels = IntArray(size * size) { i -> 0xFF000000.toInt() or ((i % size) / 2 shl 16) or ((i / size) / 2 shl 8) or 0x60 }
        val dab = BrushDab(256f, 256f, 60f)
        val keep = InpaintPlan.keepMask(InpaintPass(PixelRect(0, 0, size, size), dab.bounds, listOf(dab)))
        val started = android.os.SystemClock.elapsedRealtime()
        val inpainter = MoebiusInpainter.open(context, files, "scratch", File(context.cacheDir, "scratch-gpu"))
        val openMs = android.os.SystemClock.elapsedRealtime() - started
        val out = inpainter.inpaint(pixels, keep, seed = 1L)
        val firstMs = android.os.SystemClock.elapsedRealtime() - started - openMs
        val t2 = android.os.SystemClock.elapsedRealtime(); inpainter.inpaint(pixels, keep, seed = 2L)
        val secondMs = android.os.SystemClock.elapsedRealtime() - t2
        inpainter.close()
        val ms = "open $openMs first $firstMs second $secondMs"
        val bitmap = android.graphics.Bitmap.createBitmap(out, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        File(dir, "pipeline.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (i in out.indices) if (!keep[i]) { r += (out[i] shr 16) and 255; g += (out[i] shr 8) and 255; b += out[i] and 255; n++ }
        throw AssertionError("PIPELINE ${ms} ms hole mean rgb ${r / n} ${g / n} ${b / n} over $n")
    }

    @Test
    fun pipelineStages() {
        val dir = requireNotNull(context.getExternalFilesDir(null))
        val sig = "serving_default"
        val env = Environment.create(context)
        fun load(name: String) = CompiledModel.create(File(dir, name).absolutePath, CompiledModel.Options(Accelerator.GPU), env)
        val unet = load("moebius-unet.tflite"); val enc = load("moebius-vae-encoder.tflite"); val dec = load("moebius-vae-decoder.tflite")
        val cond = java.nio.ByteBuffer.wrap(File(dir, "moebius-cond.f32").readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val uncond = FloatArray(30720).also { cond.get(it) }; val condv = FloatArray(30720).also { cond.get(it) }
        val size = InpaintPlan.ModelSize
        val pixels = IntArray(size * size) { i -> 0xFF000000.toInt() or ((i % size) / 2 shl 16) or ((i / size) / 2 shl 8) or 0x60 }
        val dab = BrushDab(256f, 256f, 60f)
        val keep = InpaintPlan.keepMask(InpaintPass(PixelRect(0, 0, size, size), dab.bounds, listOf(dab)))
        val sampler = MoebiusSampler()
        val log = StringBuilder()
        fun stat(name: String, a: FloatArray) {
            val finite = a.filter { it.isFinite() }
            log.appendLine("$name nan/inf ${a.size - finite.size}/${a.size} min ${finite.minOrNull()} max ${finite.maxOrNull()} mean ${finite.average()}")
        }
        fun run(model: CompiledModel, inputs: Map<String, FloatArray>): FloatArray {
            val ins = inputs.mapValues { (n, v) -> model.createInputBuffer(n, sig).also { it.writeFloat(v) } }
            val out = model.createOutputBuffer("output_0", sig)
            model.run(ins, mapOf("output_0" to out), sig)
            return out.readFloat().also { ins.values.forEach { b -> b.close() }; out.close() }
        }
        val masked = run(enc, mapOf("rgb" to sampler.rgbInput(pixels, keep, true))); stat("maskedLatent", masked)
        val latent = run(enc, mapOf("rgb" to sampler.rgbInput(pixels, keep, false))); stat("latent", latent)
        val mask = sampler.latentMask(keep)
        var x = sampler.start(latent, sampler.noise(1L)); stat("x0", x)
        for (t in sampler.timesteps) {
            val inp = sampler.unetInput(x, mask, masked)
            val c = run(unet, mapOf("x" to inp, "t" to floatArrayOf(t.toFloat()), "cond" to condv)); stat("eps_c t=$t", c)
            val u = run(unet, mapOf("x" to inp, "t" to floatArrayOf(t.toFloat()), "cond" to uncond)); stat("eps_u t=$t", u)
            x = sampler.step(sampler.guide(u, c, 2.5), t, x); stat("x t=$t", x)
        }
        stat("decoded", run(dec, mapOf("latent" to x)))
        throw AssertionError("STAGES\n$log")
    }

    @Test
    fun decoderStages() {
        val dir = requireNotNull(context.getExternalFilesDir(null))
        val sig = "serving_default"
        val env = Environment.create(context)
        val log = StringBuilder()
        val size = InpaintPlan.ModelSize
        val pixels = IntArray(size * size) { i -> 0xFF000000.toInt() or ((i % size) / 2 shl 16) or ((i / size) / 2 shl 8) or 0x60 }
        val keep = BooleanArray(size * size) { true }
        val enc = CompiledModel.create(File(dir, "moebius-vae-encoder.tflite").absolutePath, CompiledModel.Options(Accelerator.GPU), env)
        val encIn = enc.createInputBuffer("rgb", sig).also { it.writeFloat(MoebiusSampler().rgbInput(pixels, keep, false)) }
        val encOut = enc.createOutputBuffer("output_0", sig)
        enc.run(mapOf("rgb" to encIn), mapOf("output_0" to encOut), sig)
        val latent = encOut.readFloat()
        for (precision in listOf(CompiledModel.GpuOptions.Precision.FP16_WITH_FP32_ACCUM, CompiledModel.GpuOptions.Precision.FP32)) {
            val options = CompiledModel.Options(Accelerator.GPU).apply { gpuOptions = CompiledModel.GpuOptions(precision = precision) }
            val dec = CompiledModel.create(File(dir, "moebius-vae-decoder.tflite").absolutePath, options, env)
            val input = dec.createInputBuffer("latent", sig).also { it.writeFloat(latent) }
            val out = dec.createOutputBuffer("output_0", sig)
            dec.run(mapOf("latent" to input), mapOf("output_0" to out), sig)
            val t0 = android.os.SystemClock.elapsedRealtime()
            repeat(3) { dec.run(mapOf("latent" to input), mapOf("output_0" to out), sig) }
            val ms = (android.os.SystemClock.elapsedRealtime() - t0) / 3
            val a = out.readFloat()
            var bad = 0; var peak = 0f
            for (v in a) if (v.isFinite()) peak = maxOf(peak, kotlin.math.abs(v)) else bad++
            log.appendLine("$precision nan $bad/${a.size} maxabs $peak $ms ms")
            out.close(); input.close(); dec.close()
        }
        throw AssertionError("DECSTAGES\n$log")
    }
}
