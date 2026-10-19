package com.librestatic.lightforge.core.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The advanced storage opt-in (ADR-046) is an optional field of the settings export. */
class AdvancedStorageSettingsTest {
    private fun test(block: suspend (GallerySettingsRepository) -> Unit) = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("advanced-storage-settings").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "test.preferences_pb") }
        try {
            block(GallerySettingsRepository(store))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    private suspend fun GallerySettingsRepository.enabled() = settings.first().operations.advancedStorageEnabled

    private suspend fun GallerySettingsRepository.pending() = advancedStorageMigrationPending.first()

    private suspend fun GallerySettingsRepository.setWish(value: Boolean) =
        update { it.copy(operations = it.operations.copy(advancedStorageEnabled = value)) }

    @Test
    fun `is on by default`() = test { repo ->
        assertTrue(GallerySettings().operations.advancedStorageEnabled)
        assertTrue(repo.enabled())
    }

    @Test
    fun `persists and round trips through the export`() = test { repo ->
        repo.setWish(false)
        assertFalse(repo.enabled())
        val exported = repo.exportJson()
        assertFalse(exported.getJSONObject("operations").getBoolean("advancedStorageEnabled"))
        repo.reset()
        assertTrue(repo.enabled())
        repo.importJson(exported)
        assertFalse(repo.enabled())
    }

    @Test
    fun `an export without the field yields the default on`() = test { repo ->
        repo.setWish(false)
        repo.importJson(JSONObject("""{"operations":{"skipAppDeleteConfirmation":true}}"""))
        assertTrue(repo.enabled())
        repo.setWish(false)
        repo.importJson(JSONObject("{}"))
        assertTrue(repo.enabled())
    }

    @Test
    fun `importing a malformed or foreign value never fails`() = test { repo ->
        repo.importJson(JSONObject("""{"operations":{"advancedStorageEnabled":"yes"}}"""))
        assertTrue(repo.enabled())
        repo.importJson(JSONObject("""{"operations":{"advancedStorageEnabled":null,"someFutureKey":1}}"""))
        assertTrue(repo.enabled())
        assertEquals(true, repo.settings.first().operations.keepLastModifiedWhenPossible)
    }

    @Test
    fun `the new advanced fields default to off`() = test { repo ->
        val settings = repo.settings.first()
        assertFalse(settings.operations.advancedShowHidden)
        assertFalse(settings.operations.advancedDeleteEmptyFolders)
        assertFalse(settings.security.protectHiddenItems)
    }

    @Test
    fun `a full export with every advanced field imports without error and round trips`() = test { repo ->
        repo.update {
            it.copy(
                operations = it.operations.copy(advancedStorageEnabled = true, advancedShowHidden = true, advancedDeleteEmptyFolders = true),
                security = it.security.copy(appLockEnabled = true, protectHiddenItems = true),
            )
        }
        val exported = repo.exportJson()
        assertTrue(exported.getJSONObject("operations").getBoolean("advancedShowHidden"))
        assertTrue(exported.getJSONObject("operations").getBoolean("advancedDeleteEmptyFolders"))
        assertTrue(exported.getJSONObject("security").getBoolean("protectHiddenItems"))
        repo.reset()
        assertFalse(repo.settings.first().operations.advancedShowHidden)
        repo.importJson(exported)
        val back = repo.settings.first()
        assertTrue(back.operations.advancedStorageEnabled)
        assertTrue(back.operations.advancedShowHidden)
        assertTrue(back.operations.advancedDeleteEmptyFolders)
        assertTrue(back.security.protectHiddenItems)
    }

    @Test
    fun `malformed or absent new fields are ignored and the temporary state is never exported`() = test { repo ->
        repo.importJson(JSONObject("""{"operations":{"advancedShowHidden":"yes","advancedDeleteEmptyFolders":null},"security":{"protectHiddenItems":"x"}}"""))
        val settings = repo.settings.first()
        assertFalse(settings.operations.advancedShowHidden)
        assertFalse(settings.operations.advancedDeleteEmptyFolders)
        assertFalse(settings.security.protectHiddenItems)
        assertFalse(repo.exportJson().toString().contains("temporary", ignoreCase = true))
    }

    @Test
    fun `compact folder chains default on and survive export and import`() = test { repo ->
        assertTrue(repo.settings.first().operations.compactFolderChains)
        repo.update { it.copy(operations = it.operations.copy(compactFolderChains = false)) }
        val exported = repo.exportJson()
        assertFalse(exported.getJSONObject("operations").getBoolean("compactFolderChains"))
        repo.reset()
        assertTrue(repo.settings.first().operations.compactFolderChains)
        repo.importJson(exported)
        assertFalse(repo.settings.first().operations.compactFolderChains)
        repo.importJson(JSONObject("""{"operations":{}}"""))
        assertTrue(repo.settings.first().operations.compactFolderChains)
    }

    @Test
    fun `folder covers survive export and import and an older export has none`() = test { repo ->
        assertTrue(repo.settings.first().operations.folderCovers.isEmpty())
        val covers = mapOf("external_primary|DCIM/Trips/" to "external_primary:42")
        repo.update { it.copy(operations = it.operations.copy(folderCovers = covers)) }
        assertEquals(covers, repo.settings.first().operations.folderCovers)
        val exported = repo.exportJson()
        repo.reset()
        assertTrue(repo.settings.first().operations.folderCovers.isEmpty())
        repo.importJson(exported)
        assertEquals(covers, repo.settings.first().operations.folderCovers)
        repo.importJson(JSONObject("""{"operations":{}}"""))
        assertTrue(repo.settings.first().operations.folderCovers.isEmpty())
    }

    @Test
    fun `the start screen defaults to photos, round trips and ignores an unknown value`() = test { repo ->
        assertEquals(StartScreen.Photos, repo.settings.first().operations.startScreen)
        repo.update { it.copy(operations = it.operations.copy(startScreen = StartScreen.Folders)) }
        assertEquals(StartScreen.Folders, repo.settings.first().operations.startScreen)
        val exported = repo.exportJson()
        assertEquals("folders", exported.getJSONObject("operations").getString("startScreen"))
        repo.reset()
        assertEquals(StartScreen.Photos, repo.settings.first().operations.startScreen)
        repo.importJson(exported)
        assertEquals(StartScreen.Folders, repo.settings.first().operations.startScreen)
        repo.importJson(JSONObject("""{"operations":{"startScreen":"tomorrow"}}"""))
        assertEquals(StartScreen.Photos, repo.settings.first().operations.startScreen)
    }

    @Test
    fun `scanned folders persist and round trip through the export`() = test { repo ->
        assertEquals(emptySet<String>(), repo.settings.first().operations.scannedFolders)
        val paths = setOf("/storage/emulated/0/Scans", "/storage/ABCD-1234/Photos")
        repo.update { it.copy(operations = it.operations.copy(scannedFolders = paths)) }
        assertEquals(paths, repo.settings.first().operations.scannedFolders)
        val exported = repo.exportJson()
        assertEquals(2, exported.getJSONObject("operations").getJSONArray("scannedFolders").length())
        repo.reset()
        assertEquals(emptySet<String>(), repo.settings.first().operations.scannedFolders)
        repo.importJson(exported)
        assertEquals(paths, repo.settings.first().operations.scannedFolders)
    }

    @Test
    fun `a fresh install keeps the default and has nothing pending`() = test { repo ->
        assertFalse(repo.resolveOnboarding())
        repo.resolveAdvancedStorageDefault()
        assertTrue(repo.enabled())
        assertFalse(repo.pending())
    }

    @Test
    fun `an upgraded install without the key is turned off explicitly and offered the switch`() = test { repo ->
        repo.setOnboardingCompleted(true)
        repo.resolveAdvancedStorageDefault()
        assertFalse(repo.enabled())
        assertTrue(repo.pending())
    }

    @Test
    fun `an explicit stored on is kept and needs no offer`() = test { repo ->
        repo.setOnboardingCompleted(true)
        repo.setWish(true)
        repo.resolveAdvancedStorageDefault()
        assertTrue(repo.enabled())
        assertFalse(repo.pending())
    }

    @Test
    fun `a stored off on an upgraded install is still offered the switch`() = test { repo ->
        // Any settings write before the migration settles persists the wish as off.
        repo.setOnboardingCompleted(true)
        repo.setWish(false)
        repo.resolveAdvancedStorageDefault()
        assertFalse(repo.enabled())
        assertTrue(repo.pending())
    }

    @Test
    fun `an upgraded install reads as off before the migration settles`() = test { repo ->
        repo.setOnboardingCompleted(true)
        assertFalse(repo.enabled())
        repo.resolveAdvancedStorageDefault()
        assertFalse(repo.enabled())
    }

    @Test
    fun `an already resolved install is never touched again`() = test { repo ->
        repo.resolveAdvancedStorageDefault() // fresh: resolved
        repo.setOnboardingCompleted(true)
        repo.resolveAdvancedStorageDefault()
        assertTrue(repo.enabled())
        assertFalse(repo.pending())
    }

    @Test
    fun `a pending offer survives repeated resolves until it is answered`() = test { repo ->
        repo.setOnboardingCompleted(true)
        repo.resolveAdvancedStorageDefault()
        repo.resolveAdvancedStorageDefault()
        assertTrue(repo.pending())
        assertFalse(repo.enabled())
        repo.resolveAdvancedStorageMigration()
        assertFalse(repo.pending())
        assertFalse(repo.enabled())
        repo.resolveAdvancedStorageDefault()
        assertFalse(repo.pending())
    }

    @Test
    fun `reset and import leave the migration state alone`() = test { repo ->
        repo.setOnboardingCompleted(true)
        repo.resolveAdvancedStorageDefault()
        assertTrue(repo.pending())
        repo.reset()
        assertTrue(repo.pending())
        assertTrue(repo.enabled())
        repo.importJson(JSONObject("{}"))
        assertTrue(repo.pending())
        repo.resolveAdvancedStorageMigration()
        repo.reset()
        assertFalse(repo.pending())
    }
}
