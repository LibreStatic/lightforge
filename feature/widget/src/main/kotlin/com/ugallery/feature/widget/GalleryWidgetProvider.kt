package com.ugallery.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import android.view.View
import com.ugallery.core.thumbnail.NativeImageDecoder
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import android.util.Size

/**
 * Home-screen widget that rotates local photos.
 * No network, no private/trash photos, battery-reasonable.
 * Uses RemoteViews with a cached bitmap updated on widget refresh.
 */
class GalleryWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "GalleryWidget"
        const val ACTION_NEXT_PHOTO = "com.ugallery.widget.NEXT_PHOTO"
        const val EXTRA_WIDGET_IDS = "widget_ids"

        fun triggerUpdate(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, GalleryWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componentName)
            if (ids.isNotEmpty()) {
                val intent = Intent(context, GalleryWidgetProvider::class.java)
                    .setAction(ACTION_NEXT_PHOTO)
                    .putExtra(EXTRA_WIDGET_IDS, ids)
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_NEXT_PHOTO) {
            val ids = intent.getIntArrayExtra(EXTRA_WIDGET_IDS)
            val appWidgetManager = AppWidgetManager.getInstance(context)
            if (ids != null) {
                for (id in ids) {
                    updateWidget(context, appWidgetManager, id)
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val widgetSelection = WidgetPhotoSelection(context)
        val photoUri = widgetSelection.getNextPhotoUri()

        val views = RemoteViews(context.packageName, R.layout.widget_gallery)

        if (photoUri != null) {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
            val maxSize = maxOf(minWidth, minHeight).coerceAtLeast(64)

            try {
                val decoder = NativeImageDecoder(context.contentResolver)
                val signal = CancellationSignal()
                val bitmap = decoder.thumbnail(photoUri, Size(maxSize, maxSize), signal)
                views.setImageViewBitmap(R.id.widget_image, bitmap)
                views.setEmptyView(R.id.widget_image, R.id.widget_empty)
                views.setViewVisibility(R.id.widget_empty, View.GONE)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load widget thumbnail", e)
                views.setViewVisibility(R.id.widget_image, View.GONE)
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            }
        } else {
            views.setViewVisibility(R.id.widget_image, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        }

        // Next photo action
        val nextIntent = Intent(context, GalleryWidgetProvider::class.java).apply {
            action = ACTION_NEXT_PHOTO
            putExtra(EXTRA_WIDGET_IDS, intArrayOf(appWidgetId))
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, appWidgetId, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
