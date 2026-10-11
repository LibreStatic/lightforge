package com.librestatic.lightforge.feature.photoeditor

import com.librestatic.lightforge.core.model.AdjustKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoEditorToolsTest {
    // Spanish names stand in for localized resources; the filter only sees the label function.
    private val names = mapOf(
        R.string.photo_editor_brightness to "Brillo",
        R.string.photo_editor_contrast to "Contraste",
        R.string.photo_editor_adjust_white_point to "Punto blanco",
        R.string.photo_editor_adjust_highlights to "Zonas brillantes",
        R.string.photo_editor_shadows to "Sombras",
        R.string.photo_editor_adjust_black_point to "Punto negro",
        R.string.photo_editor_adjust_tone to "Tono",
        R.string.photo_editor_saturation to "Saturación",
        R.string.photo_editor_adjust_warmth to "Calidez",
        R.string.photo_editor_tint to "Matiz",
        R.string.photo_editor_adjust_skin_tone to "Tono de piel",
        R.string.photo_editor_adjust_blue_tone to "Tono azul",
        R.string.photo_editor_adjust_vignette to "Viñeta",
        R.string.photo_editor_adjust_color_mix to "Mezcla de color",
        R.string.photo_editor_crop to "Recortar",
        R.string.photo_editor_straighten to "Enderezar",
        R.string.photo_editor_rotate to "Rotar",
        R.string.photo_editor_perspective to "Perspectiva",
        R.string.photo_editor_flip to "Voltear",
        R.string.photo_editor_automatic to "Automático",
        R.string.photo_editor_object_eraser to "Borrador de objetos",
        R.string.photo_editor_subject_clip to "Recorte de sujeto",
        R.string.photo_editor_filters to "Filtros",
        R.string.photo_editor_looks_mine to "Mis filtros",
        R.string.photo_editor_open_with to "Abrir con",
    )
    private val entries = photoToolEntries(isRaw = false, canOpenWith = true)

    private fun search(query: String) = filterToolEntries(entries, query) { names.getValue(it.labelRes) }
        .map { names.getValue(it.labelRes) }

    @Test
    fun everyToolIsSearchableAndLabelled() {
        assertEquals(entries.size, entries.map { it.destination }.toSet().size)
        entries.forEach { assertTrue(names.containsKey(it.labelRes)) }
        assertEquals(AdjustKey.entries.size + 1, entries.count { it.destination.adjustTile != null })
    }

    @Test
    fun blankQueryKeepsEveryToolInOrder() {
        assertEquals(entries, filterToolEntries(entries, "  ") { names.getValue(it.labelRes) })
    }

    @Test
    fun prefixMatchesAnyWordAndIgnoresCaseAndAccents() {
        assertEquals(listOf("Saturación"), search("satur"))
        assertEquals(listOf("Saturación"), search("SATURACION"))
        assertEquals(listOf("Zonas brillantes"), search("brill").filter { it == "Zonas brillantes" })
        assertTrue(search("brill").containsAll(listOf("Brillo", "Zonas brillantes")))
        assertEquals(listOf("Viñeta"), search("vineta"))
    }

    @Test
    fun sharedWordFindsAllRelatedTools() {
        assertEquals(listOf("Tono", "Tono de piel", "Tono azul"), search("tono"))
        assertEquals(listOf("Filtros", "Mis filtros"), search("filtro"))
    }

    @Test
    fun everyQueryWordMustMatch() {
        assertEquals(listOf("Punto blanco"), search("punto bla"))
        assertEquals(listOf("Tono de piel"), search("piel tono"))
    }

    @Test
    fun shortQueriesDoNotMatchInsideWords() {
        assertFalse(search("on").contains("Contraste"))
        assertTrue(search("tra").contains("Contraste")) // 3+ characters may match mid-word
    }

    @Test
    fun noMatchGivesAnEmptyList() {
        assertTrue(search("zzz").isEmpty())
    }

    @Test
    fun resultsCarryTheDestinationToNavigateTo() {
        val tint = filterToolEntries(entries, "matiz") { names.getValue(it.labelRes) }.single().destination
        assertEquals(PhotoToolDestination(PhotoEditorTool.Adjust, adjustTile = PhotoAdjustTile.Tint), tint)
        val mine = filterToolEntries(entries, "mis fil") { names.getValue(it.labelRes) }.single().destination
        assertEquals(PhotoToolDestination(PhotoEditorTool.Filters, looksTab = PhotoLooksTab.Mine), mine)
        val perspective = filterToolEntries(entries, "perspec") { names.getValue(it.labelRes) }.single().destination
        assertEquals(PhotoToolDestination(PhotoEditorTool.Crop, cropTool = PhotoCropTool.Perspective), perspective)
    }

    @Test
    fun rawPhotosOnlyOfferRawCropAndExport() {
        val raw = photoToolEntries(isRaw = true, canOpenWith = true)
        assertEquals(
            setOf(PhotoEditorTool.Raw, PhotoEditorTool.Crop, PhotoEditorTool.Export),
            raw.map { it.destination.tool }.toSet(),
        )
        assertEquals(listOf(PhotoEditorTab.Raw, PhotoEditorTab.Crop, PhotoEditorTab.Export), photoEditorTabs(true, true))
    }

    @Test
    fun moreTabAndOpenWithOnlyExistWhenTheHostCanOpenAnotherEditor() {
        assertFalse(photoEditorTabs(false, canOpenWith = false).contains(PhotoEditorTab.More))
        assertTrue(photoEditorTabs(false, canOpenWith = true).contains(PhotoEditorTab.More))
        assertFalse(photoToolEntries(false, canOpenWith = false).any { it.destination.tool == PhotoEditorTool.More })
    }

    @Test
    fun toolsMapToTheirTabs() {
        assertEquals(PhotoEditorTab.Actions, PhotoEditorTool.ObjectEraser.tab())
        assertEquals(PhotoEditorTab.Actions, PhotoEditorTool.SubjectClip.tab())
        assertEquals(PhotoEditorTool.ObjectEraser, PhotoEditorTab.Actions.firstTool())
        PhotoEditorTab.entries.forEach { assertEquals(it, it.firstTool().tab()) }
    }
}
