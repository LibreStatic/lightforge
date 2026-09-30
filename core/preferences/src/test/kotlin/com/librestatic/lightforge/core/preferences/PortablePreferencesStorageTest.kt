package com.librestatic.lightforge.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class PortablePreferencesStorageTest {
    @Test
    fun exportAndReviewDoNotTurnStorageFailureIntoDefaultBackup() = runBlocking {
        val store =
            object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow {
                    throw IOException("fixture read failure")
                }

                override suspend fun updateData(
                    transform: suspend (Preferences) -> Preferences
                ): Preferences = throw IOException("fixture write failure")
            }
        val repository = GallerySettingsRepository(store)
        // Existing live-settings display fallback is deliberately separate from archive reads.
        assertEquals(GallerySettings(), repository.settings.first())
        try {
            repository.exportJson()
            fail("Default settings exported after read failure")
        } catch (expected: IOException) {
            assertEquals("fixture read failure", expected.message)
        }
        try {
            repository.review("{}".toByteArray(), UUID.randomUUID().toString())
            fail("Review accepted failed storage")
        } catch (expected: IOException) {
            assertEquals("fixture read failure", expected.message)
        }
    }

    @Test
    fun resetPreservesUnrelatedFutureKeysInsteadOfClearingDataStore() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("portable-reset").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store =
            PreferenceDataStoreFactory.create(scope = scope) {
                File(directory, "test.preferences_pb")
            }
        val future = stringPreferencesKey("future.owned.receipt")
        try {
            store.edit { it[future] = "do-not-replay" }
            val repository = GallerySettingsRepository(store)
            repository.update { it.copy(playback = it.playback.copy(loopVideos = true)) }
            repository.reset()
            assertEquals(GallerySettings(), repository.settings.first())
            assertEquals("do-not-replay", store.data.first()[future])
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
