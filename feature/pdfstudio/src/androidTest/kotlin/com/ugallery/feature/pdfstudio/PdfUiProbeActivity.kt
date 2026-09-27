package com.ugallery.feature.pdfstudio

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.ugallery.core.designsystem.UGalleryTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*
import org.json.JSONObject

/** Real native screen in bounded parent constraints; only the test APK exposes this entry point. */
class PdfUiProbeActivity : ComponentActivity() {
    private val vm by lazy { ViewModelProvider(this)[PdfStudioViewModel::class.java] }

    override fun attachBaseContext(base: android.content.Context) {
        // Popup/Dialog use Activity resources, not only the parent's CompositionLocals.
        // A fixture-only file is written by the host before launch (intent is not attached yet).
        val file = File(base.filesDir, "pdf-ui-config.json")
        val fixture = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        val width = fixture.optInt("width", 360)
        val config =
            Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(fixture.optString("locale", "en")))
                fontScale = fixture.optDouble("font", 1.0).toFloat()
                densityDpi =
                    (base.resources.displayMetrics.widthPixels.toFloat() / width * 160).toInt()
            }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val width = intent.getIntExtra("width", 360)
        val height = intent.getIntExtra("height", 640)
        val font = intent.getFloatExtra("font", 1f)
        val dark = intent.getBooleanExtra("dark", false)
        val dynamic = intent.getBooleanExtra("dynamic", false)
        val rtl = intent.getBooleanExtra("rtl", false)
        val locale = intent.getStringExtra("locale") ?: "en"
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            if (vm.state.value.project == null) {
                val repo = vm.repository
                val source = File.createTempFile("pdf-ui-", ".png", cacheDir)
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.rgb(32, 118, 110))
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val p =
                    repo.import(
                        PdfProject(name = "Studio layout fixture"),
                        listOf(Uri.fromFile(source)),
                        0,
                    ) { _, _ ->
                    }
                source.delete()
                File(filesDir, "pdf-ui-project").writeText(p.id)
                vm.open(p.id)
                while (vm.state.value.busy) delay(20)
                vm.selectImage(0)
            }
            vm.state.collect { current ->
                File(filesDir, "pdf-ui-state.json")
                    .writeText(
                        JSONObject()
                            .put("project", current.project?.id)
                            .put("pages", current.project?.pages?.size ?: 0)
                            .put("name", current.project?.name)
                            .put("busy", current.busy)
                            .put("canUndo", current.canUndo)
                            .toString()
                    )
            }
        }
        setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pixels = with(LocalDensity.current) { maxWidth.toPx() }
                val density = Density(pixels / width, font)
                CompositionLocalProvider(
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides
                        this@PdfUiProbeActivity,
                    androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides
                        this@PdfUiProbeActivity,
                    LocalDensity provides density,
                    LocalLayoutDirection provides
                        if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    UGalleryTheme(darkTheme = dark, dynamicColor = dynamic) {
                        val c = MaterialTheme.colorScheme
                        fun contrast(a: Color, b: Color): Double {
                            val x = a.luminance().toDouble()
                            val y = b.luminance().toDouble()
                            return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
                        }
                        SideEffect {
                            File(filesDir, "pdf-ui-theme.json")
                                .writeText(
                                    JSONObject()
                                        .put("width", width)
                                        .put("height", height)
                                        .put("font", font.toDouble())
                                        .put("dark", dark)
                                        .put("dynamic", dynamic)
                                        .put("sdk", android.os.Build.VERSION.SDK_INT)
                                        .put(
                                            "surfaceVariantText",
                                            contrast(c.surface, c.onSurfaceVariant),
                                        )
                                        .put("surfacePrimaryText", contrast(c.surface, c.primary))
                                        .put("outline", contrast(c.surface, c.outline))
                                        .put("rtl", rtl)
                                        .put("surface", contrast(c.surface, c.onSurface))
                                        .put("primary", contrast(c.primary, c.onPrimary))
                                        .put(
                                            "secondaryContainer",
                                            contrast(c.secondaryContainer, c.onSecondaryContainer),
                                        )
                                        .put(
                                            "surfaceContainer",
                                            contrast(c.surfaceContainer, c.onSurface),
                                        )
                                        .put(
                                            "paperSelectionPrimary",
                                            contrast(Color.White, c.primary),
                                        )
                                        // Phase A additions: top bar surface, the snackbar's
                                        // inverse surface, issue cards (secondary/error
                                        // container) and the selected bottom-bar tool.
                                        .put("topBar", contrast(c.surface, c.onSurface))
                                        .put("snackbar", contrast(c.inverseSurface, c.inverseOnSurface))
                                        .put(
                                            "issueCard",
                                            contrast(c.secondaryContainer, c.onSecondaryContainer),
                                        )
                                        .put(
                                            "issueCardError",
                                            contrast(c.errorContainer, c.onErrorContainer),
                                        )
                                        .put(
                                            "selectedTool",
                                            contrast(c.secondaryContainer, c.onSecondaryContainer),
                                        )
                                        // Phase C additions: the canvas's floating page/zoom
                                        // badges and drag measurement chip (secondaryContainer/
                                        // onSecondaryContainer), the contextual toolbar's own
                                        // container (surfaceContainerHigh/onSurface), and its
                                        // Delete action (errorContainer/onErrorContainer).
                                        .put(
                                            "canvasBadge",
                                            contrast(c.secondaryContainer, c.onSecondaryContainer),
                                        )
                                        .put(
                                            "snapMeasurementChip",
                                            contrast(c.secondaryContainer, c.onSecondaryContainer),
                                        )
                                        .put(
                                            "contextualToolbar",
                                            contrast(c.surfaceContainerHigh, c.onSurface),
                                        )
                                        .put(
                                            "contextualToolbarDelete",
                                            contrast(c.errorContainer, c.onErrorContainer),
                                        )
                                        // Phase D review fix (R9): the panels' ModalBottomSheet
                                        // container carries helper text (onSurfaceVariant — the
                                        // imported-page note, the custom-size preview caption)
                                        // and inline validation errors (error) directly, not
                                        // through a *Container role pair.
                                        .put(
                                            "sheetContainerText",
                                            contrast(c.surfaceContainerLow, c.onSurfaceVariant),
                                        )
                                        .put(
                                            "sheetContainerError",
                                            contrast(c.surfaceContainerLow, c.error),
                                        )
                                        // Phase E addition: the library top bar's export-history
                                        // dot (Badge default role pair, error/onError) and the
                                        // library card's metadata row (surface/onSurfaceVariant is
                                        // already covered by surfaceVariantText above).
                                        .put("historyDot", contrast(c.error, c.onError))
                                        .toString()
                                )
                        }
                        Box(Modifier.width(width.dp).height(height.dp)) {
                            PdfStudioScreen(onExit = { finish() }, vm = vm)
                        }
                    }
                }
            }
        }
    }
}
