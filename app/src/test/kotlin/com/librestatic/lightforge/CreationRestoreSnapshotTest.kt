package com.librestatic.lightforge

import com.librestatic.lightforge.core.mediastore.MediaActionTarget
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.preferences.FolderSelectionTarget
import com.librestatic.lightforge.core.selection.MediaQuery
import com.librestatic.lightforge.core.selection.SelectionSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.*
import org.junit.Test

class CreationRestoreSnapshotTest {
    @Test fun manualMemoryEnvelopeKeepsOriginalUuidAndBothGenerations() {
        val manual = ManualMomentRestoreSnapshot(id, listOf(
            ManualMomentSourceSnapshot(key(2), 7, 9, true),
            ManualMomentSourceSnapshot(key(1), 8, 10, false),
        ))
        val captured = requireNotNull(CreationRestoreSnapshot.capture(null, manualMoment = manual))
        val restored = ObjectInputStream(ByteArrayInputStream(bytes(captured))).use { it.readObject() }
        val checked = requireNotNull(CreationRestoreSnapshot.validatedOrNull(restored))
        assertEquals(CreationRestoreSnapshot.CurrentVersion, checked.version)
        assertEquals(manual, checked.manualMoment)
        assertEquals(listOf(key(2), key(1)), checked.manualMoment!!.toDraft().sources.map { it.key })
    }

    @Test fun malformedManualDraftRejectsWholeEnvelopeRatherThanDroppingIt() {
        val manual = ManualMomentRestoreSnapshot("not-a-uuid", listOf(ManualMomentSourceSnapshot(key(1), 7, 9, false)))
        assertNull(CreationRestoreSnapshot.capture(null, video = video(), manualMoment = manual))
    }

    @Test fun legacyEnvelopeUpgradesWithoutInventingManualDraft() {
        val legacy = CreationRestoreSnapshot(null, video(), version = 5)
        val checked = requireNotNull(CreationRestoreSnapshot.validatedOrNull(legacy))
        assertEquals(CreationRestoreSnapshot.CurrentVersion, checked.version)
        assertNull(checked.manualMoment)
        assertEquals(legacy.video, checked.video)
    }

    private fun externalDraft(name: String = "clip.mp4"): ExternalVideoEditorRestoreSnapshot {
        val encoded = VideoEditorRestoreSnapshot.encodeRecipeExact(com.librestatic.lightforge.core.editing.video.VideoEditRecipe(startMillis = 600, endMillis = 2400))
        return ExternalVideoEditorRestoreSnapshot(id, ExternalVideoSourceSnapshot(
            "content://fixture.provider/document/one", "video/mp4", name, 640, 480, 3000, 1234, "a".repeat(64)),
            3000, encoded, encoded, 1200)
    }

    @Test fun externalEditorSurvivesSharedEnvelopeSerializationWithoutLibraryIdentity() {
        val external = externalDraft()
        val captured = requireNotNull(CreationRestoreSnapshot.capture(null, externalVideoEditor = external))
        val checked = requireNotNull(restored(captured))
        assertEquals(external, checked.externalVideoEditor)
        assertNull(checked.videoEditor)
        assertNull(checked.viewer)
        assertEquals(7, checked.version)
    }

    @Test fun externalEditorSharesTotalBudgetWithOtherCreationState() {
        val external = externalDraft("x".repeat(30_000))
        val other = video("y".repeat(40_000))
        assertNotNull(CreationRestoreSnapshot.capture(null, externalVideoEditor = external))
        assertNotNull(CreationRestoreSnapshot.capture(null, video = other))
        assertNull(CreationRestoreSnapshot.capture(null, video = other, externalVideoEditor = external))
    }

    @Test fun malformedExternalIdentityRejectsWholeEnvelope() {
        val external = externalDraft()
        assertNull(CreationRestoreSnapshot.capture(null, video = video(),
            externalVideoEditor = external.copy(source = external.source.copy(sha256 = "changed"))))
    }

