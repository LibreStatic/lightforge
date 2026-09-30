package com.librestatic.lightforge.feature.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.ui.graphics.toArgb

/** Complete RemoteViews state: reapply never depends on the host's previous content. */
internal object GalleryWidgetRenderer {
    fun render(context: Context, bitmap: Bitmap?, dynamicColor: Boolean = true): RemoteViews {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        // Keep the same defaults and paired roles as LightforgeTheme, without a Compose host.
        val colors = when {
            dynamicColor && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
            dynamicColor && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
            dark -> darkColorScheme()
            else -> expressiveLightColorScheme()
        }
        return RemoteViews(context.packageName, R.layout.widget_gallery).apply {
            // ImageView.setColorFilter(int) is remotable on API30 too; the rounded shape survives.
            setInt(R.id.widget_background, "setColorFilter", colors.surface.toArgb())
            setTextColor(R.id.widget_empty, colors.onSurface.toArgb())
            setTextViewText(R.id.widget_empty, context.getString(R.string.widget_empty_text))
            setContentDescription(R.id.widget_image, context.getString(R.string.widget_content_description))
            setImageViewBitmap(R.id.widget_image, bitmap)
            setViewVisibility(R.id.widget_image, if (bitmap == null) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.widget_empty, if (bitmap == null) View.VISIBLE else View.GONE)
        }
    }
}
