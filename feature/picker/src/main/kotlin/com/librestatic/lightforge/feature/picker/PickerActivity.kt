package com.librestatic.lightforge.feature.picker

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.preferences.AppearanceSettings
import com.librestatic.lightforge.core.designsystem.LocalShowVideoDuration
import com.librestatic.lightforge.core.designsystem.LocalThumbnailTileSettings
import com.librestatic.lightforge.core.designsystem.ThumbnailTileSettings
import com.librestatic.lightforge.feature.privatealbum.BiometricGate
import com.librestatic.lightforge.feature.settings.LegacyAppLanguage

/**
 * Answers ACTION_GET_CONTENT and ACTION_PICK for photos and videos, so the gallery shows up when
 * another app asks the user to choose media (and in the Files picker's app list).
 */
class PickerActivity : FragmentActivity() {
    private val viewModel: PickerViewModel by viewModels {
        viewModelFactory { initializer { PickerViewModel(application, PickRequest.from(intent)) } }
    }

    private var lockPromptActive = false

    // Biometric only puts an old Fragment on this module's compile classpath; the app ships 1.8.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshAccess() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LegacyAppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED)
        setContent {
            val settings by viewModel.settings.collectAsState()
            LightforgeTheme(settings?.appearance ?: AppearanceSettings()) {
                val thumbnails = settings?.thumbnails
                CompositionLocalProvider(
                    LocalShowVideoDuration provides (thumbnails?.showVideoDuration ?: true),
                    LocalThumbnailTileSettings provides ThumbnailTileSettings(
                        markFavorites = thumbnails?.markFavorites ?: true,
                        showFileType = thumbnails?.showFileType ?: true,
                    ),
                ) {
                    PickerScreen(
                        viewModel = viewModel,
                        onCancel = ::finish,
                        onPick = ::deliver,
                        onRequestAccess = ::requestAccess,
                        onOpenSettings = ::openAppSettings,
                        onUnlock = ::requestUnlock,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccess()
    }

    private fun requestAccess() {
        permissionLauncher.launch(viewModel.permissionRequest().toTypedArray())
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    private fun requestUnlock(title: String, subtitle: String) {
        if (lockPromptActive || !BiometricGate.canAuthenticate(this)) return
        lockPromptActive = true
        BiometricGate.authenticate(
            activity = this,
            title = title,
            subtitle = subtitle,
            onSuccess = { lockPromptActive = false; viewModel.unlock() },
            onError = { lockPromptActive = false },
            onFail = { lockPromptActive = false },
        )
    }

    private fun deliver(items: List<PickerMedia>) {
        if (items.isEmpty()) return
        val uris = items.map(PickerMedia::contentUri)
        val mimeTypes = items.mapNotNull(PickerMedia::mimeType).distinct().ifEmpty { listOf("image/*") }
        val clip = ClipData(ClipDescription(null, mimeTypes.toTypedArray()), ClipData.Item(uris.first()))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val result = Intent().apply {
            data = uris.first()
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
