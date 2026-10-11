package com.librestatic.lightforge

import com.librestatic.lightforge.core.model.AdjustKey
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.EditRecipe
import com.librestatic.lightforge.feature.photoeditor.PhotoEditorContentState

/** Pure helpers that derive the photo editor's colour-grade state from a recipe (ADR-045). */

/** True for every operation that belongs to the colour stage, legacy or not. */
internal fun isPhotoColorOperation(operation: EditOperation): Boolean =
    operation is EditOperation.Tone || operation is EditOperation.Filter ||
        operation is EditOperation.Adjust || operation is EditOperation.ColorMix ||
        operation is EditOperation.Look

/**
 * Approximates a legacy [EditOperation.Tone] as [EditOperation.Adjust] values so the sliders start
 * where the old edit left the photo: brightness stays, contrast and saturation are offsets from 1.
 */
internal fun legacyToneToAdjust(tone: EditOperation.Tone): EditOperation.Adjust = EditOperation.Adjust(
    mapOf(
        AdjustKey.Brightness to tone.brightness.coerceIn(-1f, 1f),
        AdjustKey.Contrast to (tone.contrast - 1f).coerceIn(-1f, 1f),
        AdjustKey.Saturation to (tone.saturation - 1f).coerceIn(-1f, 1f),
    ),
)

/** The look a legacy filter name is shown as; "none" and unknown names have none. */
internal fun legacyFilterToLook(filter: EditOperation.Filter): EditOperation.Look? =
    if (filter.name in LegacyFilterLooks) EditOperation.Look("builtin:${filter.name}", 1f) else null

private val LegacyFilterLooks = setOf("natural", "vivid", "mono")

internal fun photoAdjustFor(recipe: EditRecipe): EditOperation.Adjust =
    recipe.operations.filterIsInstance<EditOperation.Adjust>().lastOrNull()
        ?: recipe.operations.filterIsInstance<EditOperation.Tone>().lastOrNull()?.let(::legacyToneToAdjust)
        ?: EditOperation.Adjust(emptyMap())

internal fun photoColorMixFor(recipe: EditRecipe): EditOperation.ColorMix =
    recipe.operations.filterIsInstance<EditOperation.ColorMix>().lastOrNull() ?: EditOperation.ColorMix.Neutral

internal fun photoLookFor(recipe: EditRecipe): EditOperation.Look? =
    recipe.operations.filterIsInstance<EditOperation.Look>().lastOrNull()
        ?: recipe.operations.filterIsInstance<EditOperation.Filter>().lastOrNull()?.let(::legacyFilterToLook)

/**
 * The colour operations to grade with while the sliders are live. A legacy Tone/Filter that the
 * user has not touched is kept as is, so it renders exactly as before; once the Adjust or look
 * differs from its approximation, the new value replaces it.
 */
internal fun liveGradeOperations(
    recipe: EditRecipe,
    adjust: EditOperation.Adjust,
    colorMix: EditOperation.ColorMix,
    look: EditOperation.Look?,
): List<EditOperation> = buildList {
    val tone = recipe.operations.filterIsInstance<EditOperation.Tone>().lastOrNull()
    val hasAdjust = recipe.operations.any { it is EditOperation.Adjust }
    if (tone != null && !hasAdjust && adjust == legacyToneToAdjust(tone)) add(tone)
    else if (!adjust.isNeutral) add(adjust)
    if (!colorMix.isNeutral) add(colorMix)
    val filter = recipe.operations.filterIsInstance<EditOperation.Filter>().lastOrNull()
    val hasLook = recipe.operations.any { it is EditOperation.Look }
    if (filter != null && !hasLook && look != null && look == legacyFilterToLook(filter)) add(filter)
    else if (look != null && look.intensity > 0f) add(look)
}

/** Copies the colour-grade fields of [recipe] into the state; a matching Automatic suggestion stays selected. */
internal fun PhotoEditorContentState.withColorGrade(recipe: EditRecipe): PhotoEditorContentState {
    val adjust = photoAdjustFor(recipe)
    val suggestions = autoEnhancementSuggestions
    val tone = recipe.operations.filterIsInstance<EditOperation.Tone>().lastOrNull()
        ?: suggestions?.takeUnless { adjust.isNeutral }?.let {
            when (adjust) {
                it.enhanceAdjust -> it.enhance
                it.dynamicAdjust -> it.dynamic
                else -> null
            }
        }
        ?: EditOperation.Tone()
    return copy(
        selectedFilter = recipe.operations.asReversed().filterIsInstance<EditOperation.Filter>()
            .firstOrNull()?.name ?: "none",
        tone = tone,
        colorOperations = recipe.operations.filter(::isPhotoColorOperation),
        adjust = adjust,
        colorMix = photoColorMixFor(recipe),
        look = photoLookFor(recipe),
        perspective = recipe.operations.filterIsInstance<EditOperation.Perspective>().lastOrNull(),
    )
}
