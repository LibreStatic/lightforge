package com.ugallery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val CurrentPet = """ FROM pet_observations o JOIN media_items m
 ON m.volumeName=o.volumeName AND m.mediaStoreId=o.mediaStoreId
 JOIN pet_state s ON s.id=1
 WHERE s.enabled=1 AND o.modelFingerprint=s.modelFingerprint
 AND o.generationAdded=m.generationAdded AND o.generationModified=m.generationModified
 AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType=1 """

/** All multi-step writes belong in the repository's single Room transaction/revision CAS. */
@Dao
interface PetIdentityDao {
    @Query("SELECT EXISTS(SELECT 1 FROM pet_state) + EXISTS(SELECT 1 FROM pet_identities) + EXISTS(SELECT 1 FROM pet_observations) + EXISTS(SELECT 1 FROM media_items)")
    fun observeInputs():Flow<Int>
    @Query("SELECT * FROM pet_state WHERE id=1") suspend fun state():PetStateEntity?
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun ensureState(value:PetStateEntity):Long
    @Query("UPDATE pet_state SET revision=revision+1,enabled=:enabled,modelFingerprint=:fingerprint,undoToken=:undoToken WHERE id=1 AND revision=:revision")
    suspend fun compareAndSet(revision:Long,enabled:Boolean,fingerprint:String?,undoToken:String?):Int
    @Query("SELECT * FROM pet_identities WHERE id>:afterId ORDER BY id LIMIT :limit")
    suspend fun identitiesPage(afterId:String,limit:Int):List<PetIdentityEntity>
    @Query("SELECT COUNT(*) FROM pet_identities") suspend fun identityCount():Long
    @Query("SELECT * FROM pet_identities WHERE id=:id") suspend fun identity(id:String):PetIdentityEntity?
    @Query("SELECT * FROM pet_identities WHERE id IN (:ids)") suspend fun identities(ids:List<String>):List<PetIdentityEntity>
    @Query("SELECT o.*" + CurrentPet + " AND (:identityId IS NULL OR o.identityId=:identityId) AND (:filter=0 OR (:filter=1 AND o.identityId IS NULL AND o.excluded=0) OR (:filter=2 AND o.excluded=1)) AND o.id>:afterId ORDER BY o.id LIMIT :limit")
    suspend fun observationsPage(identityId:String?,filter:Int,afterId:String,limit:Int):List<PetObservationEntity>
    @Query("SELECT COUNT(*)" + CurrentPet + " AND (:identityId IS NULL OR o.identityId=:identityId) AND (:filter=0 OR (:filter=1 AND o.identityId IS NULL AND o.excluded=0) OR (:filter=2 AND o.excluded=1))")
    suspend fun observationCount(identityId:String?,filter:Int):Long
    @Query("SELECT o.*" + CurrentPet + " AND o.id IN (:ids)")
    suspend fun currentObservations(ids:List<String>):List<PetObservationEntity>
    @Query("SELECT o.*" + CurrentPet + " AND o.identityId IS NOT NULL AND o.excluded=0 AND o.species=:species AND o.modelFingerprint=:fingerprint AND o.id>:afterId ORDER BY o.id LIMIT :limit")
    suspend fun referencePage(species:Int,fingerprint:String,afterId:String,limit:Int):List<PetObservationEntity>
    @Query("SELECT o.embedding" + CurrentPet + " AND o.id=:id AND o.excluded=0 AND o.modelFingerprint=:fingerprint")
    suspend fun observationEmbedding(id:String,fingerprint:String):ByteArray?
    @Query("SELECT * FROM pet_observations WHERE id IN (:ids)")
    suspend fun rawObservations(ids:List<String>):List<PetObservationEntity>
    @Query("SELECT * FROM pet_observations WHERE identityId IN (:ids) ORDER BY id LIMIT :limit")
    suspend fun identityObservations(ids:List<String>,limit:Int):List<PetObservationEntity>
    @Query("SELECT * FROM pet_observations WHERE volumeName=:volume AND mediaStoreId=:id AND modelFingerprint=:fingerprint")
    suspend fun sourceObservations(volume:String,id:Long,fingerprint:String):List<PetObservationEntity>
    @Query("SELECT * FROM media_items WHERE isAccessible=1 AND isTrashed=0 AND mediaType=1 AND (volumeName>:volume OR (volumeName=:volume AND mediaStoreId>:id)) ORDER BY volumeName,mediaStoreId LIMIT :limit")
    suspend fun eligibleSources(volume:String,id:Long,limit:Int):List<MediaItemEntity>
    @Query("SELECT * FROM media_items WHERE volumeName=:volume AND mediaStoreId=:id AND generationAdded=:added AND generationModified=:modified AND isAccessible=1 AND isTrashed=0 AND mediaType=1")
    suspend fun currentSource(volume:String,id:Long,added:Long,modified:Long):MediaItemEntity?
    @Query("SELECT * FROM pet_analysis_stamps WHERE volumeName=:volume AND mediaStoreId=:id AND modelFingerprint=:fingerprint")
    suspend fun stamp(volume:String,id:Long,fingerprint:String):PetAnalysisStampEntity?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertObservations(values:List<PetObservationEntity>)
    @Upsert suspend fun putIdentities(values:List<PetIdentityEntity>)
    @Query("UPDATE pet_identities SET name=:name WHERE id=:id") suspend fun renameIdentity(id:String,name:String?)
    @Query("UPDATE pet_observations SET identityId=:identityId,excluded=:excluded,species=:species WHERE id=:id")
    suspend fun updateReview(id:String,identityId:String?,excluded:Boolean,species:Int)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putStamp(value:PetAnalysisStampEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun putUndo(value:PetUndoEntity)
    @Query("SELECT * FROM pet_undo WHERE token=:token") suspend fun undo(token:String):PetUndoEntity?
    @Query("DELETE FROM pet_undo") suspend fun clearUndo()
    @Query("DELETE FROM pet_identities WHERE id IN (:ids)") suspend fun deleteIdentities(ids:List<String>)
    @Query("DELETE FROM pet_observations WHERE id IN (:ids)") suspend fun deleteObservations(ids:List<String>)
    @Query("DELETE FROM pet_observations") suspend fun clearObservations()
    @Query("DELETE FROM pet_analysis_stamps") suspend fun clearStamps()
    @Query("DELETE FROM pet_identities") suspend fun clearIdentities()
}
