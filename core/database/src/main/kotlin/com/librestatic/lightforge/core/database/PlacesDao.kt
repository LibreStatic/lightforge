package com.librestatic.lightforge.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private const val EligiblePlaces = """ FROM media_items m JOIN media_exif_cache e
    ON e.volumeName=m.volumeName AND e.mediaStoreId=m.mediaStoreId
    AND e.generationModified=m.generationModified
    WHERE m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType IN (1,3)
    AND e.locationReadWithPermission=1 AND e.latitude BETWEEN -90.0 AND 90.0
    AND e.longitude BETWEEN -180.0 AND 180.0
    AND NOT EXISTS(SELECT 1 FROM archived_media a WHERE a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId) """
private const val PlacesViewport = """ AND e.latitude BETWEEN :south AND :north
    AND ((:west<=:east AND e.longitude BETWEEN :west AND :east)
      OR (:west>:east AND (e.longitude>=:west OR e.longitude<=:east)))
    AND (:startMillis IS NULL OR m.timelineSortMillis>=:startMillis)
    AND (:endMillis IS NULL OR m.timelineSortMillis<:endMillis) """

data class PlacesMediaRow(val volumeName: String,val mediaStoreId: Long,val latitude: Double,
    val longitude: Double,val timelineSortMillis: Long,val generationModified: Long)
data class PlacesMediaSnapshot(val rows: List<PlacesMediaRow>,val total: Long)

@Dao
interface PlacesDao {
    /** Observes these tables without scanning all rows. Consumers turn each invalidation into an epoch. */
    @Query("""SELECT EXISTS(SELECT 1 FROM media_items LIMIT 1)
        + EXISTS(SELECT 1 FROM media_exif_cache LIMIT 1)
        + EXISTS(SELECT 1 FROM archived_media LIMIT 1)""")
    fun observeInputs(): Flow<Int>

    /** Keyset traversal prevents corrupt/unsupported files from blocking later photos. */
    @Query("""SELECT m.* FROM media_items m LEFT JOIN media_exif_cache e
        ON e.volumeName=m.volumeName AND e.mediaStoreId=m.mediaStoreId
        WHERE m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType=1
        AND (m.volumeName>:afterVolume OR (m.volumeName=:afterVolume AND m.mediaStoreId>:afterId))
        AND NOT EXISTS(SELECT 1 FROM archived_media a WHERE a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)
        AND (e.mediaStoreId IS NULL OR e.generationModified<>m.generationModified OR e.locationReadWithPermission=0)
        ORDER BY m.volumeName,m.mediaStoreId LIMIT :limit""")
    suspend fun pendingExifPage(afterVolume:String,afterId:Long,limit:Int):List<MediaItemEntity>

    @Query("SELECT DISTINCT CAST(strftime('%Y',m.timelineSortMillis/1000.0,'unixepoch') AS INTEGER)" + EligiblePlaces +
        " AND strftime('%Y',m.timelineSortMillis/1000.0,'unixepoch') IS NOT NULL ORDER BY 1 DESC")
    suspend fun years(): List<Int>

    @Query("SELECT COUNT(*)" + EligiblePlaces + PlacesViewport)
    suspend fun count(south:Double,north:Double,west:Double,east:Double,startMillis:Long?,endMillis:Long?):Long

    @Query("SELECT m.volumeName,m.mediaStoreId,e.latitude,e.longitude,m.timelineSortMillis,m.generationModified" +
        EligiblePlaces + PlacesViewport + " ORDER BY m.timelineSortMillis DESC,m.volumeName,m.mediaStoreId LIMIT :limit")
    suspend fun page(south:Double,north:Double,west:Double,east:Double,startMillis:Long?,endMillis:Long?,limit:Int):List<PlacesMediaRow>

    @Transaction
    suspend fun snapshot(south:Double,north:Double,west:Double,east:Double,startMillis:Long?,endMillis:Long?,limit:Int):PlacesMediaSnapshot {
        require(limit in 1..2000)
        require(south.isFinite() && north.isFinite() && west.isFinite() && east.isFinite())
        require(south in -90.0..90.0 && north in south..90.0 && west in -180.0..180.0 && east in -180.0..180.0)
        require(startMillis==null || endMillis==null || startMillis<endMillis)
        val rows=page(south,north,west,east,startMillis,endMillis,limit)
        return PlacesMediaSnapshot(rows,count(south,north,west,east,startMillis,endMillis))
    }
}
