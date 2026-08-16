package com.ugallery.core.search

import android.content.Context
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import com.google.common.util.concurrent.ListenableFuture

class LocalSearchSession(private val context: Context) {
    fun open(databaseName: String = "media"): ListenableFuture<AppSearchSession> =
        LocalStorage.createSearchSessionAsync(
            LocalStorage.SearchContext.Builder(context, databaseName).build(),
        )

    fun schemaRequest(): SetSchemaRequest {
        val schema = AppSearchSchema.Builder("MediaDocument")
            .addProperty(AppSearchSchema.StringPropertyConfig.Builder("mediaKey")
                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                .build())
            .addProperty(AppSearchSchema.StringPropertyConfig.Builder("text")
                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                .build())
            .build()
        return SetSchemaRequest.Builder().addSchemas(schema).build()
    }
}

