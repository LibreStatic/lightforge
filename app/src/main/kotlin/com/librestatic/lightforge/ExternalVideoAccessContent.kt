package com.librestatic.lightforge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveIconButton
import com.librestatic.lightforge.core.designsystem.GalleryIcons

/** Presentation only: access is revalidated by the caller after an explicit retry. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ExternalVideoAccessContent(
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    checking: Boolean = false,
    sourceChanged: Boolean = false,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("external-video-access-blocked")
            .semantics { testTagsAsResourceId = true },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.external_video_access_title),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    GalleryExpressiveIconButton(onClick = onBack,
                        modifier = Modifier.testTag("external-video-access-back")) {
                        Icon(GalleryIcons.Back, contentDescription = stringResource(R.string.external_video_access_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(if (sourceChanged) R.string.external_video_access_source_changed
                else R.string.external_video_access_body),
                style = MaterialTheme.typography.bodyLarge)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.external_video_access_draft),
                    modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (checking) {
                val checkingDescription = stringResource(R.string.external_video_access_checking)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = checkingDescription })
            }
            Button(onClick = onRetry, enabled = !checking,
                modifier = Modifier.testTag("external-video-access-retry")) {
                Text(stringResource(if (checking) R.string.external_video_access_checking
                    else R.string.external_video_access_retry))
            }
        }
    }
}
