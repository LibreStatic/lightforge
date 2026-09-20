package com.ugallery.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import android.util.Size
import com.ugallery.core.thumbnail.NativeImageDecoder

/** Local photo rotation. Receiver callbacks only enqueue; selection, decoding and delivery are worker-owned. */
class GalleryWidgetProvider : AppWidgetProvider() {
    companion object {
        private const val TAG = "GalleryWidget"
        const val ACTION_NEXT_PHOTO = "com.ugallery.widget.NEXT_PHOTO"
        const val EXTRA_WIDGET_IDS = "widget_ids"
        private val updates = WidgetUpdateDispatcher(onFailure = { Log.w(TAG, "Widget update failed", it) })

        fun triggerUpdate(context: Context) {
            // Resolve installed IDs on the worker, not on the caller's possible main thread.
            context.sendBroadcast(Intent(context, GalleryWidgetProvider::class.java).setAction(ACTION_NEXT_PHOTO))
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        enqueue(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_NEXT_PHOTO) enqueue(context, intent.getIntArrayExtra(EXTRA_WIDGET_IDS))
        else super.onReceive(context, intent)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle) {
        enqueue(context, intArrayOf(appWidgetId))
    }

    override fun onDisabled(context: Context) {
        updates.cancelAll()
        super.onDisabled(context)
    }

    private fun enqueue(context: Context, ids: IntArray?) {
        val result = goAsync()
        val application = context.applicationContext
        updates.submit(ids, finish = { result?.finish() }) { requested, ticket ->
            val manager = AppWidgetManager.getInstance(application)
            val component = ComponentName(application, GalleryWidgetProvider::class.java)
            val installed = manager.getAppWidgetIds(component)
            val targets = if (requested == null) installed else installed.filter { it in requested }.toIntArray()
            for (id in targets) {
                if (!ticket.isActive) break
                updateWidget(application, manager, id, ticket)
            }
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int,
        ticket: WidgetUpdateDispatcher.Ticket) {
        if (!ticket.isActive) return
        // The accepted selector remains unchanged. Query/openFD have no cancellation parameter;
        // expiry finishes the broadcast but never starts another worker to replace a blocked one.
        val photoUri = WidgetPhotoSelection(context).getNextPhotoUri()
        if (!ticket.isActive) return
        var bitmap: Bitmap? = null
        if (photoUri != null) {
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
            val maxSize = maxOf(width, height).coerceIn(64, 512)
            if (!ticket.isActive) return
            val signal = CancellationSignal()
            try {
                bitmap = NativeImageDecoder(context.contentResolver).thumbnail(photoUri, Size(maxSize, maxSize), signal)
            } catch (failure: Exception) {
                Log.w(TAG, "Failed to load widget thumbnail", failure)
            } finally {
                // Only the worker may enter remote cancellation. The watchdog never blocks on it.
                if (!ticket.isActive) signal.cancel()
            }
        }
        if (!ticket.isActive) return
        val views = GalleryWidgetRenderer.render(context, bitmap)
        val nextIntent = Intent(context, GalleryWidgetProvider::class.java).apply {
            action = ACTION_NEXT_PHOTO
            putExtra(EXTRA_WIDGET_IDS, intArrayOf(id))
        }
        views.setOnClickPendingIntent(R.id.widget_container, PendingIntent.getBroadcast(context, id, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (!ticket.isActive) return
        // A widget may have been removed while its thumbnail was decoding.
        if (id !in manager.getAppWidgetIds(ComponentName(context, GalleryWidgetProvider::class.java))) return
        ticket.publish { manager.updateAppWidget(id, views) }
    }
}
