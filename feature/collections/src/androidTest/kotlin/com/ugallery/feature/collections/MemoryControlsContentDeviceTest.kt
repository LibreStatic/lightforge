package com.ugallery.feature.collections

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.data.*
import com.ugallery.core.database.*
import com.ugallery.core.designsystem.UGalleryTheme
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MemoryControlsContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val volume = "volume:with:colon"
    private val momentId = "saved-memory"

    private fun media(id: Long, v: String = volume, day: Int = id.toInt()) =
        MediaItemEntity(
            v,
            id,
            1,
            "image/jpeg",
            "photo-$id.jpg",
            100,
            120,
            80,
            0,
            0,
            Instant.parse("2026-06-${day.toString().padStart(2,'0')}T12:00:00Z").toEpochMilli(),
            1,
            1,
            Instant.parse("2026-06-${day.toString().padStart(2,'0')}T12:00:00Z").toEpochMilli(),
            1,
            1,
            1,
            "Camera",
            "DCIM/Camera/",
            false,
            false,
            true,
            1,
        )

    private suspend fun seed(db: GalleryDatabase) {
        db.libraryDao().upsertMedia((1L..3L).map { media(it) })
        db.momentDao()
            .upsertMoment(
                MomentEntity(
                    momentId,
                    "USER",
                    "SAVED",
                    "fixture",
                    1,
                    2,
                    "Keep title",
                    "USER",
                    true,
                    1,
                    2,
                )
            )
        db.momentDao()
            .insertMembers(
                (1L..3L).map {
                    MomentMemberEntity(momentId, it.toInt() - 1, volume, it, 1, "USER", 1f)
                }
            )
        db.momentDao().upsertCover(MomentCoverEntity(momentId, volume, 1, true))
    }

    private suspend fun person(
        db: GalleryDatabase,
        id: Long,
        cluster: String = "person",
        version: String = "v1",
        v: String = volume,
        generation: Long = 1,
    ) {
        db.personDao()
            .upsertCluster(
                PersonClusterEntity(
                    cluster,
                    version,
                    ByteArray(128),
                    1,
                    "Known person",
                    false,
                    true,
                    1,
                    1,
                )
            )
        db.libraryDao()
            .replaceFaceDetection(
                FaceDetectionRunEntity(v, id, generation, "face", 1, 1),
                listOf(
                    DetectedFaceEntity(
                        v,
                        id,
                        0,
                        "face",
                        100,
                        100,
                        500,
                        500,
                        50,
                        50,
                        550,
                        550,
                        0f,
                        0f,
                        0f,
                        .9f,
                        "[]",
                    )
                ),
            )
        db.libraryDao()
            .upsertFaceEmbeddings(
                listOf(FaceEmbeddingEntity(v, id, 0, "face", "embedding", ByteArray(128), 1))
            )
        db.personDao()
            .upsertMembership(PersonMembershipEntity(v, id, 0, cluster, version, "user", .9f, 1))
    }

    private fun await(block: () -> Boolean) = compose.waitUntil(10_000, block)

    private fun click(tag: String) {
        compose.onNodeWithTag("memory-controls-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsEnabled().performClick()
    }

    private fun field(tag: String, value: String) {
        compose.onNodeWithTag("memory-controls-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTextReplacement(value)
    }

    private fun confirm() {
        compose.onNodeWithTag("memory-controls-confirm").assertIsDisplayed().performClick()
    }

    private fun date(start: String = "2026-06-01", end: String = start) {
        click("memory-controls-add-dates")
        field("memory-controls-start", start)
        field("memory-controls-end", end)
        field("memory-controls-zone", "UTC")
        click("memory-controls-save")
        confirm()
    }

    private fun evidence(name: String, text: String) {
        val dir = File(context.filesDir, "memory-controls-ui").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        File(dir, "$name.txt").writeText(compose.onRoot().printToString())
        File(dir, "$name-result.txt").writeText(text)
    }

    private fun flow(dark: Boolean, dynamic: Boolean, width: Int, font: Float) {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repository = MemoryExclusionRepository(db)
        val moments = MomentRepository(db)
        val contrast = mutableListOf<Double>()
        try {
            runBlocking {
                seed(db)
                person(db, 1)
            }
            val originals = runBlocking { (1L..3L).map { db.libraryDao().media(volume, it) } }
            compose.setContent {
                UGalleryTheme(darkTheme = dark, dynamicColor = dynamic) {
                    val c = MaterialTheme.colorScheme
                    SideEffect {
                        contrast.clear()
                        listOf(
                                c.surface to c.onSurface,
                                c.secondaryContainer to c.onSecondaryContainer,
                                c.errorContainer to c.onErrorContainer,
                                c.surfaceVariant to c.onSurfaceVariant,
                            )
                            .forEach { (a, b) ->
                                val x = a.luminance().toDouble()
                                val y = b.luminance().toDouble()
                                contrast += (maxOf(x, y) + .05) / (minOf(x, y) + .05)
                            }
                    }
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                        Box(Modifier.width(width.dp).fillMaxHeight()) {
                            MemoryControlsContent(
                                repository,
                                GallerySmartAlbumRepository(db),
                                null,
                                {},
                                {},
                            )
                        }
                    }
                }
            }
            await {
                compose
                    .onAllNodesWithTag("memory-controls-empty")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            date()
            await { runBlocking { repository.dates().first().size == 1 } }
            runBlocking {
                assertEquals(
                    listOf(2L, 3L),
                    moments.members(momentId).map { it.media.mediaStoreId },
                )
                assertEquals(2L, moments.summaries().first().single().memberCount)
                assertEquals(2L, moments.summaries().first().single().coverMediaStoreId)
            }
            await {
                compose.onAllNodesWithTag("memory-controls-undo").fetchSemanticsNodes().isNotEmpty()
            }
            click("memory-controls-undo")
            await { runBlocking { repository.dates().first().isEmpty() } }
            runBlocking {
                assertEquals(3, moments.members(momentId).size)
                assertEquals(1L, moments.summaries().first().single().coverMediaStoreId)
            }
            click("memory-controls-add-person")
            await {
                compose
                    .onAllNodesWithTag("memory-controls-pick-person")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("memory-controls-pick-person").performClick()
            confirm()
            await { runBlocking { repository.people().first().size == 1 } }
            val rule = runBlocking { repository.people().first().single() }
            runBlocking {
                assertEquals(
                    listOf(2L, 3L),
                    moments.members(momentId).map { it.media.mediaStoreId },
                )
                db.openHelper.writableDatabase.execSQL("DELETE FROM person_clusters")
                db.openHelper.writableDatabase.execSQL("DELETE FROM face_detection_runs")
            }
            click("memory-controls-person-${rule.ruleId}")
            await {
                compose
                    .onAllNodesWithTag("memory-controls-missing-person")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithTag("memory-controls-list")
                .performScrollToNode(hasTestTag("memory-controls-missing-person"))
            compose.onNodeWithTag("memory-controls-missing-person").assertIsDisplayed()
            evidence(
                "review-$dark-$dynamic-$width-$font",
                "PASS: date hides story member/count/cover; Undo restores; person remains excluded after identity deletion; contrast=$contrast",
            )
            click("memory-controls-include")
            confirm()
            await { runBlocking { repository.people().first().isEmpty() } }
            runBlocking {
                assertEquals(3, moments.members(momentId).size)
                assertEquals(3, db.momentDao().allMembers(momentId).size)
                assertEquals(originals, (1L..3L).map { db.libraryDao().media(volume, it) })
                assertEquals("Keep title", db.momentDao().moment(momentId)?.title)
            }
            assertTrue(
                "Material text contrast $contrast",
                contrast.size == 4 && contrast.all { it >= 4.5 },
            )
        } finally {
            db.close()
        }
    }

    @Test fun compactLightCompleteFlow() = flow(false, false, 360, 1f)

    @Test fun compactDarkLargeTextCompleteFlow() = flow(true, false, 360, 1.6f)

    @Test fun expandedDynamicCompleteFlow() = flow(false, true, 840, 1f)

    @Test
    fun dateDraftRestoresWithoutWritingAndDiscardIsExplicit() {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repository = MemoryExclusionRepository(db)
        try {
            val state = StateRestorationTester(compose)
            state.setContent {
                UGalleryTheme {
                    MemoryControlsContent(repository, GallerySmartAlbumRepository(db), null, {}, {})
                }
            }
            await {
                compose
                    .onAllNodesWithTag("memory-controls-empty")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            click("memory-controls-add-dates")
            field("memory-controls-start", "2026-06-01")
            field("memory-controls-end", "2026-06-02")
            field("memory-controls-zone", "Europe/Paris")
            state.emulateSavedInstanceStateRestore()
            compose
                .onNodeWithTag("memory-controls-list")
                .performScrollToNode(hasTestTag("memory-controls-zone"))
            compose.onNodeWithTag("memory-controls-zone").assertTextContains("Europe/Paris")
            assertTrue(runBlocking { repository.dates().first().isEmpty() })
            compose.onNodeWithTag("memory-controls-back").performClick()
            compose.onNodeWithTag("memory-controls-cancel").performClick()
            click("memory-controls-save")
            compose.onNodeWithTag("memory-controls-cancel").performClick()
            assertTrue(runBlocking { repository.dates().first().isEmpty() })
            compose.onNodeWithTag("memory-controls-back").performClick()
            confirm()
            await {
                compose
                    .onAllNodesWithTag("memory-controls-empty")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun duplicateDoesNotOfferUndoAndEmptyPeopleDoNotStartAnalysis() {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repository = MemoryExclusionRepository(db)
        var analysis = 0
        try {
            runBlocking {
                repository.addDate(
                    MemoryDateRange(
                        LocalDate.parse("2026-06-01"),
                        LocalDate.parse("2026-06-01"),
                        "UTC",
                    )
                )
            }
            compose.setContent {
                UGalleryTheme {
                    MemoryControlsContent(
                        repository,
                        GallerySmartAlbumRepository(db),
                        null,
                        {},
                        { analysis++ },
                    )
                }
            }
            await {
                compose.onAllNodesWithTag("memory-controls-add-dates").fetchSemanticsNodes().any {
                    it.config
                        .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                        .not()
                }
            }
            date()
            await {
                compose
                    .onAllNodesWithTag("memory-controls-notice")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onAllNodesWithTag("memory-controls-undo").assertCountEquals(0)
            assertEquals(1, runBlocking { repository.dates().first().size })
            click("memory-controls-add-person")
            await {
                compose
                    .onAllNodesWithTag("memory-controls-no-people")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertEquals(0, analysis)
            compose.onNodeWithTag("memory-controls-analysis").performClick()
            assertEquals(1, analysis)
        } finally {
            db.close()
        }
    }
}
