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

    @Test
    fun `is off by default`() = test { repo ->
        assertFalse(GallerySettings().operations.advancedStorageEnabled)
        assertFalse(repo.enabled())
    }

    @Test
    fun `persists and round trips through the export`() = test { repo ->
        repo.update { it.copy(operations = it.operations.copy(advancedStorageEnabled = true)) }
        assertTrue(repo.enabled())
        val exported = repo.exportJson()
        assertTrue(exported.getJSONObject("operations").getBoolean("advancedStorageEnabled"))
        repo.reset()
        assertFalse(repo.enabled())
        repo.importJson(exported)
        assertTrue(repo.enabled())
    }

    @Test
    fun `an export without the field keeps the default`() = test { repo ->
        repo.importJson(JSONObject("""{"operations":{"skipAppDeleteConfirmation":true}}"""))
        assertFalse(repo.enabled())
        repo.importJson(JSONObject("{}"))
        assertFalse(repo.enabled())
    }

    @Test
    fun `importing a malformed or foreign value never fails`() = test { repo ->
        repo.importJson(JSONObject("""{"operations":{"advancedStorageEnabled":"yes"}}"""))
        assertFalse(repo.enabled())
        repo.importJson(JSONObject("""{"operations":{"advancedStorageEnabled":null,"someFutureKey":1}}"""))
        assertFalse(repo.enabled())
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
}
