package com.librestatic.lightforge.feature.pdfstudio

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/** UUID-owned project, real global module repository/VM; no alternate DB or input implementation. */
class PdfInputProbeActivity : ComponentActivity() {
    private val vm by lazy { ViewModelProvider(this)[PdfStudioViewModel::class.java] }
    val observedState: PdfStudioState get() = vm.state.value
    var viewportBounds = Rect(); private set
    var viewportDensity = 0f; private set
    val keyDispatchResults = mutableListOf<String>()
    val receivedKeys = mutableListOf<String>()
    val receivedPointers = mutableListOf<String>()

    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
            fontScale = 1f
            densityDpi = (base.resources.displayMetrics.widthPixels / 840f * 160).toInt()
        }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val uuid = requireNotNull(intent.getStringExtra("fixtureUuid"))
        require(UUID.fromString(uuid).toString() == uuid)
        val id = "input-$uuid"
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val project = requireNotNull(vm.repository.load(id))
            check(project.name == "Input $uuid")
            vm.open(id)
        }
        setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val physicalWidth = with(LocalDensity.current) { maxWidth.toPx() }
                val physicalHeight = with(LocalDensity.current) { maxHeight.toPx() }
                // Integer density fits the whole 840x640 viewport and avoids subpixel breakpoint rounding.
                val scale = minOf(physicalWidth / 840f, physicalHeight / 640f).toInt().coerceAtLeast(1).toFloat()
                CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
                    LightforgeTheme(darkTheme = false, dynamicColor = false) {
                        Box(Modifier.width(840.dp).height(640.dp).onGloballyPositioned { layout ->
                            val origin = layout.positionInWindow()
                            viewportBounds = Rect(origin.x.toInt(), origin.y.toInt(), origin.x.toInt() + layout.size.width, origin.y.toInt() + layout.size.height)
                            viewportDensity = scale
                        }) {
                            PdfStudioScreen(onExit = { finish() }, vm = vm)
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (receivedKeys.size < 400) receivedKeys += "${event.action}:${event.keyCode}:${event.metaState}:${event.source}"
        val handled = super.dispatchKeyEvent(event)
        if (keyDispatchResults.size < 400) keyDispatchResults += "${event.action}:${event.keyCode}:$handled:${hasWindowFocus()}"
        return handled
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (receivedPointers.size < 100) receivedPointers += "${event.actionMasked}:${event.source}:${event.getToolType(0)}"
        return super.dispatchTouchEvent(event)
    }
}
