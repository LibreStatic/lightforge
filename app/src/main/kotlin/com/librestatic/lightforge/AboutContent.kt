package com.librestatic.lightforge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.GalleryContentWidths
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import org.json.JSONObject

private const val LightforgeRepository = "https://github.com/LibreStatic/lightforge"
private const val LightforgeLicenseAsset = "licenses/Lightforge-Apache-2.0.txt"
private const val LightforgeCopyrightAsset = "licenses/Lightforge-Copyright.txt"
private const val ThirdPartyLicenseCatalogAsset = "third_party_licenses.json"

private data class ThirdPartyComponent(
    val group: String,
    val name: String,
    val version: String,
    val licenseName: String,
    val licenseTextAsset: String,
) {
    val coordinate: String get() = "$group:$name:$version"
}

private enum class AboutPage { Overview, AppLicense, Licenses }

@Composable
internal fun AboutContent(
    versionName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenGettingStarted: (() -> Unit)? = null,
    // Opened straight on the license list (from the first-run wizard): back leaves About.
    startInLicenses: Boolean = false,
) {
    var page by rememberSaveable { mutableStateOf(if (startInLicenses) AboutPage.Licenses else AboutPage.Overview) }
    fun leavePage() {
        if (startInLicenses) onBack() else page = AboutPage.Overview
    }

    BackHandler {
        if (page != AboutPage.Overview) leavePage() else onBack()
    }

    when (page) {
        AboutPage.Overview -> AboutOverview(
            versionName = versionName,
            onBack = onBack,
            onOpenLicenses = { page = AboutPage.Licenses },
            onOpenAppLicense = { page = AboutPage.AppLicense },
            onOpenGettingStarted = onOpenGettingStarted,
            modifier = modifier,
        )
        AboutPage.AppLicense -> AppLicense(
            onBack = ::leavePage,
            modifier = modifier,
        )
        AboutPage.Licenses -> LicenseCatalog(
            onBack = ::leavePage,
            modifier = modifier,
        )
    }
}

