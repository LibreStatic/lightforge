package com.librestatic.lightforge.feature.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads current grants every time it is invoked; permission state is never persisted as truth. */
fun interface PermissionGrantReader {
    fun isGranted(permission: String): Boolean
}

enum class PermissionRequestPurpose {
    InitialMediaAccess,
    ReselectMedia,
    LocationMetadata,
}

data class PermissionRequestPlan(
    val purpose: PermissionRequestPurpose,
    val permissions: List<String>,
) {
    init {
        require(permissions.isNotEmpty())
        require(permissions.distinct().size == permissions.size)
    }
}

/**
 * Single source for the API 30–37 runtime permission matrix.
 *
 * The coordinator exposes the most recently observed state for UI consumption, but always
 * recomputes it from PackageManager on foreground instead of treating the cached value as a grant.
 */
class PermissionCoordinator internal constructor(
    private val sdkInt: Int,
    private val grantReader: PermissionGrantReader,
) : DefaultLifecycleObserver {
    constructor(context: Context) : this(
        sdkInt = Build.VERSION.SDK_INT,
        grantReader = PermissionGrantReader { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        },
    )

    private val mutableAccess = MutableStateFlow(evaluateCurrentAccess())
    val access: StateFlow<LibraryAccess> = mutableAccess.asStateFlow()

    override fun onResume(owner: LifecycleOwner) {
        revalidate()
    }

    fun revalidate(): LibraryAccess = evaluateCurrentAccess().also { mutableAccess.value = it }

    fun initialMediaRequest(
        includeImages: Boolean = true,
        includeVideos: Boolean = true,
        includeLocationMetadata: Boolean = false,
    ): PermissionRequestPlan = PermissionRequestPlan(
        purpose = PermissionRequestPurpose.InitialMediaAccess,
        permissions = requestedMediaPermissions(includeImages, includeVideos) +
            locationPermissionIfRequested(includeLocationMetadata),
    )

    fun reselectionRequest(
        includeImages: Boolean = true,
        includeVideos: Boolean = true,
    ): PermissionRequestPlan {
        require(sdkInt >= 34) { "Media reselection requires API 34 or newer" }
        return PermissionRequestPlan(
            purpose = PermissionRequestPurpose.ReselectMedia,
            permissions = requestedMediaPermissions(includeImages, includeVideos),
        )
    }

    fun locationMetadataRequest(): PermissionRequestPlan = PermissionRequestPlan(
        purpose = PermissionRequestPurpose.LocationMetadata,
        permissions = listOf(Manifest.permission.ACCESS_MEDIA_LOCATION),
    )

    private fun evaluateCurrentAccess(): LibraryAccess {
        val location = grantReader.isGranted(Manifest.permission.ACCESS_MEDIA_LOCATION)
        if (sdkInt <= 32) {
            val grant = if (grantReader.isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                GrantLevel.Full
            } else {
                GrantLevel.None
            }
            return LibraryAccess(grant, grant, location)
        }

        val selected = sdkInt >= 34 &&
            grantReader.isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return LibraryAccess(
            images = mediaGrant(Manifest.permission.READ_MEDIA_IMAGES, selected),
            videos = mediaGrant(Manifest.permission.READ_MEDIA_VIDEO, selected),
            unredactedLocation = location,
        )
    }

    private fun mediaGrant(fullPermission: String, selected: Boolean): GrantLevel = when {
        grantReader.isGranted(fullPermission) -> GrantLevel.Full
        selected -> GrantLevel.Selected
        else -> GrantLevel.None
    }

    private fun requestedMediaPermissions(
        includeImages: Boolean,
        includeVideos: Boolean,
    ): List<String> {
        require(includeImages || includeVideos) { "At least one media kind must be requested" }
        if (sdkInt <= 32) return listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        return buildList {
            if (includeImages) add(Manifest.permission.READ_MEDIA_IMAGES)
            if (includeVideos) add(Manifest.permission.READ_MEDIA_VIDEO)
            if (sdkInt >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    }

    private fun locationPermissionIfRequested(include: Boolean): List<String> =
        if (include) listOf(Manifest.permission.ACCESS_MEDIA_LOCATION) else emptyList()
}
