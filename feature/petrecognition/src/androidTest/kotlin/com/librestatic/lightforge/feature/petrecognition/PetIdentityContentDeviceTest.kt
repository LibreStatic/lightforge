package com.librestatic.lightforge.feature.petrecognition

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Command wiring/theme tests use a bounded in-memory port; model tests separately execute real weights. */
class PetIdentityContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun lightCompactReviewNamingSplitExcludeUndoAndClear() = workflow(false, 1f, 360)
    @Test fun darkLargeTextReviewNamingSplitExcludeUndoAndClear() = workflow(true, 1.6f, 360)
    @Test fun dynamicExpandedReviewNamingSplitExcludeUndoAndClear() = workflow(false, 1f, 840)
    private fun workflow(dark: Boolean, font: Float, width: Int) {
        val photo = File.createTempFile("pet-ui-owned-", ".png", context.cacheDir)
        Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).also { bitmap -> photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
        val repo = PetUiRepository(Uri.fromFile(photo))
        val restore = StateRestorationTester(compose)
        var back = false
        var expectedSurface = 0
        try {
            restore.setContent { LightforgeTheme(darkTheme = dark, dynamicColor = true) {
                val colors = MaterialTheme.colorScheme
                expectedSurface = colors.surface.toArgb()
                listOf(colors.onSurface to colors.surface, colors.onSurface to colors.surfaceContainerLow,
                    colors.onErrorContainer to colors.errorContainer).forEach { (fg,bg) ->
                    assertTrue((maxOf(fg.luminance(),bg.luminance())+.05f)/(minOf(fg.luminance(),bg.luminance())+.05f) >= 4.5f)
                }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,font)) {
                    Box(Modifier.widthIn(max=width.dp)) { PetIdentityContent(repo,{ back=true }) }
                }
            } }
            click("pet-review")
            click("pet-select-a")
            click("pet-species-cat")
            assertTrue(repo.edits.last() is PetEdit.SetSpecies)
            click("pet-select-a")
            click("pet-create-group")
            compose.onNodeWithTag("pet-name").performTextInput("Noodle")
            compose.onNodeWithTag("pet-name-confirm").performClick()
            compose.waitForIdle()
            assertEquals("Noodle", (repo.edits.last() as PetEdit.CreateIdentity).name)
            restore.emulateSavedInstanceStateRestore()
            click("pet-select-b")
            click("pet-exclude")
            assertTrue(repo.edits.last() is PetEdit.Exclude)
            click("pet-excluded")
            click("pet-select-b")
            click("pet-restore")
            assertTrue(repo.edits.last() is PetEdit.Restore)
            click("pet-undo")
            assertEquals("undo-${repo.summary.value.revision-1}", repo.lastUndo)
            click("pet-select-b")
            click("pet-restore")
            click("pet-review")
            click("pet-select-b")
            click("pet-suggest")
            click("pet-accept-created")
            assertEquals("created", (repo.edits.last() as PetEdit.AcceptSuggestion).identityId)
            compose.onNodeWithTag("pet-identity-screen").performScrollToNode(hasText(context.getString(R.string.pet_groups)))
            compose.onNodeWithText(context.getString(R.string.pet_groups)).performClick()
            click("pet-open-created")
            click("pet-select-b")
            click("pet-split")
            compose.onNodeWithTag("pet-name").performTextInput("Other")
            compose.onNodeWithTag("pet-name-confirm").performClick()
            compose.waitForIdle()
            assertTrue(repo.edits.last() is PetEdit.Split)
            val evidence = File(context.filesDir,"pet-evidence").apply { mkdirs() }
            val screen = compose.onRoot().captureToImage().asAndroidBitmap()
            assertEquals("Rendered background must be the matching Material surface, including dark mode", expectedSurface, screen.getPixel(1,1))
            File(evidence,"ui-$dark-$font-$width.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG,100,it) }
            click("pet-remove")
            assertTrue(repo.summary.value.enabled)
            compose.onNodeWithTag("pet-remove-confirm").performClick()
            compose.waitUntil(10000) { !repo.summary.value.enabled }
            assertTrue(repo.observations.isEmpty()); assertTrue(repo.groups.isEmpty())
            assertTrue(photo.exists())
            click("pet-back"); assertTrue(back)
        } finally { photo.delete() }
    }
    @Test fun revisionConflictIsVisibleAndDoesNotReportSuccessfulNaming() {
        val repo = PetUiRepository(Uri.EMPTY).apply { rejectEdits = true }
        compose.setContent { LightforgeTheme { PetIdentityContent(repo,{}) } }
        click("pet-review"); click("pet-select-a"); click("pet-species-cat")
        compose.onNodeWithTag("pet-identity-screen").performScrollToNode(hasTestTag("pet-error"))
        compose.onNodeWithTag("pet-error").assertExists()
        assertEquals(PetSpecies.Uncertain,repo.observations.first().species)
    }
    private fun click(tag:String) {
        compose.onNodeWithTag("pet-identity-screen").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }
}

