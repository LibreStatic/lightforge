package com.librestatic.lightforge.feature.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.rememberGalleryReducedMotion
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.feature.permissions.PermissionCoordinator
import kotlinx.coroutines.launch

const val FeaturePageCount = 4
private const val SourceRepositoryUrl = "https://github.com/LibreStatic/lightforge"

/**
 * First-run "Getting started" wizard. [step] is hoisted so the host can keep it
 * across a detour (for example the license list) and process death.
 *
 * [onFinish] receives the analysis choice when the user completed the wizard, or null when they
 * skipped it; a skip must not change any existing analysis decision.
 */
@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    onStepChange: (OnboardingStep) -> Unit,
    access: LibraryAccess,
    permissions: PermissionCoordinator,
    analysis: Set<OnboardingAnalysisOption>,
    onAnalysisChange: (Set<OnboardingAnalysisOption>) -> Unit,
    versionName: String,
    onOpenLicenses: () -> Unit,
    onPermissionResult: () -> Unit,
    onFinish: (analysis: Set<OnboardingAnalysisOption>?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberGalleryReducedMotion()
    val pager = rememberPagerState { FeaturePageCount }
    val scope = rememberCoroutineScope()
    val steps = OnboardingStep.entries
    val last = step == steps.last()

    fun back() {
        if (step == OnboardingStep.Features && pager.currentPage > 0) {
            scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
        } else if (step.ordinal > 0) onStepChange(steps[step.ordinal - 1])
    }
    fun next() {
        when {
            step == OnboardingStep.Features && pager.currentPage < FeaturePageCount - 1 ->
                scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            last -> onFinish(analysis)
            else -> onStepChange(steps[step.ordinal + 1])
        }
    }
    BackHandler(enabled = step.ordinal > 0 || pager.currentPage > 0, onBack = ::back)

    val stepLabel = stringResource(R.string.onboarding_step_of, step.ordinal + 1, steps.size)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            OnboardingTopBar(
                step = step,
                stepLabel = stepLabel,
                onSkip = { onFinish(null) },
            )
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f).fillMaxWidth().semantics { paneTitle = stepLabel },
                contentKey = { it.name },
                transitionSpec = {
                    if (reducedMotion) {
                        fadeIn() togetherWith fadeOut()
                    } else {
                        val forward = targetState.ordinal > initialState.ordinal
                        val sign = if (forward) 1 else -1
                        (slideInHorizontally { width -> sign * width / 5 } + fadeIn()) togetherWith
                            (slideOutHorizontally { width -> -sign * width / 5 } + fadeOut())
                    }
                },
                label = "onboarding-step",
            ) { current ->
                OnboardingPage(current) {
                    when (current) {
                        OnboardingStep.Welcome -> WelcomeStep()
                        OnboardingStep.Features -> FeaturesStep(pager, reducedMotion)
                        OnboardingStep.Permissions -> PermissionsStep(access, permissions, onPermissionResult)
                        OnboardingStep.Analysis -> AnalysisStep(analysis, onAnalysisChange)
                        OnboardingStep.OpenSource -> OpenSourceStep(versionName, onOpenLicenses)
                    }
                }
            }
            if (step == OnboardingStep.Features) FeaturePageIndicator(pager)
            OnboardingBottomBar(
                showBack = step.ordinal > 0,
                primaryLabel = stringResource(
                    when (step) {
                        OnboardingStep.Welcome -> R.string.onboarding_get_started
                        OnboardingStep.OpenSource -> R.string.onboarding_start_exploring
                        else -> R.string.onboarding_next
                    },
                ),
                onBack = ::back,
                onPrimary = ::next,
            )
        }
    }
}

@Composable
private fun OnboardingTopBar(step: OnboardingStep, stepLabel: String, onSkip: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = GallerySpacing.Xl, end = GallerySpacing.Sm, top = GallerySpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = stepLabel },
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
        ) {
            OnboardingStep.entries.forEach { entry ->
                val active = entry.ordinal <= step.ordinal
                val width by animateFloatAsState(if (entry == step) 28f else 12f, label = "progress-width")
                Box(
                    Modifier
                        .height(6.dp)
                        .width(width.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                )
            }
        }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding-skip"),
        ) { Text(stringResource(R.string.onboarding_skip), style = MaterialTheme.typography.labelLarge) }
    }
}

