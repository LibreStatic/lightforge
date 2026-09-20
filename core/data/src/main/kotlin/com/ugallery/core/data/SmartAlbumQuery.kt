package com.ugallery.core.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.ugallery.core.model.MediaKey

/**
 * User strings are parameters. Analysis is evidence only when generation and model identities
 * agree.
 */
internal object SmartAlbumQuery {
    const val MinimumLabelConfidence = 0.65f

    fun build(
        rule: SmartAlbumRule,
        albumId: String? = null,
        revision: String? = null,
        excluded: List<MediaKey> = emptyList(),
        count: Boolean = false,
        first: Boolean = false,
        source: MediaKey? = null,
        bounds: Pair<Long?, Long?> = rule.bounds(),
    ): SupportSQLiteQuery {
        require(!first || !count)
        require(excluded.size <= 200)
        require((albumId == null) == (revision == null))
        val args = mutableListOf<Any>()
        val where =
            mutableListOf(
                "m.isAccessible=1",
                "m.isTrashed=0",
                "m.mediaType=1",
                "NOT EXISTS (SELECT 1 FROM archived_media a WHERE a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)",
            )
        if (rule.favoritesOnly) where += "m.isFavorite=1"
        val (from, until) = bounds
        require((from == null) == (until == null))
        require(from == null || from < requireNotNull(until))
        if (from != null) {
            where += "m.timelineSortMillis>=? AND m.timelineSortMillis<?"
            args += from
            args += requireNotNull(until)
        }
        rule.normalized().topic?.let { topic ->
            where +=
                """EXISTS (SELECT 1 FROM media_labels l JOIN media_label_runs r
                ON r.volumeName=l.volumeName AND r.mediaStoreId=l.mediaStoreId
                WHERE l.volumeName=m.volumeName AND l.mediaStoreId=m.mediaStoreId
                AND r.generationModified=m.generationModified AND r.modelVersion=l.modelVersion
                AND l.canonicalLabel=? AND l.confidence>=?
                AND NOT EXISTS (SELECT 1 FROM label_suppressions s WHERE s.canonicalLabel=l.canonicalLabel))"""
            args += topic
            args += MinimumLabelConfidence
        }
        rule.personClusterId?.let { person ->
            where +=
                """EXISTS (SELECT 1 FROM person_memberships p JOIN person_clusters c ON c.clusterId=p.clusterId
                JOIN face_detection_runs r ON r.volumeName=p.volumeName AND r.mediaStoreId=p.mediaStoreId
                JOIN detected_faces f ON f.volumeName=p.volumeName AND f.mediaStoreId=p.mediaStoreId AND f.faceOrdinal=p.faceOrdinal
                JOIN face_embeddings e ON e.volumeName=p.volumeName AND e.mediaStoreId=p.mediaStoreId AND e.faceOrdinal=p.faceOrdinal
                WHERE p.volumeName=m.volumeName AND p.mediaStoreId=m.mediaStoreId AND p.clusterId=?
                AND c.algorithmVersion=? AND p.algorithmVersion=c.algorithmVersion AND c.isHidden=0
                AND r.generationModified=m.generationModified AND f.modelVersion=r.modelVersion
                AND e.detectionModelVersion=r.modelVersion)"""
            args += person
            args += requireNotNull(rule.personAlgorithmVersion)
        }
        if (albumId != null) {
            where += "EXISTS (SELECT 1 FROM smart_albums s WHERE s.albumId=? AND s.revision=?)"
            args += albumId
            args += requireNotNull(revision)
            where +=
                "NOT EXISTS (SELECT 1 FROM smart_album_exclusions x WHERE x.albumId=? AND x.volumeName=m.volumeName AND x.mediaStoreId=m.mediaStoreId)"
            args += albumId
        }
        for (key in excluded.distinct()) {
            where += "NOT (m.volumeName=? AND m.mediaStoreId=?)"
            args += key.volumeName
            args += key.mediaStoreId
        }
        if (source != null) {
            where += "m.volumeName=? AND m.mediaStoreId=?"
            args += source.volumeName
            args += source.mediaStoreId
        }
        val fields = if (count) "COUNT(*)" else "m.*"
        val order =
            if (count) ""
            else " ORDER BY m.timelineSortMillis DESC,m.mediaStoreId DESC,m.volumeName DESC"
        return SimpleSQLiteQuery(
            "SELECT $fields FROM media_items m WHERE ${where.joinToString(" AND ")}$order${if (first) " LIMIT 1" else ""}",
            args.toTypedArray(),
        )
    }
}
