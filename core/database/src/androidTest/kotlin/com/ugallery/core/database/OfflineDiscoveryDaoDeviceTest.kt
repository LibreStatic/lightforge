package com.ugallery.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineDiscoveryDaoDeviceTest {
    private lateinit var db: GalleryDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, GalleryDatabase::class.java).build() }
    @After fun close() { db.close() }
    private fun media(id: Long, time: Long = 1000, lon: Double = 10.0, volume: String = "external_primary") {
        db.openHelper.writableDatabase.execSQL("INSERT INTO media_items(volumeName,mediaStoreId,mediaType,mimeType,sizeBytes,width,height,durationMillis,orientationDegrees,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,generationAdded,generationModified,isFavorite,isTrashed,isAccessible,lastSeenScanId) VALUES(?,?,1,'image/jpeg',100,10,10,0,0,1,1,?,1,2,0,0,1,1)", arrayOf<Any>(volume,id,time))
        db.openHelper.writableDatabase.execSQL("INSERT INTO media_exif_cache(volumeName,mediaStoreId,generationModified,orientation,latitude,longitude,locationReadWithPermission,cachedAtMillis) VALUES(?,?,2,0,20.0,?,1,1)", arrayOf<Any>(volume,id,lon))
    }
    private fun sql(value: String) = db.openHelper.writableDatabase.execSQL(value)
    private fun embedding(id: Long, generation: Long = 2, version: String = "v1", volume: String = "external_primary") = SemanticEmbeddingEntity("index",volume,id,generation,version,byteArrayOf(1,2,3),1,1,1,1,1,1,1,1,1000)
    private suspend fun index() = db.semanticDao().upsertIndex(SemanticIndexEntity("index","model","v1","building",0,1,1))

    @Test fun placesRejectStaleUnauthorizedHiddenAndInvalidCoordinates() = runBlocking {
        (1L..8L).forEach { media(it) }
        sql("UPDATE media_exif_cache SET generationModified=1 WHERE mediaStoreId=2")
        sql("UPDATE media_exif_cache SET locationReadWithPermission=0 WHERE mediaStoreId=3")
        sql("UPDATE media_items SET isAccessible=0 WHERE mediaStoreId=4")
        sql("UPDATE media_items SET isTrashed=1 WHERE mediaStoreId=5")
        sql("INSERT INTO archived_media VALUES('external_primary',6,1)")
        sql("UPDATE media_exif_cache SET latitude=91 WHERE mediaStoreId=7")
        sql("UPDATE media_items SET mediaType=2 WHERE mediaStoreId=8")
        val page=db.placesDao().snapshot(-90.0,90.0,-180.0,180.0,null,null,2000)
        assertEquals(listOf(1L),page.rows.map { it.mediaStoreId }); assertEquals(1L,page.total)
    }
    @Test fun placesAntimeridianUtcYearAndOverflowRemainExplicit() = runBlocking {
        media(1,-1,179.0); media(2,0,-179.0); media(3,1000,0.0)
        media(1,2000,179.0,"other")
        val dao=db.placesDao()
        assertEquals(listOf(1970,1969),dao.years())
        val page=dao.snapshot(-90.0,90.0,170.0,-170.0,null,null,1)
        assertEquals(1,page.rows.size); assertEquals(3L,page.total)
        assertEquals("other",page.rows.single().volumeName)
        assertEquals(listOf(1L),dao.snapshot(-90.0,90.0,170.0,-170.0,-31536000000,0,10).rows.map { it.mediaStoreId })
        assertEquals(2L,dao.snapshot(-90.0,90.0,170.0,-170.0,0,31536000000,10).total)
    }
    @Test fun photoLocationDiscoveryTraversesMissingExifWithoutReopeningAuthorizedNoGpsRows() = runBlocking {
        media(1); media(2); media(3); media(4); media(1,volume="other")
        sql("UPDATE media_exif_cache SET latitude=NULL,longitude=NULL WHERE mediaStoreId=1 AND volumeName='external_primary'")
        sql("DELETE FROM media_exif_cache WHERE mediaStoreId=2")
        sql("UPDATE media_exif_cache SET generationModified=1 WHERE mediaStoreId=3")
        sql("UPDATE media_exif_cache SET locationReadWithPermission=0 WHERE mediaStoreId=4 OR volumeName='other'")
        val dao=db.placesDao()
        assertEquals(listOf(2L,3L),dao.pendingExifPage("",-1,2).map { it.mediaStoreId })
        assertEquals(listOf("external_primary" to 4L,"other" to 1L),dao.pendingExifPage("external_primary",3,2).map { it.volumeName to it.mediaStoreId })
        assertTrue(dao.pendingExifPage("other",1,2).isEmpty())
        sql("INSERT INTO archived_media VALUES('external_primary',4,1)")
        assertEquals(listOf("other"),dao.pendingExifPage("external_primary",3,2).map { it.volumeName })
    }
    @Test fun lateInferenceNeverResurrectsDeletedIndexOrChangedSource() = runBlocking {
        media(1); media(2); index(); val dao=db.semanticDao()
        sql("UPDATE media_items SET generationModified=3 WHERE mediaStoreId=2")
        assertEquals(1,dao.upsertCurrentEmbeddings("index",listOf(embedding(1),embedding(2))))
        assertEquals(0,dao.upsertCurrentEmbeddings("index",listOf(embedding(1,version="old"))))
        dao.deleteIndex("index")
        assertEquals(0,dao.upsertCurrentEmbeddings("index",listOf(embedding(1))))
        assertEquals(0,dao.markIndexActiveIfPresent("index",1,10))
        assertNull(dao.index("index")); assertEquals(0L,dao.embeddingCount("index"))
        assertTrue(dao.pendingMedia("index","v1",10).isEmpty())
    }
    @Test fun everyCandidateBandFiltersGenerationAccessTrashAndModelVersion() = runBlocking {
        (1L..6L).forEach { media(it) }; media(1,volume="other"); index(); val dao=db.semanticDao()
        dao.upsertEmbeddings((1L..6L).map { embedding(it) } + embedding(1,volume="other"))
        sql("UPDATE media_items SET generationModified=3 WHERE mediaStoreId=2")
        sql("UPDATE media_items SET isAccessible=0 WHERE mediaStoreId=3")
        sql("UPDATE media_items SET isTrashed=1 WHERE mediaStoreId=4")
        sql("UPDATE semantic_embeddings SET modelVersion='old' WHERE mediaStoreId=5")
        sql("UPDATE media_items SET mediaType=2 WHERE mediaStoreId=6")
        val bands=listOf(dao.band0("index",listOf(1),20),dao.band1("index",listOf(1),20),dao.band2("index",listOf(1),20),dao.band3("index",listOf(1),20),dao.band4("index",listOf(1),20),dao.band5("index",listOf(1),20),dao.band6("index",listOf(1),20),dao.band7("index",listOf(1),20),dao.embeddingPage("index",0,20))
        bands.forEach { rows -> assertEquals(listOf("external_primary","other"),rows.map { it.volumeName }); assertTrue(rows.all { it.mediaStoreId==1L && it.generationModified==2L }) }
        assertEquals(setOf(2L,5L),dao.pendingMedia("index","v1",20).map { it.mediaStoreId }.toSet())
        assertEquals(0,dao.upsertCurrentEmbeddings("index",listOf(embedding(3),embedding(4),embedding(6))))
    }
}