/** Pinned above the bottom bar so the page position stays in one place regardless of copy length. */
@Composable
private fun FeaturePageIndicator(pager: androidx.compose.foundation.pager.PagerState) {
    Column(Modifier.fillMaxWidth().padding(top = GallerySpacing.Sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(FeaturePageCount) { index ->
                val selected = pager.currentPage == index
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (selected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
        Text(
            stringResource(R.string.onboarding_feature_page, pager.currentPage + 1, FeaturePageCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = GallerySpacing.Xs),
        )
    }
}

@Composable
private fun OnboardingBottomBar(
    showBack: Boolean,
    primaryLabel: String,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding-back")) {
                Text(stringResource(R.string.onboarding_back))
            }
        }
        Spacer(Modifier.weight(1f))
        GalleryExpressiveButton(
            onClick = onPrimary,
            modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 160.dp).testTag("onboarding-primary"),
        ) { Text(primaryLabel, style = MaterialTheme.typography.titleMedium) }
    }
}

/** Scrollable, width-capped page body so large font scales and tablets both stay readable. */
@Composable
private fun OnboardingPage(step: OnboardingStep, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GallerySpacing.Xl, vertical = GallerySpacing.Lg)
                .testTag("onboarding-step-${step.name}"),
        ) { content() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OnboardingHero(
    icon: ImageVector,
    shapeIndex: Int,
    container: Color,
    content: Color,
    height: Int = 200,
) {
    val shapes = listOf(
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Arch,
        MaterialShapes.SoftBurst,
        MaterialShapes.Flower,
    )
    val reducedMotion = rememberGalleryReducedMotion()
    val rotation by animateFloatAsState(
        targetValue = if (reducedMotion) 0f else shapeIndex * 24f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "hero-rotation",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size((height * 0.72f).dp)
                .rotate(rotation)
                .clip(shapes[shapeIndex % shapes.size].toShape())
                .background(container),
        )
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size((height * 0.28f).dp))
    }
}

@Composable
internal fun OnboardingHeadline(title: String, body: String) {
    Spacer(Modifier.height(GallerySpacing.Xxl))
    Text(
        title,
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(GallerySpacing.Md))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WelcomeStep() {
    OnboardingHero(
        icon = GalleryIcons.PhotoLibrary,
        shapeIndex = 0,
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        height = 240,
    )
    OnboardingHeadline(
        stringResource(R.string.onboarding_welcome_title),
        stringResource(R.string.onboarding_welcome_body),
    )
    Spacer(Modifier.height(GallerySpacing.Lg))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        listOf(
            GalleryIcons.CheckCircle to R.string.onboarding_chip_offline,
            GalleryIcons.Lock to R.string.onboarding_chip_no_account,
            GalleryIcons.AutoAwesome to R.string.onboarding_chip_on_device,
        ).forEach { (icon, label) ->
            StaticChip(icon, stringResource(label))
        }
    }
}

@Composable
private fun FeaturesStep(pager: androidx.compose.foundation.pager.PagerState, reducedMotion: Boolean) {
    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { page ->
        Column(Modifier.fillMaxWidth()) {
            FeatureSample(page, reducedMotion)
            val (title, body) = when (page) {
                0 -> R.string.onboarding_feature_search_title to R.string.onboarding_feature_search_body
                1 -> R.string.onboarding_feature_timeline_title to R.string.onboarding_feature_timeline_body
                2 -> R.string.onboarding_feature_edit_title to R.string.onboarding_feature_edit_body
                else -> R.string.onboarding_feature_more_title to R.string.onboarding_feature_more_body
            }
            OnboardingHeadline(stringResource(title), stringResource(body))
        }
    }
}