internal class PetUiRepository(uri:Uri) : PetIdentityRepository {
    override val summary = MutableStateFlow(PetSummary(1,true,PetModelCatalog.Fingerprint,0,2,2,0,null))
    val observations = mutableListOf(
        PetObservationCard("a",PetMediaSource(MediaKey("external",1),1,uri),PetBox(0f,0f,.45f,1f),PetSpecies.Uncertain,.8f,null,false,PetModelCatalog.Fingerprint),
        PetObservationCard("b",PetMediaSource(MediaKey("external",2),1,uri),PetBox(.5f,0f,1f,1f),PetSpecies.Cat,.9f,null,false,PetModelCatalog.Fingerprint))
    val groups = mutableListOf<PetIdentityCard>()
    val edits = mutableListOf<PetEdit>()
    var rejectEdits = false
    var lastUndo:String? = null
    private var undoRows:List<PetObservationCard> = emptyList()
    override suspend fun currentSummary() = summary.value
    override suspend fun setAnalysisEnabled(expectedRevision:Long,enabled:Boolean,modelFingerprint:String?):Boolean {
        if (expectedRevision != summary.value.revision) return false
        if (!enabled) { observations.clear(); groups.clear(); undoRows=emptyList() }
        summary.value=summary.value.copy(revision=summary.value.revision+1,enabled=enabled,modelFingerprint=modelFingerprint,undoToken=null)
        return true
    }
    override suspend fun identitiesPage(afterId:String?,limit:Int)=PetPage(groups.toList(),null)
    override suspend fun observationsPage(identityId:String?,filter:PetObservationFilter,afterId:String?,limit:Int)=PetPage(observations.filter {
        (identityId==null || it.identityId==identityId) && when(filter) {
            PetObservationFilter.All -> !it.excluded
            PetObservationFilter.Unassigned -> !it.excluded && it.identityId==null
            PetObservationFilter.Excluded -> it.excluded
        }
    },null)
    override suspend fun eligibleSources(afterKey:MediaKey?,limit:Int)=PetSourcePage(emptyList(),null)
    override suspend fun isCurrent(source:PetMediaSource)=true
    override suspend fun isAnalyzed(source:PetMediaSource,modelFingerprint:String)=false
    override suspend fun commitAnalysis(source:PetMediaSource,modelFingerprint:String,observations:List<PetAnalyzedObservation>)=false
    override suspend fun observationEmbedding(observationId:String,modelFingerprint:String)=FloatArray(512).apply { this[0]=1f }
    override suspend fun referenceEmbeddingsPage(species:PetSpecies,modelFingerprint:String,afterId:String?,limit:Int)=PetPage(listOf(PetReferenceEmbedding("a","created",FloatArray(512).apply { this[0]=1f })),null)
    override suspend fun edit(expectedRevision:Long,edit:PetEdit):PetEditResult {
        if (rejectEdits || expectedRevision != summary.value.revision) return PetEditResult(false,summary.value.revision)
        undoRows=observations.toList()
        edits+=edit
        fun update(ids:Set<String>,change:(PetObservationCard)->PetObservationCard) { for(i in observations.indices) if(observations[i].id in ids) observations[i]=change(observations[i]) }
        when(edit) {
            is PetEdit.SetSpecies -> update(edit.observationIds) { it.copy(species=edit.species) }
            is PetEdit.CreateIdentity -> { update(edit.observationIds) { it.copy(identityId="created") }; groups+=PetIdentityCard("created",edit.species,edit.name,1,observations.first()) }
            is PetEdit.Exclude -> update(edit.observationIds) { it.copy(excluded=true) }
            is PetEdit.Restore -> update(edit.observationIds) { it.copy(excluded=false) }
            is PetEdit.AcceptSuggestion -> update(edit.observationIds) { it.copy(identityId=edit.identityId) }
            is PetEdit.Split -> update(edit.observationIds) { it.copy(identityId="split") }
            is PetEdit.Rename -> Unit
            is PetEdit.Merge -> Unit
        }
        val revision=summary.value.revision+1
        summary.value=summary.value.copy(revision=revision,undoToken="undo-$revision")
        return PetEditResult(true,revision,"undo-$revision")
    }
    override suspend fun undo(expectedRevision:Long,token:String):PetEditResult {
        lastUndo=token
        observations.clear(); observations.addAll(undoRows)
        summary.value=summary.value.copy(revision=summary.value.revision+1,undoToken=null)
        return PetEditResult(true,summary.value.revision)
    }
}
