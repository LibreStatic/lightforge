package com.librestatic.lightforge.core.search

import androidx.appsearch.app.AppSearchBatchResult
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SearchSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSearchScaleBenchmarkTest {
    @Test
    fun indexAndQuery100kDocuments() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val owner = LocalSearchSession(context)
        val session = owner.open("scale-benchmark").get(30, TimeUnit.SECONDS)
        try {
            session.setSchemaAsync(
                owner.schemaRequest(forceOverride = true),
            ).get(30, TimeUnit.SECONDS)

            val indexMs = measureTimeMillis {
                repeat(DOCUMENT_COUNT / BATCH_SIZE) { batch ->
                    val documents = List(BATCH_SIZE) { offset ->
                        val index = batch * BATCH_SIZE + offset
                        MediaSearchDocument(
                            key = MediaKey("external_primary", index.toLong()),
                            kind = MediaKind.Image,
                            mimeType = "image/jpeg",
                            displayName = "photo-$index.jpg",
                            bucketName = "Camera",
                            timelineSortMillis = index.toLong(),
                            generationModified = 1,
                            favorite = false,
                            ocrText = if (index % 10 == 0) "beach july" else "local photo",
                            ocrModelVersion = 1,
                        ).genericDocument()
                    }
                    val result = session.putAsync(
                        PutDocumentsRequest.Builder().addGenericDocuments(documents).build(),
                    ).get(60, TimeUnit.SECONDS)
                    assertTrue(result.failures.toString(), result.isSuccess)
                }
            }

            var firstPageCount = 0
            val queryMs = measureTimeMillis {
                val results = session.search(
                    "beach",
                    SearchSpec.Builder()
                        .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                        .setResultCountPerPage(100)
                        .build(),
                )
                firstPageCount = results.nextPageAsync.get(30, TimeUnit.SECONDS).size
                results.close()
            }
            assertEquals(100, firstPageCount)

            val output = JSONObject()
                .put("documents", DOCUMENT_COUNT)
                .put("batchSize", BATCH_SIZE)
                .put("indexMs", indexMs)
                .put("queryFirstPageMs", queryMs)
                .put("firstPageCount", firstPageCount)
                .put("runtime", "AppSearch LocalStorage")
            val directory = requireNotNull(context.getExternalFilesDir(null))
            File(directory, "appsearch-100k.json").writeText(output.toString(2) + "\n")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("lightforge.appsearch.metrics", output.toString())
            })
        } finally {
            session.close()
        }
    }

    private companion object {
        const val DOCUMENT_COUNT = 100_000
        const val BATCH_SIZE = MediaSearchIndex.MaxBatchSize
    }
}
