package com.librestatic.lightforge.core.search

import android.content.Context
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import com.google.common.util.concurrent.ListenableFuture

class LocalSearchSession(private val context: Context) {
    fun open(databaseName: String = MediaSearchIndex.DefaultDatabase): ListenableFuture<AppSearchSession> =
        LocalStorage.createSearchSessionAsync(
            LocalStorage.SearchContext.Builder(context, databaseName).build(),
        )

    fun schemaRequest(forceOverride: Boolean = false): SetSchemaRequest = SetSchemaRequest.Builder()
        .addSchemas(MediaSearchSchema.schema())
        .setForceOverride(forceOverride)
        .build()
}

object MediaSearchSchema {
    const val Type = "MediaDocument"
    const val Version = 5L

    object Property {
        const val MediaKey = "mediaKey"
        const val VolumeName = "volumeName"
        const val MediaStoreId = "mediaStoreId"
        const val Kind = "kind"
        const val MimeType = "mimeType"
        const val DisplayName = "displayName"
        const val BucketName = "bucketName"
        const val OcrText = "ocrText"
        const val NormalizedText = "normalizedText"
        const val CanonicalLabels = "canonicalLabels"
        const val PersonIds = "personIds"
        const val TimelineSortMillis = "timelineSortMillis"
        const val GenerationModified = "generationModified"
        const val DurationMillis = "durationMillis"
        const val Width = "width"
        const val Height = "height"
        const val Favorite = "favorite"
        const val FavoriteToken = "favoriteToken"
        const val SchemaVersion = "schemaVersion"
        const val LabelModelVersion = "labelModelVersion"
        const val OcrModelVersion = "ocrModelVersion"
    }

    fun schema(): AppSearchSchema = AppSearchSchema.Builder(Type)
        .addProperty(exact(Property.MediaKey, required = true))
        .addProperty(exact(Property.VolumeName, required = true))
        .addProperty(number(Property.MediaStoreId, range = true))
        .addProperty(exact(Property.Kind, required = true))
        .addProperty(exact(Property.MimeType))
        .addProperty(prefix(Property.DisplayName))
        .addProperty(prefix(Property.BucketName))
        .addProperty(prefix(Property.OcrText))
        .addProperty(prefix(Property.NormalizedText))
        .addProperty(prefix(Property.CanonicalLabels, repeated = true))
        .addProperty(exact(Property.PersonIds, repeated = true))
        .addProperty(number(Property.TimelineSortMillis, range = true))
        .addProperty(number(Property.GenerationModified))
        .addProperty(optionalNumber(Property.DurationMillis))
        .addProperty(optionalNumber(Property.Width))
        .addProperty(optionalNumber(Property.Height))
        .addProperty(boolean(Property.Favorite))
        .addProperty(exact(Property.FavoriteToken, required = true))
        .addProperty(number(Property.SchemaVersion))
        .addProperty(number(Property.LabelModelVersion))
        .addProperty(number(Property.OcrModelVersion))
        .build()

    private fun exact(name: String, required: Boolean = false, repeated: Boolean = false) =
        AppSearchSchema.StringPropertyConfig.Builder(name)
            .setCardinality(cardinality(required, repeated))
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_VERBATIM)
            .build()

    private fun prefix(name: String, repeated: Boolean = false) =
        AppSearchSchema.StringPropertyConfig.Builder(name)
            .setCardinality(cardinality(required = false, repeated))
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
            .build()

    private fun number(name: String, range: Boolean = false) =
        AppSearchSchema.LongPropertyConfig.Builder(name)
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
            .setIndexingType(
                if (range) AppSearchSchema.LongPropertyConfig.INDEXING_TYPE_RANGE
                else AppSearchSchema.LongPropertyConfig.INDEXING_TYPE_NONE,
            )
            .build()

    private fun optionalNumber(name: String) = AppSearchSchema.LongPropertyConfig.Builder(name)
        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
        .setIndexingType(AppSearchSchema.LongPropertyConfig.INDEXING_TYPE_NONE)
        .build()

    private fun boolean(name: String) = AppSearchSchema.BooleanPropertyConfig.Builder(name)
        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
        .build()

    private fun cardinality(required: Boolean, repeated: Boolean): Int = when {
        repeated -> AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED
        required -> AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED
        else -> AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL
    }
}
