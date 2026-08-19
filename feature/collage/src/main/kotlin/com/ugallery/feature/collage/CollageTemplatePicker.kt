package com.ugallery.feature.collage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
        columns = GridCells.Fixed(3),
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
                text = template.displayName,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = "${template.slotCount} photos",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
