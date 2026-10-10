package com.librestatic.lightforge

import com.librestatic.lightforge.core.editing.video.VideoEditRecipe
import com.librestatic.lightforge.feature.videoeditor.VideoEditorContentState

/**
 * Chronological undo/redo for the video editor.
 *
 * Recipe edits (trim, speed, audio, music, color, crop, slow motion…) are stored here as full
 * snapshots taken *before* each change. Drawing edits keep their own layer stacks in the view
 * model; this class only remembers *when* they happened ([recordAnnotationChange]) so a single
 * Undo walks both kinds of edit in the order the user made them.
 *
 * Because every recipe snapshot is complete, restoring one also restores the annotations it held.
 * That stays consistent with the separate annotation stacks precisely because undo is strictly
 * chronological: any later drawing edit has already been undone before an older recipe snapshot
 * is restored.
 */
internal class VideoEditHistory(private val limit: Int = DefaultLimit) {
    data class Snapshot(val recipe: VideoEditRecipe, val content: VideoEditorContentState)

    sealed interface Step {
        /** Apply [snapshot] as the new editor state. */
        data class Restore(val snapshot: Snapshot) : Step

        /** The view model must undo/redo one step of its annotation stacks. */
        data object Annotation : Step
    }

    private enum class Kind { Recipe, Annotation }

    private val recipeUndo = ArrayDeque<Snapshot>()
    private val recipeRedo = ArrayDeque<Snapshot>()
    private val undoOrder = ArrayDeque<Kind>()
    private val redoOrder = ArrayDeque<Kind>()
    private var lastChangeKey: String? = null
    private var lastChangeAtMillis = 0L

    val canUndo: Boolean get() = undoOrder.isNotEmpty()
    val canRedo: Boolean get() = redoOrder.isNotEmpty()

    /**
     * Records that the recipe is about to differ from [previous]. Rapid changes of the same [key]
     * (a slider drag) are merged so one Undo reverts the whole gesture.
     */
    fun recordRecipeChange(previous: Snapshot, key: String, nowMillis: Long) {
        val merge = undoOrder.lastOrNull() == Kind.Recipe &&
            lastChangeKey == key &&
            nowMillis - lastChangeAtMillis <= CoalesceWindowMillis
        lastChangeAtMillis = nowMillis
        lastChangeKey = key
        if (!merge) {
            recipeUndo.addLast(previous)
            undoOrder.addLast(Kind.Recipe)
            if (recipeUndo.size > limit) {
                recipeUndo.removeFirst()
                undoOrder.remove(Kind.Recipe)
            }
        }
        clearRedo()
    }

    fun recordAnnotationChange() {
        lastChangeKey = null
        undoOrder.addLast(Kind.Annotation)
        clearRedo()
    }

    /** Pops the newest edit. [current] is what Redo will restore. Null when nothing can be undone. */
    fun undo(current: Snapshot): Step? {
        lastChangeKey = null
        return when (undoOrder.removeLastOrNull()) {
            Kind.Recipe -> {
                val target = recipeUndo.removeLastOrNull() ?: return null
                recipeRedo.addLast(current)
                redoOrder.addLast(Kind.Recipe)
                Step.Restore(target)
            }
            Kind.Annotation -> {
                redoOrder.addLast(Kind.Annotation)
                Step.Annotation
            }
            null -> null
        }
    }

    fun redo(current: Snapshot): Step? {
        lastChangeKey = null
        return when (redoOrder.removeLastOrNull()) {
            Kind.Recipe -> {
                val target = recipeRedo.removeLastOrNull() ?: return null
                recipeUndo.addLast(current)
                undoOrder.addLast(Kind.Recipe)
                Step.Restore(target)
            }
            Kind.Annotation -> {
                undoOrder.addLast(Kind.Annotation)
                Step.Annotation
            }
            null -> null
        }
    }

    /** After a process restore only the annotation stacks survive; mirror them in the order. */
    fun seedAnnotationOrder(undoCount: Int, redoCount: Int) {
        repeat(undoCount) { undoOrder.addLast(Kind.Annotation) }
        repeat(redoCount) { redoOrder.addLast(Kind.Annotation) }
    }

    fun clear() {
        recipeUndo.clear()
        recipeRedo.clear()
        undoOrder.clear()
        redoOrder.clear()
        lastChangeKey = null
    }

    private fun clearRedo() {
        recipeRedo.clear()
        redoOrder.clear()
    }

    companion object {
        const val DefaultLimit = 100
        const val CoalesceWindowMillis = 800L

        /** Names the part of the recipe that changed, so a drag of one control merges into one step. */
        fun changeKey(old: VideoEditRecipe, new: VideoEditRecipe): String = buildList {
            if (old.startMillis != new.startMillis || old.endMillis != new.endMillis) add("trim")
            if (old.speed != new.speed) add("speed")
            if (old.interpolateSlowMotion != new.interpolateSlowMotion) add("interpolate")
            if (old.originalAudioVolume != new.originalAudioVolume) add("audioVolume")
            if (old.musicUri != new.musicUri) add("music")
            if (old.musicVolume != new.musicVolume) add("musicVolume")
            if (old.geometry != new.geometry) add("geometry")
            if (old.colorGrade != new.colorGrade) add("grade")
            if (old.outputQuality != new.outputQuality) add("quality")
            if (old.dynamicRange != new.dynamicRange) add("range")
            if (old.slowMotionSegments != new.slowMotionSegments) add("slowMotion")
            if (old.output != new.output) add("output")
        }.joinToString(",")

        /** Copies the recipe-derived fields of [from] onto [this], keeping transient session state. */
        fun VideoEditorContentState.withRecipeFieldsFrom(from: VideoEditorContentState): VideoEditorContentState {
            val start = from.trimStartMillis
            val end = from.trimEndMillis
            return copy(
                trimStartMillis = start,
                trimEndMillis = end,
                currentMillis = currentMillis.takeIf { end > start && it in start until end } ?: start,
                speed = from.speed,
                interpolateSlowMotion = from.interpolateSlowMotion,
                originalAudioVolume = from.originalAudioVolume,
                selectedMusicName = from.selectedMusicName,
                selectedMusicUri = from.selectedMusicUri,
                musicVolume = from.musicVolume,
                colorGrade = from.colorGrade,
                activeCustomLut = from.activeCustomLut,
                outputQuality = from.outputQuality,
                dynamicRange = from.dynamicRange,
                geometry = from.geometry,
                slowMotionSegments = from.slowMotionSegments,
                selectedSlowMotionSegmentId = from.selectedSlowMotionSegmentId,
                slowMotionMarkInMillis = null,
                annotations = from.annotations,
                output = from.output,
                selectedAnnotationId = from.selectedAnnotationId,
                annotationTrackingProgress = null,
                annotationTrackingCorrectionMillis = null,
                statusMessage = null,
            )
        }
    }
}
