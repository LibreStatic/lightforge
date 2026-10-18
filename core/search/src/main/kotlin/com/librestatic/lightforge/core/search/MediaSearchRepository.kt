package com.librestatic.lightforge.core.search

import android.content.Context
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.SearchResult
import androidx.appsearch.app.SearchResults
import androidx.appsearch.app.SearchSpec
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.TimeUnit

data class SearchRankingDebug(
    val queryExpression: String,
    val strategy: String,
    val rankingSignal: Double,
    val matchedProperties: List<String>,
)

data class MediaSearchHit(
    val key: MediaKey,
    val kind: MediaKind,
    val displayName: String?,
    val timelineSortMillis: Long,
    val generationModified: Long,
    val favorite: Boolean,
    val debug: SearchRankingDebug,
    val durationMillis: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

data class MediaSearchPage(
    val hits: List<MediaSearchHit>,
    val isTerminal: Boolean,
)

class MediaSearchCursor internal constructor(
    private val session: AppSearchSession,
    private val results: SearchResults,
    private val expression: String,
    private val strategy: String,
    /** Drops hits the index cannot judge itself (a folder scope); null keeps every hit. */
    private val hitFilter: (suspend (List<MediaSearchHit>) -> List<MediaSearchHit>)? = null,
) : Closeable {
    private var closed = false

    suspend fun nextPage(): MediaSearchPage = withContext(Dispatchers.IO) {
        check(!closed) { "Search cursor is closed" }
        var scanned = 0
        var result: MediaSearchPage? = null
        while (result == null) {
            val page = results.nextPageAsync.get(30, TimeUnit.SECONDS)
            if (page.isEmpty()) {
                close()
                result = MediaSearchPage(emptyList(), true)
                continue
            }
            val hits = page.map { it.hit(expression, strategy) }.let { hitFilter?.invoke(it) ?: it }
            // A scope can filter out a whole page; keep reading (bounded) so the caller is not handed an empty page
            // that looks like the end of the results.
            if (hits.isNotEmpty() || hitFilter == null || ++scanned >= MaxFilteredPages) {
                result = MediaSearchPage(hits, false)
            }
        }
        checkNotNull(result)
    }

    override fun close() {
        if (closed) return
        closed = true
        results.close()
        session.close()
    }

    private fun SearchResult.hit(expression: String, strategy: String): MediaSearchHit {
        val document = genericDocument
        return MediaSearchHit(
            key = MediaKey(
                requireNotNull(document.getPropertyString(MediaSearchSchema.Property.VolumeName)),
                document.getPropertyLong(MediaSearchSchema.Property.MediaStoreId),
            ),
            kind = if (document.getPropertyString(MediaSearchSchema.Property.Kind) == "video") {
                MediaKind.Video
            } else MediaKind.Image,
            displayName = document.getPropertyString(MediaSearchSchema.Property.DisplayName),
            timelineSortMillis = document.getPropertyLong(MediaSearchSchema.Property.TimelineSortMillis),
            generationModified = document.getPropertyLong(MediaSearchSchema.Property.GenerationModified),
            favorite = document.getPropertyBoolean(MediaSearchSchema.Property.Favorite),
            debug = SearchRankingDebug(
                expression,
                strategy,
                rankingSignal,
                matchInfos.map { it.propertyPath }.distinct(),
            ),
            durationMillis = document.getPropertyLong(MediaSearchSchema.Property.DurationMillis),
            width = document.getPropertyLong(MediaSearchSchema.Property.Width).toInt().coerceAtLeast(0),
            height = document.getPropertyLong(MediaSearchSchema.Property.Height).toInt().coerceAtLeast(0),
        )
    }
}

private const val MaxFilteredPages = 20

class AppSearchMediaSearchRepository(
    context: Context,
    private val databaseName: String = MediaSearchIndex.DefaultDatabase,
    private val parser: SearchQueryParser = SearchQueryParser(),
) {
    private val owner = LocalSearchSession(context.applicationContext)

    fun parse(raw: String): ParsedSearchQuery = parser.parse(raw)

    suspend fun search(raw: String, pageSize: Int = 100): MediaSearchCursor = search(parse(raw), pageSize)

    suspend fun search(
        query: ParsedSearchQuery,
        pageSize: Int = 100,
        hitFilter: (suspend (List<MediaSearchHit>) -> List<MediaSearchHit>)? = null,
    ): MediaSearchCursor =
        withContext(Dispatchers.IO) {
            require(pageSize in 1..1_000)
            val session = owner.open(databaseName).get(30, TimeUnit.SECONDS)
            try {
                val expression = query.expression()
                val textRanking = query.normalizedTerms.isNotEmpty()
                val strategy = if (textRanking) "relevance" else "newest"
                val spec = SearchSpec.Builder()
                    .addFilterSchemas(MediaSearchSchema.Type)
                    .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                    .setNumericSearchEnabled(true)
                    .setResultCountPerPage(pageSize)
                    .setSnippetCount(pageSize)
                    .setSnippetCountPerProperty(1)
                    .setMaxSnippetSize(160)
                    .setRankingStrategy(
                        if (textRanking) SearchSpec.RANKING_STRATEGY_RELEVANCE_SCORE
                        else SearchSpec.RANKING_STRATEGY_CREATION_TIMESTAMP,
                    )
                    .setOrder(SearchSpec.ORDER_DESCENDING)
                    .build()
                MediaSearchCursor(session, session.search(expression, spec), expression, strategy, hitFilter)
            } catch (failure: Throwable) {
                session.close()
                throw failure
            }
        }
}

internal fun ParsedSearchQuery.expression(): String = buildList {
    normalizedTerms.forEach(::add)
    kind?.let { add("${MediaSearchSchema.Property.Kind}:${it.name.lowercase()}") }
    if (favoriteOnly) add("${MediaSearchSchema.Property.FavoriteToken}:favorite")
    personIds.forEach { add("${MediaSearchSchema.Property.PersonIds}:$it") }
    fromMillisInclusive?.let { add("${MediaSearchSchema.Property.TimelineSortMillis} >= $it") }
    toMillisExclusive?.let { add("${MediaSearchSchema.Property.TimelineSortMillis} < $it") }
}.joinToString(" AND ")