    private val id = "15d3aed9-19d9-4e2b-bd47-30d913799f52"
    private fun key(id: Long) = MediaKey("external_primary", id)
    private fun target(id: Long) = MediaActionTarget(key(id), MediaKind.Image)
    private fun source(id: Long) = CreationVideoSourceSnapshot("content://media/external_primary/images/media/$id", 9, 7)
    private fun video(title: String? = null) = CreationVideoSnapshot(id, title, listOf(source(2), source(1)))
    private fun bytes(value: Any): ByteArray = ByteArrayOutputStream().use { output ->
        ObjectOutputStream(output).use { it.writeObject(value) }; output.toByteArray()
    }
    private fun restored(value: CreationRestoreSnapshot): CreationRestoreSnapshot? =
        ObjectInputStream(ByteArrayInputStream(bytes(value))).use { CreationRestoreSnapshot.validatedOrNull(it.readObject()) }

    @Test fun explicitSelectionAndNullableVideoTitleRoundTripWithoutLosingOrderOrGenerations() {
        val selection = SelectionSpec.explicit(listOf(key(2), key(1)))
        val snapshot = requireNotNull(CreationRestoreSnapshot.capture(selection, listOf(target(2), target(1)), 2, video()))
        val read = requireNotNull(restored(snapshot))
        assertEquals(snapshot, read)
        assertEquals(listOf(key(2), key(1)), (read.selection!!.selection as SelectionSpec.Explicit).keys.toList())
        assertEquals(listOf(target(2), target(1)), read.selection!!.targets)
        assertEquals(2L, read.selection!!.count)
        assertNull(read.video!!.title)
        assertEquals(id, read.video!!.id)
        assertEquals(listOf(source(2), source(1)), read.video!!.sources)
        assertEquals(bytes(snapshot).size, CreationRestoreSnapshot.serializedSizeOrNull(snapshot))
    }

    @Test fun queryAll100kRemainsCompactAndPreservesExclusionsAndQueryNotCurrentFilters() {
        val query = MediaQuery(scope = MediaQuery.Scope.PhysicalAlbum("external_primary", 7), favoriteOnly = true)
        val selection = SelectionSpec.queryAll(query, listOf(key(9), key(3)))
        val snapshot = requireNotNull(CreationRestoreSnapshot.capture(selection, count = 99_998))
        val read = requireNotNull(restored(snapshot)).selection!!
        assertEquals(99_998L, read.count)
        assertTrue(read.targets.isEmpty())
        assertEquals(selection, read.selection)
        assertEquals(query, (read.selection as SelectionSpec.QueryAll).querySnapshot)
        assertTrue(bytes(snapshot).size < 8192)
    }

    @Test fun detachedCollectionsDoNotTrackLaterCallerMutation() {
        val rules = linkedMapOf<FolderSelectionTarget, Boolean>(FolderSelectionTarget.Path("external_primary", "Pictures/") to true)
        val query = SelectionSpec.queryAll(MediaQuery(folderRules = rules))
        val sources = mutableListOf(source(1))
        val snapshot = requireNotNull(CreationRestoreSnapshot.capture(query, count = 100_000, video = video("Trip").copy(sources = sources)))
        rules.clear(); sources.clear()
        assertEquals(1, (snapshot.selection!!.selection as SelectionSpec.QueryAll).querySnapshot.folderRules.size)
        assertEquals(listOf(source(1)), snapshot.video!!.sources)
        val targets = mutableListOf(target(1))
        val explicit = requireNotNull(CreationRestoreSnapshot.capture(SelectionSpec.explicit(listOf(key(1))), targets, 1))
        targets.clear()
        assertEquals(listOf(target(1)), explicit.selection!!.targets)
    }

    @Test fun oversizedExplicitSelectionIsOmittedWholeAndLiveSelectionAndTargetsStayIntact() {
        val keys = (1L..5000L).map(::key)
        val selection = SelectionSpec.explicit(keys)
        val targets = keys.map { MediaActionTarget(it, MediaKind.Image) }
        assertNull(CreationRestoreSnapshot.capture(selection, targets, keys.size.toLong()))
        assertEquals(keys, selection.keys.toList())
        assertEquals(5000, targets.size)
    }

    @Test fun totalBudgetAppliesToCombinedEnvelopeNotTwoIndividuallySmallValues() {
        val selection = SelectionSpec.queryAll(MediaQuery(scope = MediaQuery.Scope.Search("x".repeat(31_000))))
        val video = video("y".repeat(36_000))
        assertNotNull(CreationRestoreSnapshot.capture(selection, count = 100_000))
        assertNotNull(CreationRestoreSnapshot.capture(null, video = video))
        assertNull(CreationRestoreSnapshot.capture(selection, count = 100_000, video = video))
        assertEquals(36_000, video.title!!.length)
        assertEquals(2, video.sources.size)
        assertEquals(31_000, (selection.querySnapshot.scope as MediaQuery.Scope.Search).normalizedQuery.length)
    }

