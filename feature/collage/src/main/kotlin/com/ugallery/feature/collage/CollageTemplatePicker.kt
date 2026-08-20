package com.ugallery.feature.collage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

/**
 * Compose UI for selecting collage template and configuring output.
 */
@Composable
fun CollageTemplatePicker(
    templates: List<CollageTemplate> = CollageTemplate.entries.toList(),
    selectedTemplate: CollageTemplate,
    onTemplateSelected: (CollageTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(templates) { template ->
            TemplateCard(
                template = template,
                isSelected = template == selectedTemplate,
                onClick = { onTemplateSelected(template) },
            )
        }
    }
}

@Composable
private fun TemplateCard(
    template: CollageTemplate,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(template.labelResource()),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = stringResource(R.string.collage_photo_count, template.slotCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun CollageTemplate.labelResource(): Int = when (this) {
    CollageTemplate.GRID_2 -> R.string.collage_grid_2
    CollageTemplate.GRID_3 -> R.string.collage_grid_3
    CollageTemplate.GRID_4 -> R.string.collage_grid_4
    CollageTemplate.STACK_3 -> R.string.collage_stack_3
    CollageTemplate.STRIP_3 -> R.string.collage_strip_3
    CollageTemplate.POLAROID_3 -> R.string.collage_polaroid_3
}
