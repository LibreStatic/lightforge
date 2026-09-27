package com.librestatic.lightforge.core.database

import androidx.room.Room
import androidx.room.withTransaction
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineIdentityDaoDeviceTest {
    private lateinit var db:GalleryDatabase
    @Before fun open() { db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,GalleryDatabase::class.java).build() }
    @After fun close() { db.close() }
    private fun media(id:Long,volume:String="external_primary") {
        db.openHelper.writableDatabase.execSQL("INSERT INTO media_items(volumeName,mediaStoreId,mediaType,mimeType,sizeBytes,width,height,durationMillis,orientationDegrees,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,generationAdded,generationModified,isFavorite,isTrashed,isAccessible,lastSeenScanId) VALUES(?,?,1,'image/jpeg',100,10,10,0,0,1,1,1000,1,2,0,0,1,1)",arrayOf<Any>(volume,id))
    }
    private fun observation(id:String,media:Long,volume:String="external_primary",group:String?="cat") = PetObservationEntity(id,volume,media,1,2,"model",0f,0f,1f,1f,0,.9f,ByteArray(2048),group,false)
    private suspend fun active() { db.petIdentityDao().ensureState(PetStateEntity(enabled=true,modelFingerprint="model"));db.petIdentityDao().putIdentities(listOf(PetIdentityEntity("cat",0,"Mora",1))) }
    private fun sql(s:String)=db.openHelper.writableDatabase.execSQL(s)

    @Test fun completedScanHidesUnseenRowsWithoutErasingIdentityDecisions() = runBlocking {
        media(1); media(2); active()
        val pets = db.petIdentityDao()
        pets.insertObservations(listOf(observation("visible", 1), observation("unavailable", 2)))
        sql("UPDATE media_items SET lastSeenScanId=99 WHERE mediaStoreId=1")
        val checkpoint = MediaStoreCheckpointEntity("external_primary", "v1", 3, 2, null, "Complete", 123)
        db.libraryDao().completeVolumeScan(checkpoint, 99)
        assertEquals(listOf("visible"), pets.observationsPage(null, 0, "", 64).map { it.id })
        assertFalse(db.libraryDao().media("external_primary", 2)!!.isAccessible)
        assertEquals("cat", pets.rawObservations(listOf("unavailable")).single().identityId)
        assertEquals("Mora", pets.identity("cat")!!.name)
        assertEquals(checkpoint, db.libraryDao().checkpoint("external_primary"))
        // A permission-hidden original can reappear without losing its group or local name.
        val returning = db.libraryDao().media("external_primary", 2)!!.copy(isAccessible = true, lastSeenScanId = 100)
        // Exercise the real scanner path, including its unchanged-generation optimization.
        db.libraryDao().reconcileMediaPage(listOf(returning), checkpoint.copy(activeScanId = 100, scanState = "Running"))
        assertTrue(db.libraryDao().media("external_primary", 2)!!.isAccessible)
        assertEquals(setOf("visible", "unavailable"), pets.observationsPage(null, 0, "", 64).map { it.id }.toSet())
        assertEquals("cat", pets.rawObservations(listOf("unavailable")).single().identityId)
    }

    @Test fun verifiedPublicationDuringScanKeepsSeenRowsVisibleWithoutPreservingAbsentRows() = runBlocking {
        media(1); media(2); active()
        val dao = db.libraryDao()
        val checkpoint = MediaStoreCheckpointEntity("external_primary", "v1", 3, 2, 99, "Running", 123)
        dao.upsertCheckpoint(checkpoint)
        val observed = dao.media("external_primary", 1)!!.copy(lastSeenScanId = 99)
        dao.reconcileMediaPage(listOf(observed), checkpoint)
        // A separate restore worker verified the provider bytes after this scan page was read.
        // Portable publication has no scanner token; its update must not erase the active mark.
        dao.upsertMedia(listOf(observed.copy(lastSeenScanId = 0), observed.copy(mediaStoreId = 3, lastSeenScanId = 0)))
        dao.completeVolumeScan(checkpoint.copy(activeScanId = null, scanState = "Complete"), 99)
        assertTrue(dao.media("external_primary", 1)!!.isAccessible)
        assertTrue(dao.media("external_primary", 3)!!.isAccessible)
        assertFalse(dao.media("external_primary", 2)!!.isAccessible)
    }

    @Test fun migration26To27PreservesMediaMemoriesAndRestoreReceipts() {
        val name="migration-v26-pet-peer.db"
        val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),GalleryDatabase::class.java)
        helper.createDatabase(name,26).use {
            it.execSQL("INSERT INTO media_items(volumeName,mediaStoreId,mediaType,mimeType,sizeBytes,width,height,durationMillis,orientationDegrees,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,generationAdded,generationModified,isFavorite,isTrashed,isAccessible,lastSeenScanId) VALUES('external_primary',701,1,'image/jpeg',100,10,10,0,0,1,1,1000,1,2,1,0,1,1)")
            it.execSQL("INSERT INTO moments VALUES('saved','AUTO','SAVED','v1',1,2,'Edited trip','USER',1,10,20)")
            it.execSQL("INSERT INTO gallery_restore_receipts VALUES('operation','snapshot',1,2,0,20)")
        }
        helper.runMigrationsAndValidate(name,27,true,GalleryDatabaseFactory.Migration26To27).use {
            it.query("SELECT generationAdded,generationModified,isFavorite FROM media_items").use { c -> assertTrue(c.moveToFirst());assertEquals(1L,c.getLong(0));assertEquals(2L,c.getLong(1));assertEquals(1,c.getInt(2)) }
            it.query("SELECT title,isUserEdited FROM moments").use { c -> assertTrue(c.moveToFirst());assertEquals("Edited trip",c.getString(0));assertEquals(1,c.getInt(1)) }
            it.query("SELECT snapshotId FROM gallery_restore_receipts").use { c -> assertTrue(c.moveToFirst());assertEquals("snapshot",c.getString(0)) }
            listOf("pet_state","pet_identities","pet_observations","pet_analysis_stamps","pet_undo","peer_import_versions","peer_import_sources").forEach { table ->
                it.query("SELECT COUNT(*) FROM $table").use { c -> assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0)) }
            }
        }
    }
    @Test fun currentPetQueriesRejectReusedIdsChangedSourcesAndHiddenRows() = runBlocking {
        (1L..6L).forEach { media(it) }; media(1,"other");active();val dao=db.petIdentityDao()
        dao.insertObservations((1L..6L).map { observation("p$it",it) }+observation("other",1,"other"))
        sql("UPDATE media_items SET generationAdded=5 WHERE mediaStoreId=2")
        sql("UPDATE media_items SET generationModified=5 WHERE mediaStoreId=3")
        sql("UPDATE media_items SET isAccessible=0 WHERE mediaStoreId=4")
        sql("UPDATE media_items SET isTrashed=1 WHERE mediaStoreId=5")
        sql("UPDATE media_items SET mediaType=3 WHERE mediaStoreId=6")
        assertEquals(listOf("other","p1"),dao.observationsPage(null,0,"",64).map { it.id })
        assertEquals(listOf("other","p1"),dao.referencePage(0,"model","",64).map { it.id })
        assertNull(dao.observationEmbedding("p2","model"));assertNull(dao.currentSource("external_primary",2,1,2))
        assertEquals(2L,dao.observationCount("cat",0))
        assertTrue(dao.referencePage(0,"different","",64).isEmpty())
        assertEquals(2048,dao.observationEmbedding("p1","model")!!.size)
    }
    @Test fun identityUpsertPreservesAssignmentsAndMediaRemovalCascadesOnlyItsObservations() = runBlocking {
        media(1);media(1,"other");active();val dao=db.petIdentityDao()
        dao.insertObservations(listOf(observation("p1",1),observation("p2",1,"other")))
        dao.putStamp(PetAnalysisStampEntity("external_primary",1,"model",1,2))
        dao.putIdentities(listOf(PetIdentityEntity("cat",0,"Renamed",1)))
        assertEquals("cat",dao.rawObservations(listOf("p1")).single().identityId)
        sql("DELETE FROM media_items WHERE volumeName='external_primary' AND mediaStoreId=1")
        assertEquals(listOf("p2"),dao.observationsPage(null,0,"",64).map { it.id })
        assertNull(dao.stamp("external_primary",1,"model"));assertEquals("Renamed",dao.identity("cat")!!.name)
    }
    @Test fun optoutRevisionAndNamespaceClearAreAtomicWithoutChangingOriginals() = runBlocking {
        media(1);active();val dao=db.petIdentityDao();dao.insertObservations(listOf(observation("p1",1)))
        dao.putStamp(PetAnalysisStampEntity("external_primary",1,"model",1,2));dao.putUndo(PetUndoEntity("token",0,"{}"))
        db.withTransaction {
            assertEquals(1,dao.compareAndSet(0,false,null,null));dao.clearUndo();dao.clearObservations();dao.clearStamps();dao.clearIdentities()
        }
        assertEquals(0,dao.compareAndSet(0,true,"model",null));assertEquals(1L,dao.state()!!.revision)
        assertTrue(dao.observationsPage(null,0,"",64).isEmpty());assertNull(dao.undo("token"));assertNull(dao.stamp("external_primary",1,"model"));assertEquals(0L,dao.identityCount())
        assertNotNull(dao.currentSource("external_primary",1,1,2))
    }
    @Test fun peerVersionReceiptsDistinguishPeerSourceAndRevisionAndRetainPriorBytes() = runBlocking {
        val dao=db.peerImportDao()
        fun version(peer:String,source:String,rev:String) = PeerImportVersionEntity(peer,source,rev,"operation","content://fixture/$peer/$source/$rev",null,null,1,2,"same-bytes",100,20)
        db.withTransaction {
            dao.insertVersion(version("peer1","source1","old"));dao.putSource(PeerImportSourceEntity("peer1","source1","old",10))
            dao.insertVersion(version("peer1","source1","new"));dao.putSource(PeerImportSourceEntity("peer1","source1","new",20))
            dao.insertVersion(version("peer1","source2","old"));dao.insertVersion(version("peer2","source1","old"))
        }
        assertEquals(4,dao.operationVersions("operation").size);assertEquals("new",dao.source("peer1","source1")!!.activeRevision)
        assertNotNull(dao.version("peer1","source1","old"));assertNotNull(dao.version("peer2","source1","old"))
        assertNull(dao.version("peer2","source2","old"));assertNull(dao.source("peer2","source1"))
        assertTrue(runCatching { dao.insertVersion(version("peer1","source1","old")) }.isFailure)
        assertEquals(4,dao.operationVersions("operation").size)
    }
    @Test fun revisionCompareAndSetRejectsStaleEditsAndRollsBackFailedTransactions() = runBlocking {
        active();val dao=db.petIdentityDao()
        assertEquals(1,dao.compareAndSet(0,true,"model","token"));assertEquals(0,dao.compareAndSet(0,true,"model","stale"))
        assertTrue(runCatching { db.withTransaction { dao.compareAndSet(1,false,null,null); error("injected before commit") } }.isFailure)
        assertEquals(1L,dao.state()!!.revision);assertTrue(dao.state()!!.enabled);assertEquals("token",dao.state()!!.undoToken)
    }
}