@Composable
private fun PermissionsStep(
    access: LibraryAccess,
    permissions: PermissionCoordinator,
    onPermissionResult: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val asked = remember(context) { OnboardingAskLog(context) }
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissions.revalidate()
        onPermissionResult()
        tick++
    }
    fun rationale(permission: String) = activity?.shouldShowRequestPermissionRationale(permission) == true
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun request(list: List<String>) {
        asked.mark(list)
        launcher.launch(list.toTypedArray())
    }
    fun openSystemSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    val mediaPlan = remember(permissions) { permissions.initialMediaRequest() }
    val locationPermission = Manifest.permission.ACCESS_MEDIA_LOCATION
    val notificationsAvailable = Build.VERSION.SDK_INT >= 33
    val notificationPermission = "android.permission.POST_NOTIFICATIONS"
    // Grants and rationale flags are read live; tick and access re-read them after each result.
    val mediaStatus = remember(tick, access) {
        OnboardingPermissionPolicy.media(
            access,
            asked = asked.any(mediaPlan.permissions),
            showRationale = mediaPlan.permissions.any(::rationale),
        )
    }
    val locationStatus = remember(tick, access) {
        OnboardingPermissionPolicy.single(
            granted = access.unredactedLocation || granted(locationPermission),
            asked = asked.any(listOf(locationPermission)),
            showRationale = rationale(locationPermission),
        )
    }
    val notificationStatus = remember(tick, access) {
        OnboardingPermissionPolicy.single(
            granted = notificationsAvailable && granted(notificationPermission),
            asked = asked.any(listOf(notificationPermission)),
            showRationale = notificationsAvailable && rationale(notificationPermission),
            available = notificationsAvailable,
        )
    }

    OnboardingHero(
        icon = GalleryIcons.Photo,
        shapeIndex = 2,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        height = 160,
    )
    OnboardingHeadline(
        stringResource(R.string.onboarding_permissions_title),
        stringResource(R.string.onboarding_permissions_body),
    )
    Spacer(Modifier.height(GallerySpacing.Lg))
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        PermissionRow(
            icon = GalleryIcons.PhotoLibrary,
            title = stringResource(R.string.onboarding_permission_media_title),
            body = stringResource(R.string.onboarding_permission_media_body),
            status = mediaStatus,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            tag = "media",
            onRequest = {
                if (mediaStatus == OnboardingPermissionStatus.Partial && Build.VERSION.SDK_INT >= 34) {
                    request(permissions.reselectionRequest().permissions)
                } else request(mediaPlan.permissions)
            },
            onOpenSettings = ::openSystemSettings,
        )
        PermissionRow(
            icon = GalleryIcons.Place,
            title = stringResource(R.string.onboarding_permission_location_title),
            body = stringResource(R.string.onboarding_permission_location_body),
            status = locationStatus,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            tag = "location",
            enabled = mediaStatus == OnboardingPermissionStatus.Granted || mediaStatus == OnboardingPermissionStatus.Partial,
            disabledHint = stringResource(R.string.onboarding_permission_location_needs_media),
            grantedNote = stringResource(R.string.onboarding_permission_location_auto_granted),
            onRequest = { request(permissions.locationMetadataRequest().permissions) },
            onOpenSettings = ::openSystemSettings,
        )
        if (notificationsAvailable) {
            PermissionRow(
                icon = GalleryIcons.Notifications,
                title = stringResource(R.string.onboarding_permission_notifications_title),
                body = stringResource(R.string.onboarding_permission_notifications_body),
                status = notificationStatus,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                tag = "notifications",
                onRequest = { request(listOf(notificationPermission)) },
                onOpenSettings = ::openSystemSettings,
            )
        }
    }
    Spacer(Modifier.height(GallerySpacing.Md))
    Text(
        stringResource(R.string.onboarding_permissions_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OpenSourceStep(versionName: String, onOpenLicenses: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    OnboardingHero(
        icon = GalleryIcons.Heart,
        shapeIndex = 4,
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        height = 160,
    )
    OnboardingHeadline(
        stringResource(R.string.onboarding_open_source_title),
        stringResource(R.string.onboarding_open_source_body),
    )
    Spacer(Modifier.height(GallerySpacing.Lg))
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        LinkRow(
            icon = GalleryIcons.Link,
            title = stringResource(R.string.onboarding_source_title),
            body = stringResource(R.string.onboarding_source_body),
            tag = "source",
            onClick = { runCatching { uriHandler.openUri(SourceRepositoryUrl) } },
        )
        LinkRow(
            icon = GalleryIcons.Receipt,
            title = stringResource(R.string.onboarding_licenses_title),
            body = stringResource(R.string.onboarding_licenses_body),
            tag = "licenses",
            onClick = onOpenLicenses,
        )
        LinkRow(
            icon = GalleryIcons.User,
            title = stringResource(R.string.onboarding_credits_title),
            body = stringResource(R.string.onboarding_credits_body),
            tag = "credits",
            onClick = null,
        )
    }
    Spacer(Modifier.height(GallerySpacing.Md))
    Text(
        stringResource(R.string.onboarding_version, versionName),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Remembers which runtime permissions this install already requested, to tell "never asked" from "blocked". */
internal class OnboardingAskLog(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("onboarding-permission-asks", Context.MODE_PRIVATE)
    fun any(permissions: List<String>): Boolean = permissions.any { preferences.getBoolean(it, false) }
    fun mark(permissions: List<String>) {
        preferences.edit().apply { permissions.forEach { putBoolean(it, true) } }.apply()
    }
}