@Composable
private fun AboutOverview(
    versionName: String,
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenAppLicense: () -> Unit,
    onOpenGettingStarted: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val rowCount = if (onOpenGettingStarted != null) 4 else 3
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val noLinkHandler = stringResource(R.string.about_no_link_handler)

    Column(modifier.fillMaxSize()) {
        GalleryTopAppBar(
            title = stringResource(R.string.about_title),
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.about_back),
        )
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = GalleryContentWidths.Reading)
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(GallerySpacing.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xl),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.about_app_icon),
                modifier = Modifier.size(112.dp).clip(MaterialTheme.shapes.extraLarge),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.about_version, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("about_version"),
                )
            }
            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
            ) {
                Text(
                    text = stringResource(R.string.about_developer_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.about_developer),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("about_developer"),
                )
            }
            Column(Modifier.fillMaxWidth()) {
                AboutActionRow(
                    title = stringResource(R.string.about_source_code),
                    summary = stringResource(R.string.about_source_code_summary),
                    index = 0,
                    count = rowCount,
                    modifier = Modifier.testTag("about_source_code"),
                ) {
                    // openUri throws when no installed app handles ACTION_VIEW for the link.
                    try {
                        uriHandler.openUri(LightforgeRepository)
                    } catch (_: IllegalArgumentException) {
                        android.widget.Toast.makeText(context, noLinkHandler, android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(context, noLinkHandler, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                AboutActionRow(
                    title = stringResource(R.string.about_app_license),
                    summary = stringResource(R.string.about_app_license_summary),
                    index = 1,
                    count = rowCount,
                    modifier = Modifier.testTag("about_app_license"),
                    onClick = onOpenAppLicense,
                )
                AboutActionRow(
                    title = stringResource(R.string.about_licenses),
                    summary = stringResource(R.string.about_licenses_summary),
                    index = 2,
                    count = rowCount,
                    modifier = Modifier.testTag("about_licenses"),
                    onClick = onOpenLicenses,
                )
                if (onOpenGettingStarted != null) {
                    AboutActionRow(
                        title = stringResource(com.librestatic.lightforge.feature.onboarding.R.string.onboarding_reopen_title),
                        summary = stringResource(com.librestatic.lightforge.feature.onboarding.R.string.onboarding_reopen_body),
                        index = 3,
                        count = rowCount,
                        modifier = Modifier.testTag("about_getting_started"),
                        onClick = onOpenGettingStarted,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppLicense(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val copyrightNotice = remember(context) {
        context.assets.open(LightforgeCopyrightAsset).bufferedReader().use { it.readText() }
    }
    val licenseText = remember(context) {
        context.assets.open(LightforgeLicenseAsset).bufferedReader().use { it.readText() }
    }
    val templateNote = stringResource(R.string.about_license_template_note)

    Column(modifier.fillMaxSize().testTag("about_app_license_text")) {
        GalleryTopAppBar(
            title = stringResource(R.string.about_app_license_title),
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.about_back),
        )
        ReadableScrollColumn {
            SelectionContainer {
                Text(
                    text = buildString {
                        append(copyrightNotice.trimEnd())
                        append("\n\n")
                        append(templateNote)
                        append("\n\n")
                        append(licenseText)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(GallerySpacing.Xl),
                )
            }
        }
    }
}

@Composable
private fun AboutActionRow(
    title: String,
    summary: String,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = modifier.fillMaxWidth(),
        leadingContent = {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(GalleryIcons.Info, contentDescription = null)
            }
        },
        supportingContent = { Text(summary) },
        trailingContent = {
            Text("›", style = MaterialTheme.typography.headlineSmall)
        },
    ) { Text(title) }
}

@Composable
private fun LicenseCatalog(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val components = remember(context) {
        runCatching {
            val document = context.assets.open(ThirdPartyLicenseCatalogAsset)
                .bufferedReader()
                .use { JSONObject(it.readText()) }
            val array = document.getJSONArray("components")
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                ThirdPartyComponent(
                    group = item.getString("group"),
                    name = item.getString("name"),
                    version = item.getString("version"),
                    licenseName = item.getString("licenseName"),
                    licenseTextAsset = item.getString("licenseTextAsset"),
                )
            }
        }
    }
    var selected by remember { mutableStateOf<ThirdPartyComponent?>(null) }

    selected?.let { component ->
        DependencyLicense(
            component = component,
            onBack = { selected = null },
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize().testTag("about_license_catalog")) {
        GalleryTopAppBar(
            title = stringResource(R.string.about_licenses_title),
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.about_back),
        )
        components.fold(
            onSuccess = { catalog ->
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                // Cards stay in a readable column; the list still scrolls from the whole width.
                val side = maxOf(GallerySpacing.Xl, (maxWidth - GalleryContentWidths.Reading) / 2)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = side,
                        top = GallerySpacing.Xl,
                        end = side,
                        bottom = GallerySpacing.Xl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
                ) {
                    item {
                        Text(
                            text = pluralStringResource(R.plurals.about_dependencies_count, catalog.size, catalog.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(catalog, key = { it.coordinate }) { component ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = component }
                                .testTag("dependency_${component.group}:${component.name}"),
                        ) {
                            Column(
                                Modifier.padding(GallerySpacing.Lg),
                                verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
                            ) {
                                Text(component.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    component.coordinate,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(component.licenseName, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                }
            },
            onFailure = {
                Text(
                    text = stringResource(R.string.about_license_load_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(GallerySpacing.Xl),
                )
            },
        )
    }
}

/** Scrolls the whole width while keeping long text in a centred, readable column. */
@Composable
private fun ReadableScrollColumn(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = GalleryContentWidths.Reading).fillMaxWidth()) { content() }
    }
}

@Composable
private fun DependencyLicense(
    component: ThirdPartyComponent,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val licenseText = remember(context, component.licenseTextAsset) {
        runCatching {
            context.assets.open(component.licenseTextAsset).bufferedReader().use { it.readText() }
        }
    }

    Column(modifier.fillMaxSize().testTag("dependency_license_text")) {
        GalleryTopAppBar(
            title = component.licenseName,
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.about_back),
        )
        licenseText.fold(
            onSuccess = { text ->
                ReadableScrollColumn {
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .testTag("dependency_license_body")
                                .fillMaxWidth()
                                .padding(GallerySpacing.Xl),
                        )
                    }
                }
            },
            onFailure = {
                Text(
                    text = stringResource(R.string.about_license_load_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(GallerySpacing.Xl),
                )
            },
        )
    }
}
