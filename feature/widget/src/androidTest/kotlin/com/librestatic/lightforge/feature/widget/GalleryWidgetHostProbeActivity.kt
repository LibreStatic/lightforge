package com.librestatic.lightforge.feature.widget

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import java.util.UUID

/** Own native host; never replaces the installed launcher or manufactures RemoteViews. */
class GalleryWidgetHostProbeActivity : Activity() {
    lateinit var host: AppWidgetHost
        private set
    private lateinit var root: FrameLayout
    var widget: ObservedHostView? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uuid = requireNotNull(intent.getStringExtra("fixtureUuid"))
        require(UUID.fromString(uuid).toString() == uuid)
        require(packageName == "com.librestatic.lightforge.feature.widget.test")
        val hostId = intent.getIntExtra("hostId", 0)
        require(hostId > 0)
        root = FrameLayout(this)
        setContentView(root)
        host = object : AppWidgetHost(this, hostId) {
            override fun onCreateView(context: Context, appWidgetId: Int,
                appWidget: AppWidgetProviderInfo): AppWidgetHostView = ObservedHostView(context)
        }
        host.startListening()
    }

    fun mount(id: Int, info: AppWidgetProviderInfo) {
        check(widget == null)
        widget = (host.createView(this, id, info) as ObservedHostView).also {
            root.addView(it, FrameLayout.LayoutParams(dp(220), dp(200)))
            it.updateAppWidgetSize(Bundle(), 220, 200, 220, 200)
        }
    }

    /** Reconnects the existing system widget after reboot without synthesizing an update. */
    fun mountExistingWithoutResize(id: Int, info: AppWidgetProviderInfo) {
        check(widget == null)
        check(id in host.appWidgetIds)
        widget = (host.createView(this, id, info) as ObservedHostView).also {
            root.addView(it, FrameLayout.LayoutParams(dp(220), dp(200)))
        }
    }

    fun resize() {
        val view = requireNotNull(widget)
        view.layoutParams = FrameLayout.LayoutParams(dp(300), dp(240))
        view.updateAppWidgetSize(Bundle(), 300, 240, 300, 240)
    }

    fun sample(): Sample? {
        val view = widget ?: return null
        val image = view.findViewById<ImageView>(R.id.widget_image) ?: return null
        if (!image.isShown || image.width <= 0 || image.height <= 0 || image.drawable == null) return null
        val container = view.findViewById<View>(R.id.widget_container) ?: return null
        val location = IntArray(2)
        container.getLocationOnScreen(location)
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val pixel = try {
            image.draw(Canvas(bitmap))
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally { bitmap.recycle() }
        val imageRect = Rect(0, 0, image.width, image.height)
        view.offsetDescendantRectToMyCoords(image, imageRect)
        val inside = imageRect.left >= 0 && imageRect.top >= 0 && imageRect.right <= view.width && imageRect.bottom <= view.height
        return Sample(view.deliveries, pixel, view.width, view.height,
            location[0] + container.width / 2, location[1] + container.height / 2,
            container.isClickable && container.isEnabled,
            image.contentDescription?.toString().orEmpty(), inside)
    }

    fun stopHost() {
        host.stopListening()
        root.removeAllViews()
        widget = null
    }

    override fun onDestroy() {
        if (::host.isInitialized) host.stopListening()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    class ObservedHostView(context: Context) : AppWidgetHostView(context) {
        var deliveries = 0
            private set
        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            if (remoteViews != null) deliveries++
        }
    }

    data class Sample(val deliveries: Int, val pixel: Int, val width: Int, val height: Int,
        val centerX: Int, val centerY: Int, val clickable: Boolean, val description: String, val imageInsideHost: Boolean)
}
