package com.ugallery.core.search

import android.content.Context
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MediaSearchIndexDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun indexQueryDeleteAndVolumePurgeRemainLocalAndBounded() = runBlocking {
        val database = uniqueDatabase()
        val index = AppSearchMediaIndex(context, database)
        index.ensureSchema(forceOverride = true)
        index.clear()
        try {
            index.put(
                listOf(
                    document("external_primary", 1, ocr = "Factura número cuarenta", labels = listOf("document")),
                    document("sd-card", 2, ocr = "Playa de verano", labels = listOf("beach")),
                ),
            )
            assertEquals(listOf("external_primary:1"), queryIds(database, "factura"))
            assertEquals(listOf("sd-card:2"), queryIds(database, "bea"))

            index.remove(listOf(MediaKey("external_primary", 1)))
            assertTrue(queryIds(database, "factura").isEmpty())
            index.purgeVolume("sd-card")
            assertTrue(queryIds(database, "").isEmpty())
        } finally {
            index.close()
        }
    }

    @Test fun incompatibleSchemaCanBeDroppedAndRebuiltWithoutStaleFields() = runBlocking {
        val database = uniqueDatabase()
        val owner = LocalSearchSession(context)
        owner.open(database).get(30, TimeUnit.SECONDS).use { session ->
            val legacy = AppSearchSchema.Builder(MediaSearchSchema.Type)
                .addProperty(
                    AppSearchSchema.StringPropertyConfig.Builder("legacyModelOutput")
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                        .build(),
                ).build()
            session.setSchemaAsync(
                SetSchemaRequest.Builder().addSchemas(legacy).setForceOverride(true).build(),
            ).get(30, TimeUnit.SECONDS)
            session.putAsync(
                PutDocumentsRequest.Builder().addGenericDocuments(
                    GenericDocument.Builder<GenericDocument.Builder<*>>(
                        "media:external_primary", "legacy", MediaSearchSchema.Type,
                    ).setPropertyString("legacyModelOutput", "stale").build(),
                ).build(),
            ).get(30, TimeUnit.SECONDS)
        }

        AppSearchMediaIndex(context, database).use { index ->
            index.ensureSchema(forceOverride = true)
            assertTrue(queryIds(database, "").isEmpty())
            index.put(listOf(document("external_primary", 3, ocr = "fresh")))
            assertEquals(listOf("external_primary:3"), queryIds(database, "fresh"))
        }
    }

    @Test fun rebuildResumesAcrossCoordinatorRecreation() = runBlocking {
        val database = uniqueDatabase()
        val documents = (0L until 650L).map { document("external_primary", it, ocr = "local $it") }
        val source = SearchDocumentSource { after, limit ->
            documents.filter { after == null || it.key.mediaStoreId > after.mediaStoreId }.take(limit)
        }
        val state = SharedPreferencesSearchRebuildStateStore(context, database)
        state.clear()
        var checks = 0
        AppSearchMediaIndex(context, database).use { index ->
            val paused = SearchIndexCoordinator(index, source, state).rebuild { checks++ == 0 }
            assertEquals(500L, (paused as SearchRebuildResult.Paused).checkpoint.indexedCount)
        }
        AppSearchMediaIndex(context, database).use { index ->
            val completed = SearchIndexCoordinator(index, source, state).rebuild()
            assertEquals(SearchRebuildResult.Complete(650), completed)
        }
        assertEquals(650, queryIds(database, "local", pageSize = 73).size)
        assertEquals(null, state.read())
    }

    private fun queryIds(database: String, query: String, pageSize: Int = 100): List<String> {
        val owner = LocalSearchSession(context)
        return owner.open(database).get(30, TimeUnit.SECONDS).use { session ->
            val results = session.search(
                query,
                SearchSpec.Builder()
                    .addFilterSchemas(MediaSearchSchema.Type)
                    .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                    .setResultCountPerPage(pageSize)
                    .build(),
            )
            buildList {
                results.use {
                    while (true) {
                        val page = it.nextPageAsync.get(30, TimeUnit.SECONDS)
                        if (page.isEmpty()) break
                        page.forEach { result -> add(result.genericDocument.id) }
                    }
                }
            }.sorted()
        }
    }

    private fun document(
        volume: String,
        id: Long,
        ocr: String,
        labels: List<String> = emptyList(),
    ) = MediaSearchDocument(
        key = MediaKey(volume, id), kind = MediaKind.Image, mimeType = "image/jpeg",
        displayName = "$id.jpg", bucketName = "Camera", timelineSortMillis = id,
        generationModified = 1, favorite = false, ocrText = ocr,
        canonicalLabels = labels, ocrModelVersion = 1, labelModelVersion = 1,
    )

    private fun uniqueDatabase() = "m3-${UUID.randomUUID()}"
}
