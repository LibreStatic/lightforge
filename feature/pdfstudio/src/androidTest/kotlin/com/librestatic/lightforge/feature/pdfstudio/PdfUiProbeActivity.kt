package com.librestatic.lightforge.feature.pdfstudio

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
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Phase F item 3 review fix: a trivial, self-contained [PdfMediaSource] so the Media panel can be
 * screenshotted/exercised by the probe/scripts without any real gallery data or permissions. Every
 * item is a small generated solid-color PNG written to this test app's own cache (file:// URIs,
 * like the other import tests), so an insert really goes through the durable intake pipeline
 * without touching any real gallery data or permissions.
 */
private class PdfFakeMediaSource(private val directory: File) : PdfMediaSource {
    override val access =
        kotlinx.coroutines.flow.MutableStateFlow(PdfMediaAccess(PdfMediaAccessState.Full))

    private fun fixture(name: String, key: String): Uri {
        val file = File(directory.apply { mkdirs() }, "$name.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val hue = (key.hashCode().and(0xff)) / 255f * 360f
            bitmap.eraseColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.8f)))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return Uri.fromFile(file)
    }

    private val photos =
        List(4) { n ->
            PdfMediaItem(
                key = "fake-photo-$n",
                uri = fixture("photo-$n", "fake-photo-$n"),
                displayName = "Fake photo $n",
                isDocument = false,
                width = 64,
                height = 64,
            )
        }
    private val documents =
        List(2) { n ->
            PdfMediaItem(
                key = "fake-doc-$n",
                uri = fixture("document-$n", "fake-doc-$n"),
                displayName = "Fake document $n",
                isDocument = true,
                width = 64,
                height = 64,
            )
        }

    override fun items(filter: PdfMediaFilter) =
        kotlinx.coroutines.flow.flowOf(
            when (filter.scope) {
                PdfMediaScope.All -> photos + documents
                PdfMediaScope.Photos -> photos
                PdfMediaScope.Documents -> documents
            }
        )

    override suspend fun thumbnail(item: PdfMediaItem, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val hue = (item.key.hashCode().and(0xff)) / 255f * 360f
        bitmap.eraseColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.8f)))
        return bitmap
    }
}

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
        // Phase F2 item D: an optional synthetic fold, so HingeSplit/Tabletop can be screenshotted
        // on a normal (non-foldable) emulator. Absent `fold` keeps every existing caller/JSON
        // output byte-for-byte identical to before this phase.
        val foldOrientation = intent.getStringExtra("fold")
        val hingeDp = intent.getIntExtra("hingePx", 0)
        val foldPos = intent.getFloatExtra("foldPos", 0.5f)
        // Phase F item 3 review fix: opt-in fake Media panel source, so verify_pdf_adaptive_ui.py
        // can screenshot/exercise the tab without any real gallery data. Absent `fakeMedia` keeps
        // mediaSource null (the tab hidden), matching every existing probe run byte-for-byte.
        val fakeMedia = intent.getBooleanExtra("fakeMedia", false)
        // Phase G2: opt-in second fixture element (a text box), so verify_pdf_adaptive_ui.py's
        // --multi flow has two elements to long-press/tap into a multi-selection. Absent `multi`
        // keeps the fixture at exactly the one image every existing probe run already expects.
        val multiFixture = intent.getBooleanExtra("multi", false)
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
                if (multiFixture) {
                    vm.addText("B")
                    while (vm.state.value.busy) delay(20)
                }
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
                            // Phase F item 3 review fix: lets verify_pdf_adaptive_ui.py's --media
                            // flow assert a Media panel tap actually appended an image (and undo
                            // removed it) without a screenshot diff.
                            .put(
                                "currentPageImages",
                                current.project?.pages?.getOrNull(current.page)?.images?.size ?: 0,
                            )
                            // Phase G1b: lets verify_pdf_adaptive_ui.py's --text flow assert an
                            // Insert -> Text tap actually appended a text (and undo removed it)
                            // without a screenshot diff, mirroring currentPageImages above.
                            .put(
                                "currentPageTexts",
                                current.project?.pages?.getOrNull(current.page)?.texts?.size ?: 0,
                            )
                            .put("selectedTextId", current.selectedTextId)
                            // Phase G2: lets verify_pdf_adaptive_ui.py's --multi flow assert a
                            // long-press + tap actually built a 2-element group (and that Align/
                            // Undo leave a consistent selection) without a screenshot diff,
                            // mirroring currentPageImages/currentPageTexts above.
                            .put("selectedCount", current.selectedIds.size)
                            .put("groupSelected", current.groupSelected)
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
                    LightforgeTheme(darkTheme = dark, dynamicColor = dynamic) {
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
                                        // Phase G2: the multi-select contextual bar (same
                                        // secondaryContainer/onSecondaryContainer pair as the
                                        // canvas badges above, reused for its own "N selected"
                                        // announcement) and its Delete action (errorContainer/
                                        // onErrorContainer, same pair as contextualToolbarDelete).
                                        .put(
                                            "multiSelectBar",
                                            contrast(c.secondaryContainer, c.onSecondaryContainer),
                                        )
                                        .put(
                                            "multiSelectBarDelete",
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
                                        // Phase F2 additions: the expanded-mode rulers and status
                                        // bar (surfaceContainer/onSurfaceVariant), the shortcuts
                                        // sheet's keycap chips (surfaceContainerHigh/onSurface)
                                        // and the pointer-hover tooltip (inverseSurface/
                                        // inverseOnSurface).
                                        .put("ruler", contrast(c.surfaceContainer, c.onSurfaceVariant))
                                        .put("statusBar", contrast(c.surfaceContainer, c.onSurfaceVariant))
                                        .put(
                                            "shortcutKeycap",
                                            contrast(c.surfaceContainerHigh, c.onSurface),
                                        )
                                        .put(
                                            "hoverTooltip",
                                            contrast(c.inverseSurface, c.inverseOnSurface),
                                        )
                                        // Phase F item 3 addition: the Media panel's grid-cell
                                        // placeholder background (no thumbnail decoded yet, or a
                                        // document icon) and its custom-action content color.
                                        .put(
                                            "mediaThumbnail",
                                            contrast(c.surfaceContainerHighest, c.onSurfaceVariant),
                                        )
                                        // Phase G1b additions: the inline text editor's glyph-
                                        // error banner (errorContainer/onErrorContainer) and the
                                        // ink swatch's selection ring, drawn in the fixed
                                        // print-space PdfPaperTokens.GuideOuter/GuideInner pair
                                        // (not a Material role — see PdfPaperTokens) over each
                                        // swatch's own PdfInk fill.
                                        .put(
                                            "textErrorBanner",
                                            contrast(c.errorContainer, c.onErrorContainer),
                                        )
                                        .put(
                                            "inkSwatchSelection",
                                            contrast(Color.White, Color.Black),
                                        )
                                        .toString()
                                )
                        }
                        val foldInfo =
                            foldOrientation?.let { orientation ->
                                val vertical = orientation == "vertical"
                                val hinge = hingeDp.dp
                                if (vertical) {
                                    val center = (width * foldPos).dp
                                    com.librestatic.lightforge.core.designsystem.GalleryFoldInfo(
                                        orientation =
                                            com.librestatic.lightforge.core.designsystem.GalleryFoldOrientation
                                                .Vertical,
                                        isSeparating = true,
                                        left = (center - hinge / 2).coerceAtLeast(0.dp),
                                        top = 0.dp,
                                        right = (center + hinge / 2).coerceAtMost(width.dp),
                                        bottom = height.dp,
                                    )
                                } else {
                                    val center = (height * foldPos).dp
                                    com.librestatic.lightforge.core.designsystem.GalleryFoldInfo(
                                        orientation =
                                            com.librestatic.lightforge.core.designsystem.GalleryFoldOrientation
                                                .Horizontal,
                                        isSeparating = true,
                                        left = 0.dp,
                                        top = (center - hinge / 2).coerceAtLeast(0.dp),
                                        right = width.dp,
                                        bottom = (center + hinge / 2).coerceAtMost(height.dp),
                                    )
                                }
                            }
                        val mediaSource = remember { if (fakeMedia) PdfFakeMediaSource(File(cacheDir, "pdf-ui-fake-media")) else null }
                        Box(Modifier.width(width.dp).height(height.dp)) {
                            PdfStudioScreen(
                                onExit = { finish() },
                                vm = vm,
                                foldInfo = foldInfo,
                                mediaSource = mediaSource,
                            )
                        }
                    }
                }
            }
        }
    }
}
