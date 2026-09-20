package com.ugallery.core.database

/** Indexed joins over selected identities, not an in-memory scan of the library. */
internal const val MemoryCurrentPersonMatches =
    """
SELECT x.ruleId,m.volumeName,m.mediaStoreId
FROM memory_person_exclusions x JOIN person_clusters c
ON c.clusterId=x.clusterId AND c.algorithmVersion=x.algorithmVersion
JOIN person_memberships p ON p.clusterId=c.clusterId AND p.algorithmVersion=c.algorithmVersion
JOIN media_items m ON m.volumeName=p.volumeName AND m.mediaStoreId=p.mediaStoreId
JOIN face_detection_runs r ON r.volumeName=p.volumeName AND r.mediaStoreId=p.mediaStoreId
JOIN detected_faces f ON f.volumeName=p.volumeName AND f.mediaStoreId=p.mediaStoreId AND f.faceOrdinal=p.faceOrdinal
JOIN face_embeddings e ON e.volumeName=p.volumeName AND e.mediaStoreId=p.mediaStoreId AND e.faceOrdinal=p.faceOrdinal
WHERE m.mediaType=1 AND r.generationModified=m.generationModified
AND f.modelVersion=r.modelVersion AND e.detectionModelVersion=r.modelVersion
"""

/** Only explicit classifications are documents; a rejected suggestion (Excluded) stays eligible. */
internal const val MemoryConfirmedDocumentCategories = "('Receipt','Ticket','Note','Other')"

internal const val MemoryNotConfirmedDocument =
    "NOT EXISTS (SELECT 1 FROM document_annotations document WHERE " +
        "document.volumeName=m.volumeName AND document.mediaStoreId=m.mediaStoreId " +
        "AND document.category IN " + MemoryConfirmedDocumentCategories + ")"

/** A reversible view filter. Generation retains references so removing a rule restores stories. */
internal const val MemoryVisibleCte =
    """
WITH current_hidden AS (
""" +
        MemoryCurrentPersonMatches +
        """
), memory_base AS (
SELECT m.* FROM media_items m WHERE m.mediaType=1 AND m.isAccessible=1 AND m.isTrashed=0
AND NOT EXISTS (SELECT 1 FROM archived_media a WHERE a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)
AND NOT EXISTS (SELECT 1 FROM memory_date_exclusions d WHERE m.timelineSortMillis>=d.fromMillis AND m.timelineSortMillis<d.untilMillis)
AND NOT EXISTS (SELECT 1 FROM memory_person_sources s WHERE s.volumeName=m.volumeName AND s.mediaStoreId=m.mediaStoreId)
AND NOT EXISTS (SELECT 1 FROM current_hidden h WHERE h.volumeName=m.volumeName AND h.mediaStoreId=m.mediaStoreId)
), memory_visible AS (
SELECT m.*, vm.momentId AS visibleMomentId FROM memory_base m
JOIN moment_members vm ON vm.volumeName=m.volumeName AND vm.mediaStoreId=m.mediaStoreId
JOIN moments owner ON owner.momentId=vm.momentId
WHERE owner.includeSpecialMedia=1 OR """ + MemoryNotConfirmedDocument + """
)
"""
