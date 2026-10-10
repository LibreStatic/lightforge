package com.librestatic.lightforge.core.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PortablePreferencesRepositoryTest {
    private fun payload(value: String) = value.toByteArray(Charsets.UTF_8)

    private val all = PortablePreferenceGroup.entries.toSet()

    private suspend fun failure(reason: PortablePreferencesFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (e: PortablePreferencesException) {
            assertEquals(reason, e.reason)
        }
    }

    private fun test(block: suspend (GallerySettingsRepository, File) -> Unit) = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("portable-preferences").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(directory, "test.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            block(GallerySettingsRepository(store), file)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun selectedPresentationPreservesSecurityVolumesAnalysisPlaybackAndOnboarding() =
        test { repo, _ ->
            repo.update {
                it.copy(
                    library =
                        it.library.copy(
                            folderSelectionMode = FolderSelectionMode.OnlyIncluded,
                            folderRules = mapOf(FolderSelectionTarget.Bucket("local", 55) to true),
                        ),
                    security = SecuritySettings(true, true, 15),
                    operations = OperationSettings(true, false, false),
                    analysis = AnalysisSettings(50),
                    gestures = it.gestures.copy(videoSkipSeconds = 30),
                    playback = it.playback.copy(loopVideos = true),
                )
            }
            val before = repo.settings.first()
            val bytes =
                payload(
                    """{"schemaVersion":5,"thumbnails":{"gridColumns":7},"library":{"sort":"Name","folderSelectionMode":"AllExceptExcluded","folderRules":[]},"security":{"appLockEnabled":false},"analysis":{"fullAnalysisMinimumBatteryPercent":20},"operations":{"skipAppDeleteConfirmation":true},"gestures":{"onboardingShown":false,"pinchZoom":false},"playback":{"loopVideos":false}}"""
                )
            val review = repo.review(bytes, UUID.randomUUID().toString())
            assertEquals(all, review.availableGroups)
            repo.apply(bytes, review, setOf(PortablePreferenceGroup.Presentation))
            val after = repo.settings.first()
            assertEquals(7, after.thumbnails.gridColumns)
            assertEquals(LibrarySort.Name, after.library.sort)
            assertEquals(before.library.folderRules, after.library.folderRules)
            assertEquals(before.library.folderSelectionMode, after.library.folderSelectionMode)
            assertEquals(before.security, after.security)
            assertEquals(before.operations, after.operations)
            assertEquals(before.analysis, after.analysis)
            assertEquals(before.playback, after.playback)
            assertEquals(before.gestures, after.gestures)
        }

    @Test
    fun frameInterpolationEngineRestoresFromBackupAndRejectsUnknownNames() = test { repo, _ ->
        val bytes = payload("""{"playback":{"frameInterpolationEngine":"Gpu"}}""")
        val review = repo.review(bytes, UUID.randomUUID().toString())
        repo.apply(bytes, review, review.availableGroups)
        assertEquals(FrameInterpolationEngine.Gpu, repo.settings.first().playback.frameInterpolationEngine)

        // Unknown names are rejected by the strict backup path, as for the other enum fields.
        failure(PortablePreferencesFailure.InvalidPayload) {
            repo.review(
                payload("""{"playback":{"frameInterpolationEngine":"Quantum"}}"""),
                UUID.randomUUID().toString(),
            )
        }
        assertEquals(FrameInterpolationEngine.Gpu, repo.settings.first().playback.frameInterpolationEngine)
    }

    @Test
    fun fullImportFallsBackToAutomaticForMissingOrUnknownEngine() = test { repo, _ ->
        repo.update { it.copy(playback = it.playback.copy(frameInterpolationEngine = FrameInterpolationEngine.Gpu)) }
        repo.importJson(org.json.JSONObject("""{"playback":{"frameInterpolationEngine":"Quantum"}}"""))
        assertEquals(FrameInterpolationEngine.Automatic, repo.settings.first().playback.frameInterpolationEngine)
        repo.update { it.copy(playback = it.playback.copy(frameInterpolationEngine = FrameInterpolationEngine.Gpu)) }
        repo.importJson(org.json.JSONObject("""{"playback":{"loopVideos":true}}"""))
        assertEquals(FrameInterpolationEngine.Automatic, repo.settings.first().playback.frameInterpolationEngine)
    }

    @Test
    fun playbackAndGesturesApplyOnlyPresentFieldsAndRestoreOldToggleKeys() = test { repo, _ ->
        repo.update { it.copy(gestures = it.gestures.copy(videoSkipSeconds = 30)) }
        // onboardingShown was removed and is ignored; the thumbnail and rotate toggles keep their
        // original key names, so older exports restore them.
        val bytes =
            payload(
                """{"playback":{"loopVideos":true,"videoScrubbingMode":"Filmstrip"},"gestures":{"pinchZoom":false,"photoMaxZoom":3.5,"onboardingShown":false,"rotatePhotos":false},"thumbnails":{"animateMedia":true,"showFileType":false,"markFavorites":false}}"""
            )
        val review = repo.review(bytes, UUID.randomUUID().toString())
        assertEquals(8, review.differences.size)
        repo.apply(bytes, review, review.availableGroups)
        val after = repo.settings.first()
        assertTrue(after.playback.loopVideos)
        assertTrue(after.playback.autoplayVideos)
        assertEquals(VideoScrubbingMode.Filmstrip, after.playback.videoScrubbingMode)
        assertFalse(after.gestures.pinchZoom)
        assertEquals(30, after.gestures.videoSkipSeconds)
        assertEquals(3.5f, after.gestures.photoMaxZoom)
        assertFalse(after.gestures.rotatePhotos)
        assertTrue(after.thumbnails.animateMedia)
        assertFalse(after.thumbnails.showFileType)
        assertFalse(after.thumbnails.markFavorites)
    }

    @Test
    fun swipeUpForDetailsPersistsAndImportsWhileOlderExportsKeepIt() = test { repo, _ ->
        assertTrue(repo.settings.first().gestures.swipeUpForDetails)
        repo.update { it.copy(gestures = it.gestures.copy(swipeUpForDetails = false)) }
        assertFalse(repo.settings.first().gestures.swipeUpForDetails)

        // An export without the field leaves the current choice alone.
        val older = payload("""{"gestures":{"pinchZoom":false}}""")
        val olderReview = repo.review(older, UUID.randomUUID().toString())
        assertTrue(olderReview.differences.none { it.field == PortablePreferenceField.SwipeUp })
        repo.apply(older, olderReview, olderReview.availableGroups)
        assertFalse(repo.settings.first().gestures.swipeUpForDetails)

        val bytes = payload("""{"gestures":{"swipeUpForDetails":true}}""")
        val review = repo.review(bytes, UUID.randomUUID().toString())
        val difference = review.differences.single { it.field == PortablePreferenceField.SwipeUp }
        assertEquals("false", difference.currentValue)
        assertEquals("true", difference.importedValue)
        repo.apply(bytes, review, review.availableGroups)
        assertTrue(repo.settings.first().gestures.swipeUpForDetails)
    }

    @Test
    fun concurrentReviewsOnlyOneCommitsAndForgedDiffRejected() = test { repo, _ ->
        val bytes = payload("""{"thumbnails":{"gridColumns":6}}""")
        val a = repo.review(bytes, UUID.randomUUID().toString())
        val b = repo.review(bytes, UUID.randomUUID().toString())
        coroutineScope {
            val results =
                listOf(a, b)
                    .map { review ->
                        async { runCatching { repo.apply(bytes, review, review.availableGroups) } }
                    }
                    .awaitAll()
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(
                PortablePreferencesFailure.Conflict,
                (results.single { it.isFailure }.exceptionOrNull() as PortablePreferencesException)
                    .reason,
            )
        }
        val current = repo.review(bytes, UUID.randomUUID().toString())
        failure(PortablePreferencesFailure.Conflict) {
            repo.apply(bytes, current.copy(differences = emptyList()), current.availableGroups)
        }
        assertEquals(6, repo.settings.first().thumbnails.gridColumns)
    }

    @Test
    fun resetKeepsReceiptAndReplayDoesNotRestoreOldSettings() = test { repo, _ ->
        val bytes = payload("""{"thumbnails":{"gridColumns":8},"playback":{"loopVideos":true}}""")
        val review = repo.review(bytes, UUID.randomUUID().toString())
        repo.apply(bytes, review, review.availableGroups)
        repo.reset()
        val refreshed = repo.review(bytes, review.operationId)
        assertTrue(refreshed.alreadyApplied)
        val repeated = repo.apply(bytes, review, review.availableGroups)
        assertTrue(repeated.alreadyApplied)
        assertEquals(GallerySettings(), repo.settings.first())
        failure(PortablePreferencesFailure.OperationMismatch) {
            repo.apply(bytes, review, setOf(PortablePreferenceGroup.Playback))
        }
        failure(PortablePreferencesFailure.OperationMismatch) {
            repo.review(payload("{}"), review.operationId)
        }
    }

    @Test
    fun receiptSurvivesRealDataStoreCloseAndReopen() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("portable-reopen").toFile()
        val file = File(directory, "settings.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bytes = payload("""{"playback":{"loopVideos":true}}""")
        try {
            var repo =
                GallerySettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
            val review = repo.review(bytes, UUID.randomUUID().toString())
            repo.apply(bytes, review, review.availableGroups)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            repo =
                GallerySettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
            repo.update { it.copy(playback = it.playback.copy(loopVideos = false)) }
            assertTrue(repo.apply(bytes, review, review.availableGroups).alreadyApplied)
            assertFalse(repo.settings.first().playback.loopVideos)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun ordinaryUpdateAndLegacyImportInvalidateReview() = test { repo, _ ->
        val bytes = payload("""{"playback":{"loopVideos":true}}""")
        var review = repo.review(bytes, UUID.randomUUID().toString())
        repo.update { it.copy(thumbnails = it.thumbnails.copy(gridColumns = 4)) }
        failure(PortablePreferencesFailure.Conflict) {
            repo.apply(bytes, review, review.availableGroups)
        }
        review = repo.review(bytes, UUID.randomUUID().toString())
        repo.importJson(org.json.JSONObject("{}"))
        failure(PortablePreferencesFailure.Conflict) {
            repo.apply(bytes, review, review.availableGroups)
        }
    }

    @Test
    fun malformedOversizedDeepAndWrongTypedPayloadsRejectBeforeWrite() = test { repo, _ ->
        val invalid =
            listOf(
                ByteArray(PortablePreferencesCodec.MaxBytes + 1),
                byteArrayOf(0xc3.toByte(), 0x28),
                payload("""{"thumbnails":{"gridColumns":100}}"""),
                payload("""{"playback":{"loopVideos":"true"}}"""),
                payload("""{"schemaVersion":999} """),
                payload("""{"schemaVersion":4294967297}"""),
                payload("{} {}"),
                payload("[".repeat(17) + "]".repeat(17)),
                payload("""{"gestures":{"videoSkipSeconds":7}}"""),
                payload("""{"library":{"sort":"unknown"}}"""),
            )
        invalid.forEach { bytes ->
            failure(PortablePreferencesFailure.InvalidPayload) {
                repo.review(bytes, UUID.randomUUID().toString())
            }
        }
        assertEquals(GallerySettings(), repo.settings.first())
        try {
            PortablePreferencesCodec.readBounded(
                ByteArray(PortablePreferencesCodec.MaxBytes + 1).inputStream()
            )
            fail("Unbounded input accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun fullReceiptLedgerRejectsWithoutEvictingReplayProtection() = test { repo, _ ->
        val bytes = payload("""{"playback":{"loopVideos":true}}""")
        val first = repo.review(bytes, UUID.randomUUID().toString())
        repo.apply(bytes, first, first.availableGroups)
        repeat(PortablePreferencesCodec.MaxReceipts - 1) {
            val review = repo.review(bytes, UUID.randomUUID().toString())
            repo.apply(bytes, review, review.availableGroups)
        }
        val rejected = repo.review(bytes, UUID.randomUUID().toString())
        failure(PortablePreferencesFailure.ReceiptLimit) {
            repo.apply(bytes, rejected, rejected.availableGroups)
        }
        repo.reset()
        assertTrue(repo.apply(bytes, first, first.availableGroups).alreadyApplied)
        assertFalse(repo.settings.first().playback.loopVideos)
    }

    @Test
    fun albumSidePanelChoicePersistsAndRoundTripsThroughJson() = test { repo, _ ->
        assertNull(repo.settings.first().library.albumSidePanelOpen)
        repo.update { it.copy(library = it.library.copy(albumSidePanelOpen = false)) }
        assertEquals(false, repo.settings.first().library.albumSidePanelOpen)
        val exported = repo.exportJson()
        repo.reset()
        assertNull(repo.settings.first().library.albumSidePanelOpen)
        repo.importJson(exported)
        assertEquals(false, repo.settings.first().library.albumSidePanelOpen)
        repo.update { it.copy(library = it.library.copy(albumSidePanelOpen = null)) }
        assertNull(repo.settings.first().library.albumSidePanelOpen)
    }

    @Test
    fun appearancePersistsRoundTripsAndFallsBackOnUnknownValues() = test { repo, _ ->
        assertEquals(AppearanceSettings(), repo.settings.first().appearance)
        val chosen = AppearanceSettings(ThemePalette.Warm, ThemeMode.Dark, pureBlack = true)
        repo.update { it.copy(appearance = chosen) }
        assertEquals(chosen, repo.settings.first().appearance)
        val exported = repo.exportJson()
        repo.reset()
        assertEquals(AppearanceSettings(), repo.settings.first().appearance)
        repo.importJson(exported)
        assertEquals(chosen, repo.settings.first().appearance)
        repo.importJson(
            org.json.JSONObject(
                """{"schemaVersion":5,"appearance":{"palette":"Sepia","mode":"Dusk","pureBlack":true}}"""
            )
        )
        assertEquals(
            AppearanceSettings(ThemePalette.MaterialYou, ThemeMode.System, pureBlack = true),
            repo.settings.first().appearance,
        )
    }

    @Test
    fun minimalistBlackIsAlwaysDarkAndPureBlack() {
        val minimalist = AppearanceSettings(ThemePalette.MinimalistBlack, ThemeMode.Light)
        assertEquals(true, minimalist.isDark(systemDark = false))
        assertEquals(true, minimalist.isPureBlack(systemDark = false))
        val amoled = AppearanceSettings(pureBlack = true)
        assertEquals(false, amoled.isPureBlack(systemDark = false))
        assertEquals(true, amoled.isPureBlack(systemDark = true))
        assertEquals(false, amoled.copy(mode = ThemeMode.Light).isPureBlack(systemDark = true))
    }
}
