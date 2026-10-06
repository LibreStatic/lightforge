package com.librestatic.lightforge.feature.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.librestatic.lightforge.core.designsystem.lightforgeColorScheme
import com.librestatic.lightforge.core.preferences.AppearanceSettings

/** Complete RemoteViews state: reapply never depends on the host's previous content. */
internal object GalleryWidgetRenderer {
    fun render(
        context: Context,
        bitmap: Bitmap?,
        dynamicColor: Boolean = true,
        appearance: AppearanceSettings = AppearanceSettings(),
    ): RemoteViews {
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        // The same scheme LightforgeTheme builds, without a Compose host.
        val colors = lightforgeColorScheme(
            context = context,
            palette = appearance.palette,
            darkTheme = appearance.isDark(systemDark),
            pureBlack = appearance.isPureBlack(systemDark),
            dynamicColor = dynamicColor,
        )
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