    @Test fun byteCeilingRejectsOversizedUtf8AndCountsExactJavaSerialization() {
        val small = requireNotNull(CreationRestoreSnapshot.capture(null, video = video("é".repeat(100))))
        assertEquals(bytes(small).size, CreationRestoreSnapshot.serializedSizeOrNull(small))
        val tooLarge = CreationRestoreSnapshot(null, video("界".repeat(30_000)))
        assertTrue(bytes(tooLarge).size > CreationRestoreSnapshot.MaxSerializedBytes)
        assertNull(CreationRestoreSnapshot.serializedSizeOrNull(tooLarge))
        assertNull(CreationRestoreSnapshot.validatedOrNull(tooLarge))
    }

    @Test fun exact64KiBBoundaryFitsAndOneMoreAsciiByteIsRejected() {
        var low = 0
        var high = CreationRestoreSnapshot.MaxSerializedBytes
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (CreationRestoreSnapshot.capture(null, video = video("a".repeat(middle))) != null) low = middle
            else high = middle - 1
        }
        val largest = requireNotNull(CreationRestoreSnapshot.capture(null, video = video("a".repeat(low))))
        assertEquals(CreationRestoreSnapshot.MaxSerializedBytes, bytes(largest).size)
        assertNotNull(restored(largest))
        assertNull(CreationRestoreSnapshot.capture(null, video = video("a".repeat(low + 1))))
    }

    @Test fun explicitSelectionRequiresCompleteUniqueMatchingTargetsAndExactCount() {
        val selection = SelectionSpec.explicit(listOf(key(1), key(2)))
        assertNull(CreationRestoreSnapshot.capture(selection, listOf(target(1)), 2))
        assertNull(CreationRestoreSnapshot.capture(selection, listOf(target(1), target(1)), 2))
        assertNull(CreationRestoreSnapshot.capture(selection, listOf(target(1), target(3)), 2))
        assertNull(CreationRestoreSnapshot.capture(selection, listOf(target(1), target(2)), 1))
        assertNull(CreationRestoreSnapshot.capture(null, listOf(target(1)), 0))
        assertNull(CreationRestoreSnapshot.capture(null, count = 1))
        assertNull(CreationRestoreSnapshot.capture(SelectionSpec.queryAll(MediaQuery()), listOf(target(1)), 100_000))
        assertNull(CreationRestoreSnapshot.capture(SelectionSpec.queryAll(MediaQuery()), count = -1))
        assertNotNull(CreationRestoreSnapshot.capture(SelectionSpec.explicit()))
    }

    @Test fun videoRequiresCanonicalUuidOneTo120UniqueSourcesAndBothGenerations() {
        fun reject(value: CreationVideoSnapshot) = assertNull(CreationRestoreSnapshot.capture(null, video = value))
        reject(video().copy(id = "not-a-uuid"))
        reject(video().copy(id = id.uppercase()))
        reject(video().copy(sources = emptyList()))
        reject(video().copy(sources = (1L..121L).map(::source)))
        reject(video().copy(sources = listOf(source(1), source(1))))
        reject(video().copy(sources = listOf(source(1).copy(generationModified = -1))))
        reject(video().copy(sources = listOf(source(1).copy(generationAdded = -1))))
        assertNotNull(CreationRestoreSnapshot.capture(null, video = video().copy(sources = (1L..120L).map(::source))))
        assertNotNull(CreationRestoreSnapshot.capture(null, video = video().copy(sources = listOf(source(0).copy(generationAdded = 0, generationModified = 0)))))
    }

    @Test fun videoRejectsExternalProvidersAmbiguousUrisAndNonImagePaths() {
        for (uri in listOf(
            "file:///storage/emulated/0/DCIM/photo.jpg", "https://media/external/images/media/1",
            "content://other/external/images/media/1", "content://media/external/video/media/1",
            "content://media/external/file/1", "content://media/external/images/media/01",
            "content://media/external/images/media/-1", "content://media/external/images/media/9223372036854775808",
            "content://media/external/images/media/1?x=1", "content://media/external/images/media/1#x",
            "content://media/external/images/media/%31", "content://media/a%2fb/images/media/1",
            "content://user@media/external/images/media/1", "content://media:80/external/images/media/1"
        )) assertNull(uri, CreationRestoreSnapshot.capture(null, video = video().copy(sources = listOf(source(1).copy(uri = uri)))))
        assertNotNull(CreationRestoreSnapshot.capture(null, video = video().copy(sources = listOf(source(1).copy(uri = "content://media/1234-ABCD/images/media/1")))))
    }

    @Test fun invalidRestoredVersionTypeAndBypassedKeyConstructorAreRejected() {
        assertNull(CreationRestoreSnapshot.validatedOrNull("not saved state"))
        assertNull(CreationRestoreSnapshot.validatedOrNull(null))
        assertNull(restored(CreationRestoreSnapshot(null, video(), version = 42)))
        val corrupted = key(1)
        MediaKey::class.java.getDeclaredField("mediaStoreId").apply { isAccessible = true }.setLong(corrupted, -1)
        val value = CreationRestoreSnapshot(CreationSelectionSnapshot(
            SelectionSpec.explicit(listOf(corrupted)), listOf(MediaActionTarget(corrupted, MediaKind.Image)), 1
        ), null)
        assertNull(restored(value))
    }
    @Test fun collageSessionOrderAndBothGenerationsSurviveTogetherWithSelection() {
        val collage = CreationCollageSnapshot(id, listOf(source(2), source(1)))
        val snapshot = requireNotNull(CreationRestoreSnapshot.capture(
            SelectionSpec.explicit(listOf(key(2), key(1))), listOf(target(2), target(1)), 2, collage = collage))
        assertEquals(snapshot, restored(snapshot))
        assertEquals(collage, restored(snapshot)!!.collage)
        assertTrue(bytes(snapshot).size < 8192)
    }

    @Test fun collageRejectsPartialDuplicateChangedGenerationAndOversizedJointState() {
        val collage = CreationCollageSnapshot(id, listOf(source(1), source(2)))
        for (invalid in listOf(
            collage.copy(id = "other"), collage.copy(sources = listOf(source(1))),
            collage.copy(sources = (1L..5L).map(::source)), collage.copy(sources = listOf(source(1), source(1))),
            collage.copy(sources = listOf(source(1).copy(generationAdded = -1), source(2))),
            collage.copy(sources = listOf(source(1).copy(uri = "file:///photo.jpg"), source(2))),
        )) assertNull(CreationRestoreSnapshot.capture(null, collage = invalid))
        var title = ""
        while (CreationRestoreSnapshot.capture(null, video = video(title + "a".repeat(100))) != null) title += "a".repeat(100)
        assertNotNull(CreationRestoreSnapshot.capture(null, video = video(title)))
        assertNull(CreationRestoreSnapshot.capture(null, video = video(title), collage = collage))
        assertEquals(2, collage.sources.size)
    }

    @Test fun versionOneEnvelopeStillRestoresSelectionAndVideo() {
        val old = CreationRestoreSnapshot(null, video(), version = 1)
        val migrated = requireNotNull(restored(old))
        assertEquals(CreationRestoreSnapshot.CurrentVersion, migrated.version)
        assertEquals(old.video, migrated.video)
        assertNull(migrated.collage)
    }

    @Test fun collageDraftRouteStaysAvailableDuringValidationAndExplicitUnavailableState() {
        assertEquals(SurfaceRoute.Collage, availableSurfaceRoute(SurfaceRoute.Collage, false, false, false,
            hasCreationCollageDraft = true))
        assertEquals("creation-collage:$id", surfaceStateKey(SurfaceRoute.Collage, RootTab.Photos, null,
            creationCollageSessionId = id))
        assertEquals(SurfaceRoute.Root, availableSurfaceRoute(SurfaceRoute.Collage, false, false, false))
    }

    @Test fun gifRoundTripsWithSelectionVideoAndCollageWithoutSharingMutableSources() {
        val sources = mutableListOf(source(2), source(1))
        val gif = CreationGifSnapshot(id, sources)
        val value = requireNotNull(CreationRestoreSnapshot.capture(
            SelectionSpec.explicit(listOf(key(2), key(1))), listOf(target(2), target(1)), 2,
            video(), CreationCollageSnapshot(id, listOf(source(1), source(2))), gif))
        sources.clear()
        val copy = requireNotNull(restored(value))
        assertEquals(value, copy)
        assertEquals(listOf(source(2), source(1)), copy.gif!!.sources)
        assertEquals(id, copy.gif!!.id)
        assertNotNull(copy.collage)
        assertNotNull(copy.video)
        assertEquals(2L, copy.selection!!.count)
    }

    @Test fun gifRequiresTwoToSixtyUniqueCanonicalPhotosAndBothGenerations() {
        val gif = CreationGifSnapshot(id, listOf(source(1), source(2)))
        for (invalid in listOf(
            gif.copy(id = "invalid"), gif.copy(sources = listOf(source(1))),
            gif.copy(sources = (1L..61L).map(::source)),
            gif.copy(sources = listOf(source(1), source(1))),
            gif.copy(sources = listOf(source(1).copy(generationAdded = -1), source(2))),
            gif.copy(sources = listOf(source(1).copy(generationModified = -1), source(2))),
            gif.copy(sources = listOf(source(1).copy(uri = "file:///photo.jpg"), source(2))),
        )) assertNull(CreationRestoreSnapshot.capture(null, gif = invalid))
        assertNotNull(CreationRestoreSnapshot.capture(null, gif = gif.copy(sources = (1L..60L).map(::source))))
    }

    @Test fun gifSharesExactEnvelopeBudgetAndNeverTrimsLiveSources() {
        val gif = CreationGifSnapshot(id, listOf(source(1), source(2)))
        var low = 0
        var high = CreationRestoreSnapshot.MaxSerializedBytes
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (CreationRestoreSnapshot.capture(null, video = video("a".repeat(middle))) != null) low = middle
            else high = middle - 1
        }
        assertNotNull(CreationRestoreSnapshot.capture(null, video = video("a".repeat(low))))
        assertNull(CreationRestoreSnapshot.capture(null, video = video("a".repeat(low)), gif = gif))
        assertEquals(2, gif.sources.size)
    }

    @Test fun previousEnvelopeVersionsKeepTheirExistingFieldsWithoutInventingGif() {
        for (version in 1..2) {
            val old = CreationRestoreSnapshot(null, video(), version,
                collage = if (version == 2) CreationCollageSnapshot(id, listOf(source(1), source(2))) else null)
            val copy = requireNotNull(restored(old))
            assertEquals(CreationRestoreSnapshot.CurrentVersion, copy.version)
            assertEquals(old.video, copy.video)
            assertEquals(old.collage, copy.collage)
            assertNull(copy.gif)
        }
    }

    private fun viewer(query: MediaQuery = MediaQuery()) =
        ViewerRestoreSnapshot(key(2), MediaKind.Image, 9, 7, false, query)

    @Test fun viewerRoundTripsExactCurrentSourceAndOriginQueryAlongsideAllCreationDrafts() {
        val value = requireNotNull(CreationRestoreSnapshot.capture(
            SelectionSpec.explicit(listOf(key(1), key(2))), listOf(target(1), target(2)), 2, video(),
            CreationCollageSnapshot(id, listOf(source(1), source(2))),
            CreationGifSnapshot(id, listOf(source(2), source(1))),
            viewer(MediaQuery(scope = MediaQuery.Scope.PhysicalAlbum("external_primary", 42), favoriteOnly = true))))
        assertEquals(value, restored(value))
        assertEquals(value.viewer!!.identity, restored(value)!!.viewer!!.identity)
        assertEquals(MediaQuery.Scope.PhysicalAlbum("external_primary", 42), restored(value)!!.viewer!!.query.scope)
    }

    @Test fun viewerSourceIdentityRejectsReplacementKindTrashAndEitherGenerationChange() {
        val current = viewer()
        assertTrue(current.matchesSource(key(2), MediaKind.Image, 9, 7, false))
        assertFalse(current.matchesSource(key(1), MediaKind.Image, 9, 7, false))
        assertFalse(current.matchesSource(key(2), MediaKind.Video, 9, 7, false))
        assertFalse(current.matchesSource(key(2), MediaKind.Image, 10, 7, false))
        assertFalse(current.matchesSource(key(2), MediaKind.Image, 9, 8, false))
        assertFalse(current.matchesSource(key(2), MediaKind.Image, 9, 7, true))
        assertNotEquals(current.identity, current.copy(generationAdded = 8).identity)
        assertNotEquals(current.identity, current.copy(kind = MediaKind.Video).identity)
    }

    @Test fun viewerRejectsInvalidRestoredGenerationAndDetachesOriginRules() {
        assertNull(CreationRestoreSnapshot.capture(null, viewer = viewer().copy(generationModified = -1)))
        assertNull(CreationRestoreSnapshot.capture(null, viewer = viewer().copy(generationAdded = -1)))
        val rules = linkedMapOf<FolderSelectionTarget, Boolean>(FolderSelectionTarget.Path("external_primary", "Pictures/") to true)
        val value = requireNotNull(CreationRestoreSnapshot.capture(null, viewer = viewer(MediaQuery(folderRules = rules))))
        rules.clear()
        assertEquals(1, value.viewer!!.query.folderRules.size)
        assertNotNull(restored(value))
        assertNotNull(CreationRestoreSnapshot.capture(null, viewer = viewer().copy(kind = MediaKind.Video, isTrashed = true)))
    }

    @Test fun viewerAndDraftsShareOneBudgetRatherThanEachGetting64KiB() {
        val query = MediaQuery(scope = MediaQuery.Scope.Search("q".repeat(31_000)))
        val video = video("v".repeat(36_000))
        assertNotNull(CreationRestoreSnapshot.capture(null, viewer = viewer(query)))
        assertNotNull(CreationRestoreSnapshot.capture(null, video = video))
        assertNull(CreationRestoreSnapshot.capture(null, video = video, viewer = viewer(query)))
    }

    @Test fun versionThreeKeepsGifWithoutInventingCurrentViewer() {
        val gif = CreationGifSnapshot(id, listOf(source(2), source(1)))
        val old = CreationRestoreSnapshot(null, video(), version = 3, gif = gif)
        val copy = requireNotNull(restored(old))
        assertEquals(CreationRestoreSnapshot.CurrentVersion, copy.version)
        assertEquals(gif, copy.gif)
        assertNull(copy.viewer)
    }

    @Test fun videoEditorRouteAndProviderStayBoundToTheirSession() {
        assertEquals(SurfaceRoute.VideoEditor, availableSurfaceRoute(SurfaceRoute.VideoEditor, false, false, false,
            hasVideoEditorDraft = true))
        assertEquals("video-editor:$id", surfaceStateKey(SurfaceRoute.VideoEditor, RootTab.Photos, null,
            videoEditorSessionId = id))
        assertEquals(SurfaceRoute.Root, availableSurfaceRoute(SurfaceRoute.VideoEditor, false, false, false))
    }

    @Test fun editorEnvelopeRestoresCompleteRecipeOnlyWithItsMatchingViewer() {
        val viewer = ViewerRestoreSnapshot(key(1), MediaKind.Video, 5, 4, false, MediaQuery())
        val baseline = VideoEditorRestoreSnapshot.encodeRecipeExact(com.librestatic.lightforge.core.editing.video.VideoEditRecipe())
        val recipe = VideoEditorRestoreSnapshot.encodeRecipeExact(com.librestatic.lightforge.core.editing.video.VideoEditRecipe(startMillis = 500, endMillis = 2500))
        val editor = VideoEditorRestoreSnapshot(id, viewer, 3000, recipe, baseline, 1500)
        val envelope = requireNotNull(CreationRestoreSnapshot.capture(null, viewer = viewer, videoEditor = editor))
        assertEquals(editor, restored(envelope)!!.videoEditor)
        assertNull(CreationRestoreSnapshot.capture(null, videoEditor = editor))
        assertNull(CreationRestoreSnapshot.capture(null, viewer = viewer.copy(generationAdded = 3), videoEditor = editor))
        assertNull(CreationRestoreSnapshot.capture(null, viewer = viewer, videoEditor = editor.copy(positionMillis = 2500)))
        assertNull(restored(envelope.copy(videoEditor = editor.copy(recipe = recipe + "x".repeat(65536)))))
    }

}
