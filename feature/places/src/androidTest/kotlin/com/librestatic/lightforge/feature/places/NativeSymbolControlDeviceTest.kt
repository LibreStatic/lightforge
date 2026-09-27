package com.librestatic.lightforge.feature.places

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer

/** Diagnostic control: no Compose tree, region, lazy layout, or restored MapView. */
class NativeSymbolControlDeviceTest {
    @Test
    fun nativeSdfCountAndAndroidBitmapUseSameMaterialForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.filesDir, "maps-native-symbol-control").apply { mkdirs() }
        val foreground = lightColorScheme().onPrimaryContainer.toArgb()
        val background = lightColorScheme().primaryContainer.toArgb()
        fun css(color: Int) = "#%06x".format(color and 0xffffff)
        val icon = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(icon)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = background
        canvas.drawCircle(48f, 48f, 42f, paint)
        paint.color = foreground
        paint.textSize = 48f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("1", 48f, 48f - (paint.ascent() + paint.descent()) / 2f, paint)
        File(out, "android-source.png").outputStream().use {
            icon.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // The left count is data-driven, the middle count is constant, and the right is RGBA.
        val json =
            """
            {"version":8,"glyphs":"asset://places/fonts/{fontstack}/{range}.pbf",
             "sources":{
               "sdf":{"type":"geojson","data":{"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"count":1},"geometry":{"type":"Point","coordinates":[-0.03,0]}}]}},
               "constant":{"type":"geojson","data":{"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}]}},
               "bitmap":{"type":"geojson","data":{"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[0.03,0]}}]}}},
             "layers":[
               {"id":"background","type":"background","paint":{"background-color":"#f5f3ee"}},
               {"id":"circle","type":"circle","source":"sdf","paint":{"circle-radius":32,"circle-color":"${css(background)}"}},
               {"id":"constant-circle","type":"circle","source":"constant","paint":{"circle-radius":32,"circle-color":"${css(background)}"}},
               {"id":"sdf-count","type":"symbol","source":"sdf","layout":{"text-field":["to-string",["get","count"]],"text-font":["Noto Sans Regular"],"text-size":36,"text-allow-overlap":true,"text-ignore-placement":true},"paint":{"text-color":"${css(foreground)}"}},
               {"id":"constant-count","type":"symbol","source":"constant","layout":{"text-field":"1","text-font":["Noto Sans Regular"],"text-size":36,"text-allow-overlap":true,"text-ignore-placement":true},"paint":{"text-color":"${css(foreground)}"}}
             ]}
        """
                .trimIndent()
        File(out, "input-style.json").writeText(json)
        val ready = CountDownLatch(1)
        var view: MapView? = null
        var map: MapLibreMap? = null
        var last: Bitmap? = null
        var observations = JSONObject()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                OfflineMapNetworkGuard.install()
                MapLibre.getInstance(activity)
                val native = MapView(activity)
                view = native
                native.onCreate(null)
                activity.setContentView(native)
                native.onStart()
                native.onResume()
                native.getMapAsync { loaded ->
                    map = loaded
                    loaded.cameraPosition =
                        CameraPosition.Builder().target(LatLng(0.0, 0.0)).zoom(12.0).build()
                    loaded.setStyle(Style.Builder().fromJson(json)) { style ->
                        style.addImage("android-digit", icon)
                        style.addLayer(
                            SymbolLayer("bitmap-count", "bitmap")
                                .withProperties(
                                    iconImage("android-digit"),
                                    iconAllowOverlap(true),
                                    iconIgnorePlacement(true),
                                )
                        )
                        File(out, "effective-style.json").writeText(style.json)
                        ready.countDown()
                    }
                }
            }
            assertTrue("Native local style loaded", ready.await(20, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var sdfPixels = 0
            var constantPixels = 0
            var bitmapPixels = 0
            var sdfPlaced = 0
            var bitmapPlaced = 0
            while (System.nanoTime() < deadline) {
                val captured = CountDownLatch(1)
                var centers = emptyList<PointF>()
                scenario.onActivity {
                    val native = checkNotNull(map)
                    centers =
                        listOf(-0.03, 0.0, 0.03).map { longitude ->
                            native.projection.toScreenLocation(LatLng(0.0, longitude))
                        }
                    sdfPlaced =
                        native.queryRenderedFeatures(RectF(0f, 0f, 5000f, 5000f), "sdf-count").size
                    bitmapPlaced =
                        native
                            .queryRenderedFeatures(RectF(0f, 0f, 5000f, 5000f), "bitmap-count")
                            .size
                    native.snapshot { image ->
                        last?.recycle()
                        last = image
                        captured.countDown()
                    }
                }
                assertTrue("Native snapshot callback", captured.await(10, TimeUnit.SECONDS))
                val image = checkNotNull(last)
                fun count(center: PointF, color: Int): Int {
                    var matching = 0
                    // Crop each separate symbol: a correctly colored bitmap must not mask SDF
                    // failure.
                    val radius =
                        minOf(
                            (70 * context.resources.displayMetrics.density).toInt(),
                            (kotlin.math.abs(centers[1].x - centers[0].x) * 0.4f).toInt(),
                        )
                    for (y in
                        maxOf(0, center.y.toInt() - radius) until
                            minOf(image.height, center.y.toInt() + radius)) {
                        for (x in
                            maxOf(0, center.x.toInt() - radius) until
                                minOf(image.width, center.x.toInt() + radius)) {
                            val pixel = image.getPixel(x, y)
                            if (
                                kotlin.math.abs(Color.red(pixel) - Color.red(color)) <= 3 &&
                                    kotlin.math.abs(Color.green(pixel) - Color.green(color)) <= 3 &&
                                    kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) <= 3
                            )
                                matching++
                        }
                    }
                    return matching
                }
                sdfPixels = count(centers[0], foreground)
                constantPixels = count(centers[1], foreground)
                bitmapPixels = count(centers[2], foreground)
                observations =
                    JSONObject()
                        .put("foreground", css(foreground))
                        .put("background", css(background))
                        .put("sdfPixels", sdfPixels)
                        .put("constantPixels", constantPixels)
                        .put("bitmapPixels", bitmapPixels)
                        .put("sdfPlaced", sdfPlaced)
                        .put("bitmapPlaced", bitmapPlaced)
                        .put("width", image.width)
                        .put("height", image.height)
                        .put("sdfBluePixels", count(centers[0], Color.BLUE))
                        .put("sdfGreenPixels", count(centers[0], Color.GREEN))
                        .put("centers", centers.joinToString { "${it.x},${it.y}" })
                        .put("sdk", android.os.Build.VERSION.SDK_INT)
                if (
                    sdfPixels >= 2 &&
                        constantPixels >= 2 &&
                        bitmapPixels >= 2 &&
                        sdfPlaced > 0 &&
                        bitmapPlaced > 0
                )
                    break
                // Wait for the next rendered frame instead of a fixed pause.
                val frame = CountDownLatch(1)
                scenario.onActivity {
                    val native = checkNotNull(view)
                    native.addOnDidFinishRenderingFrameListener(
                        object : MapView.OnDidFinishRenderingFrameListener {
                            override fun onDidFinishRenderingFrame(
                                fully: Boolean,
                                frameEncodingTime: Double,
                                frameRenderingTime: Double,
                            ) {
                                native.removeOnDidFinishRenderingFrameListener(this)
                                frame.countDown()
                            }
                        }
                    )
                    checkNotNull(map).triggerRepaint()
                }
                frame.await(2, TimeUnit.SECONDS)
            }
            File(out, "result.json").writeText(observations.toString(2))
            last?.let { image ->
                File(out, "native.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            assertTrue("Android RGBA icon control must place: $observations", bitmapPlaced > 0)
            assertTrue(
                "Android RGBA icon control must preserve foreground: $observations",
                bitmapPixels >= 2,
            )
            assertTrue("SDF must place: $observations", sdfPlaced > 0)
            assertTrue(
                "Native SDF constant must preserve foreground: $observations",
                constantPixels >= 2,
            )
            assertTrue(
                "Native SDF expression must preserve foreground: $observations",
                sdfPixels >= 2,
            )
        } catch (failure: Throwable) {
            File(out, "failure.txt").writeText(failure.stackTraceToString())
            File(out, "result.json").writeText(observations.toString(2))
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(out, "screen-failure.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            last?.let { image ->
                File(out, "native.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            throw failure
        } finally {
            scenario.onActivity {
                view?.let {
                    it.onPause()
                    it.onStop()
                    it.onDestroy()
                }
            }
            scenario.close()
            last?.recycle()
            icon.recycle()
        }
    }
}
