package com.ugallery.feature.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real RemoteViews apply/reapply; no launcher, media provider, Room or global settings. */
@RunWith(AndroidJUnit4::class)
class GalleryWidgetRendererDeviceTest {
    @Test fun emptyPhotoEmptyPhotoReapplyKeepsVisibleContentPairedColorsAndLocalizedCopy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val expected = mapOf(
            "en" to ("No photos available" to "Gallery photo"),
            "es" to ("No hay fotos disponibles" to "Foto de la galería"),
            "fr" to ("Aucune photo disponible" to "Photo de la galerie"),
            "pt" to ("Nenhuma foto disponível" to "Foto da galeria"),
            "it" to ("Nessuna foto disponibile" to "Foto della galleria"),
            "de" to ("Keine Fotos verfügbar" to "Galeriefoto"),
        )
        val originalConfiguration = Configuration(base.resources.configuration)
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            failure = runCatching {
                val photo = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                try {
                    for ((language, copy) in expected) for (dark in listOf(false, true)) {
                        val configuration = Configuration(originalConfiguration).apply {
                            setLocales(LocaleList.forLanguageTags(language))
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        }
                        val context = base.createConfigurationContext(configuration)
                        for (dynamic in listOf(false, true)) {
                            verifyCycle(context, photo, dark, dynamic, copy)
                        }
                    }
                } finally { photo.recycle() }
                assertEquals(originalConfiguration, base.resources.configuration)
            }.exceptionOrNull()
        }
        failure?.let { throw it }
    }

    private fun verifyCycle(context: Context, photo: Bitmap, dark: Boolean, dynamic: Boolean, copy: Pair<String, String>) {
        val scheme = when {
            dynamic && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
            dynamic && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
            dark -> darkColorScheme()
            else -> expressiveLightColorScheme()
        }
        val parent = FrameLayout(context)
        val first = GalleryWidgetRenderer.render(context, null, dynamic)
        val view = first.apply(context, parent)
        parent.addView(view)
        val image = view.findViewById<ImageView>(R.id.widget_image)
        val empty = view.findViewById<TextView>(R.id.widget_empty)
        fun assertEmpty() {
            assertEquals(View.GONE, image.visibility)
            assertEquals(View.VISIBLE, empty.visibility)
            assertNull((image.drawable as? BitmapDrawable)?.bitmap) // Android keeps a nullable BitmapDrawable wrapper.
            assertEquals(copy.first, empty.text.toString())
            assertEquals(copy.second, image.contentDescription.toString())
            assertEquals(scheme.onSurface.toArgb(), empty.currentTextColor)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                view.findViewById<View>(R.id.widget_background).importantForAccessibility)
            val pixels = draw(view)
            try {
                val actualSurface = pixels.getPixel(pixels.width / 2, 20)
                assertEquals(scheme.surface.toArgb(), actualSurface)
                assertTrue("Rendered surface/onSurface contrast", ColorUtils.calculateContrast(empty.currentTextColor, actualSurface) >= 4.5)
            } finally { pixels.recycle() }
        }
        fun assertPhoto() {
            assertEquals(View.VISIBLE, image.visibility)
            assertEquals(View.GONE, empty.visibility)
            assertTrue((image.drawable as BitmapDrawable).bitmap.sameAs(photo))
            val pixels = draw(view)
            try { assertEquals(Color.RED, pixels.getPixel(pixels.width / 2, pixels.height / 2)) }
            finally { pixels.recycle() }
        }
        try {
            assertEmpty()
            GalleryWidgetRenderer.render(context, photo, dynamic).reapply(context, view)
            assertPhoto()
            // Provider decode failure and absent selection both intentionally render null.
            GalleryWidgetRenderer.render(context, null, dynamic).reapply(context, view)
            assertEmpty()
            GalleryWidgetRenderer.render(context, photo, dynamic).reapply(context, view)
            assertPhoto()
        } finally {
            image.setImageDrawable(null)
            parent.removeAllViews()
        }
    }

    private fun draw(view: View): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 360, 240)
        return Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
}
