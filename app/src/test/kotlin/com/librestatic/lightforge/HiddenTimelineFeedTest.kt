package com.librestatic.lightforge

import com.librestatic.lightforge.core.preferences.FolderSelectionMode
import com.librestatic.lightforge.core.preferences.FolderSelectionTarget
import com.librestatic.lightforge.core.preferences.LibraryFilter
import com.librestatic.lightforge.core.preferences.LibrarySettings
import com.librestatic.lightforge.core.preferences.withFilterToggled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenTimelineFeedTest {
    private fun file(path: String, name: String = "a.jpg", mime: String = "image/jpeg", video: Boolean = false) =
        HiddenFacts("external_primary", path, name, mime, video)

    private fun pick(rows: List<HiddenFacts>, showAll: Boolean, rules: Set<String> = emptySet(), library: LibrarySettings = LibrarySettings()) =
        hiddenRowsForTimeline(rows, { it }, showAll, rules, library)

    private val private = file("Pictures/Private/")
    private val deep = file("Pictures/Private/Trip/", "b.jpg")
    private val other = file("DCIM/Secret/", "c.jpg")

    @Test fun `nothing is listed unless hidden items are shown or a folder has the rule`() {
        assertTrue(pick(listOf(private, other), showAll = false).isEmpty())
    }

    @Test fun `showing hidden items lists every hidden file`() {
        assertEquals(listOf(private, deep, other), pick(listOf(private, deep, other), showAll = true))
    }

    @Test fun `a folder rule lists that folder and everything below it only`() {
        val rules = setOf("external_primary|Pictures/Private/")
        assertEquals(listOf(private, deep), pick(listOf(private, deep, other), showAll = false, rules = rules))
    }

    @Test fun `type chips narrow the hidden files like any other item`() {
        val video = file("Pictures/Private/", "v.mp4", "video/mp4", video = true)
        val gif = file("Pictures/Private/", "g.gif", "image/gif")
        val raw = file("Pictures/Private/", "r.dng", "image/x-adobe-dng")
        val all = listOf(private, video, gif, raw)
        val videos = LibrarySettings().withFilterToggled(LibraryFilter.Videos)
        assertEquals(listOf(video), pick(all, true, library = videos))
        val videosAndGifs = videos.withFilterToggled(LibraryFilter.Animated)
        assertEquals(listOf(video, gif), pick(all, true, library = videosAndGifs))
        assertEquals(listOf(raw), pick(all, true, library = LibrarySettings().withFilterToggled(LibraryFilter.Raw)))
    }

    @Test fun `an excluded folder stays out even when its hidden items are shown`() {
        val library = LibrarySettings(
            folderSelectionMode = FolderSelectionMode.AllExceptExcluded,
            folderRules = mapOf(FolderSelectionTarget.Path("external_primary", "Pictures/Private/") to false),
        )
        assertEquals(listOf(other), pick(listOf(private, deep, other), showAll = true, library = library))
    }
}
