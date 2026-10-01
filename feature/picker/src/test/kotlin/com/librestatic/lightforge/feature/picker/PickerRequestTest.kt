package com.librestatic.lightforge.feature.picker

import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.preferences.FolderSelectionTarget
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerRequestTest {
    @Test
    fun imageWildcardAcceptsOnlyImages() {
        val request = PickRequest.parse("image/*", null, null, allowMultiple = false)
        assertEquals(KindFilter.Any, request.images)
        assertEquals(KindFilter.None, request.videos)
        assertFalse(request.isUnsatisfiable)
    }

    @Test
    fun extraMimeTypesRefineAnAnyType() {
        val request = PickRequest.parse("*/*", listOf("image/png", "IMAGE/JPEG", "application/pdf"), null, true)
        assertEquals(KindFilter.Exact(setOf("image/png", "image/jpeg")), request.images)
        assertEquals(KindFilter.None, request.videos)
        assertTrue(request.allowMultiple)
    }

    @Test
    fun wildcardWinsOverExactTypes() {
        val request = PickRequest.parse(null, listOf("video/mp4", "video/*"), null, false)
        assertEquals(KindFilter.Any, request.videos)
    }

    @Test
    fun anyTypeAcceptsBothKinds() {
        val request = PickRequest.parse("*/*", null, null, false)
        assertEquals(KindFilter.Any, request.images)
        assertEquals(KindFilter.Any, request.videos)
    }

    @Test
    fun nonMediaRequestIsUnsatisfiable() {
        assertTrue(PickRequest.parse("application/pdf", null, null, false).isUnsatisfiable)
    }

    @Test
    fun pickOnCollectionUriUsesItsPath() {
        val images = PickRequest.parse(null, null, "/external/images/media", false)
        assertEquals(KindFilter.Any, images.images)
        assertEquals(KindFilter.None, images.videos)
        val videos = PickRequest.parse("vnd.android.cursor.dir/video", null, null, false)
        assertEquals(KindFilter.None, videos.images)
        assertEquals(KindFilter.Any, videos.videos)
    }

    @Test
    fun kindSelectionBindsExactMimeTypes() {
        val selection = PickerSql.kindSelection(
            PickRequest(KindFilter.Exact(setOf("image/png", "image/gif")), KindFilter.Any, false),
        )
        assertEquals("(media_type=1 AND mime_type IN (?,?)) OR (media_type=3)", selection.sql)
        assertEquals(listOf("image/gif", "image/png"), selection.args)
    }

    @Test
    fun noFolderRulesMeansNoFolderPredicate() {
        assertNull(PickerSql.folderSelection(FolderRules.Everything, listOf(BucketVisibility("external_primary", 1, true))))
    }

    @Test
    fun excludedFoldersKeepLooseFiles() {
        val rules = FolderRules(true, mapOf(FolderSelectionTarget.Bucket("external_primary", 7) to false))
        val buckets = listOf(
            BucketVisibility("external_primary", 7, false),
            BucketVisibility("external_primary", 3, false),
            BucketVisibility("external_primary", 9, true),
        )
        val selection = PickerSql.folderSelection(rules, buckets)!!
        assertEquals("bucket_id IS NULL OR NOT ((volume_name=? AND bucket_id IN (3,7)))", selection.sql)
        assertEquals(listOf("external_primary"), selection.args)
    }

    @Test
    fun includeOnlyModeListsVisibleFoldersPerVolume() {
        val rules = FolderRules(false, mapOf(FolderSelectionTarget.Bucket("sd", 2) to true))
        val selection = PickerSql.folderSelection(
            rules,
            listOf(BucketVisibility("sd", 2, true), BucketVisibility("external_primary", 5, true), BucketVisibility("sd", 4, false)),
        )!!
        assertEquals(
            "(volume_name=? AND bucket_id IN (5)) OR (volume_name=? AND bucket_id IN (2))",
            selection.sql,
        )
        assertEquals(listOf("external_primary", "sd"), selection.args)
        assertEquals(SqlSelection.Nothing, PickerSql.folderSelection(rules, listOf(BucketVisibility("sd", 4, false))))
    }

    @Test
    fun pathRulesDecideBucketVisibility() {
        val rules = FolderRules(true, mapOf(FolderSelectionTarget.Path("external_primary", "Pictures/Private/") to false))
        assertFalse(rules.isVisible("external_primary", 11, "Pictures/Private/Old/"))
        assertTrue(rules.isVisible("external_primary", 12, "Pictures/Public/"))
    }

    @Test
    fun nameSearchEscapesWildcards() {
        val selection = PickerSql.nameSelection(" 50%_off ")
        assertEquals(listOf("%50\\%\\_off%"), selection.args)
    }

    @Test
    fun clashingFolderNamesUseTheirPath() {
        fun album(id: Long, name: String?, path: String) =
            PickerAlbum("external_primary", id, name, path, 1, MediaKey("external_primary", id), false, 0)
        val labels = PickerSql.disambiguate(
            listOf(album(1, "UGallery", "Pictures/UGallery/"), album(2, "ugallery", "Movies/UGallery/"), album(3, "Camera", "DCIM/Camera/"), album(4, null, "/")),
        ).map(PickerAlbum::name)
        assertEquals(listOf("Pictures/UGallery", "Movies/UGallery", "Camera", null), labels)
    }

    @Test
    fun documentIdsRoundTrip() {
        val ids = listOf(DocumentId.Root, DocumentId.Album("external_primary", -1234), DocumentId.Media(MediaKey("1a2b-3c4d", 42)))
        ids.forEach { assertEquals(it, DocumentId.parse(it.encode())) }
    }

    @Test(expected = FileNotFoundException::class)
    fun malformedDocumentIdIsNotFound() {
        DocumentId.parse("media:external_primary:-3")
    }
}
