package com.librestatic.lightforge.feature.collections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.librestatic.lightforge.core.database.MomentEntity
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Presentation only: a location emission or locale change never mutates a user's saved story. */
@Composable
fun momentDisplayTitle(moment: MomentEntity, placeLabels: ((String) -> Flow<String?>)? = null): String {
    moment.title?.takeIf { it.isNotBlank() }?.let { return it }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val places = remember(moment.momentId, placeLabels) { placeLabels?.invoke(moment.momentId) ?: flowOf(null) }
    val place by places.collectAsState(initial = null)
    return momentAutomaticTitle(context, moment.startMillis, moment.endMillis,
        ZoneId.systemDefault(), locale, placeLabel = place)
}
