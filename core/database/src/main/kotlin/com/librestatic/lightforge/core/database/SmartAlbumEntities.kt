package com.librestatic.lightforge.core.database

import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** User rules are independent of replaceable local-analysis rows and cluster lifetimes. */
@Entity(tableName = "smart_albums")
data class SmartAlbumEntity(
    @PrimaryKey val albumId: String,
    val name: String,
    val topic: String?,
    val personClusterId: String?,
    val personAlgorithmVersion: String?,
    val year: Int?,
    val zoneId: String,
    val fromMillis: Long?,
    val untilMillis: Long?,
    val favoritesOnly: Boolean,
    val revision: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "smart_album_exclusions",
    primaryKeys = ["albumId", "volumeName", "mediaStoreId"],
    foreignKeys =
        [
            ForeignKey(
                entity = SmartAlbumEntity::class,
                parentColumns = ["albumId"],
                childColumns = ["albumId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["volumeName", "mediaStoreId"],
                childColumns = ["volumeName", "mediaStoreId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["volumeName", "mediaStoreId"])],
)
data class SmartAlbumExclusionEntity(
    val albumId: String,
    val volumeName: String,
    val mediaStoreId: Long,
    val token: String,
    val excludedAtMillis: Long,
)

data class SmartAlbumPersonOption(
    val clusterId: String,
    val algorithmVersion: String,
    val displayName: String?,
)

@Dao
interface SmartAlbumDao {
    @Query("SELECT * FROM smart_albums ORDER BY updatedAtMillis DESC, albumId")
    fun albums(): PagingSource<Int, SmartAlbumEntity>

    @Query("SELECT * FROM smart_albums WHERE albumId=:id")
    suspend fun get(id: String): SmartAlbumEntity?

    @Query("SELECT * FROM smart_albums WHERE albumId=:id")
    fun observe(id: String): Flow<SmartAlbumEntity?>

    @Insert suspend fun insert(album: SmartAlbumEntity)

    @Update suspend fun update(album: SmartAlbumEntity)

    @Query("DELETE FROM smart_albums WHERE albumId=:id") suspend fun delete(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun exclude(value: SmartAlbumExclusionEntity)

    @Query(
        "DELETE FROM smart_album_exclusions WHERE albumId=:id AND volumeName=:volume AND mediaStoreId=:media AND token=:token"
    )
    suspend fun undoExclude(id: String, volume: String, media: Long, token: String): Int

    @Query(
        "DELETE FROM smart_album_exclusions WHERE albumId=:id AND volumeName=:volume AND mediaStoreId=:media"
    )
    suspend fun includeAgain(id: String, volume: String, media: Long): Int

    @Query(
        "SELECT * FROM smart_album_exclusions WHERE albumId=:id ORDER BY excludedAtMillis DESC,volumeName,mediaStoreId"
    )
    fun exclusions(id: String): PagingSource<Int, SmartAlbumExclusionEntity>

    @RawQuery(
        observedEntities =
            [
                MediaItemEntity::class,
                ArchivedMediaEntity::class,
                SmartAlbumEntity::class,
                SmartAlbumExclusionEntity::class,
                MediaLabelEntity::class,
                MediaLabelRunEntity::class,
                LabelSuppressionEntity::class,
                PersonClusterEntity::class,
                PersonMembershipEntity::class,
                FaceDetectionRunEntity::class,
                DetectedFaceEntity::class,
                FaceEmbeddingEntity::class,
            ]
    )
    fun media(query: SupportSQLiteQuery): PagingSource<Int, MediaItemEntity>

    @RawQuery(
        observedEntities =
            [
                MediaItemEntity::class,
                ArchivedMediaEntity::class,
                SmartAlbumEntity::class,
                SmartAlbumExclusionEntity::class,
                MediaLabelEntity::class,
                MediaLabelRunEntity::class,
                LabelSuppressionEntity::class,
                PersonClusterEntity::class,
                PersonMembershipEntity::class,
                FaceDetectionRunEntity::class,
                DetectedFaceEntity::class,
                FaceEmbeddingEntity::class,
            ]
    )
    fun count(query: SupportSQLiteQuery): Flow<Long>

    @RawQuery suspend fun countNow(query: SupportSQLiteQuery): Long

    @Query(
        "SELECT clusterId, algorithmVersion, displayName FROM person_clusters WHERE isHidden=0 ORDER BY displayName,clusterId"
    )
    fun people(): PagingSource<Int, SmartAlbumPersonOption>

    @Query(
        "SELECT clusterId, algorithmVersion, displayName FROM person_clusters WHERE clusterId=:id AND algorithmVersion=:version AND isHidden=0"
    )
    suspend fun person(id: String, version: String): SmartAlbumPersonOption?

    @Query(
        """SELECT DISTINCT l.canonicalLabel FROM media_labels l JOIN media_label_runs r
        ON r.volumeName=l.volumeName AND r.mediaStoreId=l.mediaStoreId
        JOIN media_items m ON m.volumeName=l.volumeName AND m.mediaStoreId=l.mediaStoreId
        WHERE m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType=1
        AND r.generationModified=m.generationModified AND r.modelVersion=l.modelVersion
        AND l.confidence>=:minimumConfidence
        AND NOT EXISTS (SELECT 1 FROM label_suppressions s WHERE s.canonicalLabel=l.canonicalLabel)
        AND NOT EXISTS (SELECT 1 FROM archived_media a WHERE a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)
        ORDER BY l.canonicalLabel"""
    )
    fun topics(minimumConfidence: Float): PagingSource<Int, String>

    @Query(
        "SELECT clusterId, algorithmVersion, displayName FROM person_clusters WHERE clusterId=:id AND algorithmVersion=:version AND isHidden=0"
    )
    fun observePerson(id: String, version: String): Flow<SmartAlbumPersonOption?>

    @Query(
        "SELECT * FROM media_items WHERE volumeName=:volume AND mediaStoreId=:media AND isAccessible=1 AND isTrashed=0"
    )
    fun source(volume: String, media: Long): Flow<MediaItemEntity?>

    @RawQuery(
        observedEntities =
            [
                MediaItemEntity::class,
                ArchivedMediaEntity::class,
                SmartAlbumEntity::class,
                SmartAlbumExclusionEntity::class,
                MediaLabelEntity::class,
                MediaLabelRunEntity::class,
                LabelSuppressionEntity::class,
                PersonClusterEntity::class,
                PersonMembershipEntity::class,
                FaceDetectionRunEntity::class,
                DetectedFaceEntity::class,
                FaceEmbeddingEntity::class,
            ]
    )
    fun firstMedia(query: androidx.sqlite.db.SupportSQLiteQuery): Flow<MediaItemEntity?>
}
