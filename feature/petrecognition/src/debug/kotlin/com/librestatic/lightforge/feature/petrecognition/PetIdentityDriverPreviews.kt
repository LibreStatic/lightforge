package com.librestatic.lightforge.feature.petrecognition

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.work.Configuration
import androidx.work.WorkManager
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.flow.MutableStateFlow

// Debug-only, zero-argument entry point for tools/compose-driver. It runs the real
// PetIdentityContent on an in-memory repository: unassigned observations (three cats that look
// alike, so a "Possible pets" suggestion shows), one group, analysis counts and an undo token, so the
// tabs, the top-bar actions and the selection bar can all be exercised. [PetIdentityEmptyPreview]
// has no groups or suggestions, to show the explanatory empty state.
// Photos do not decode (empty URI), so cards render without thumbnails.

/** Pet groups with analysis enabled. Open "Review unassigned" and tick a card to see the selection bar. */
@Composable
fun PetIdentityPreview() {
    val context = LocalContext.current
    val repository = remember {
        // The screen observes WorkManager for model downloads; Robolectric does not initialize it.
        if (!WorkManager.isInitialized()) WorkManager.initialize(context.applicationContext, Configuration.Builder().build())
        PreviewPetRepository()
    }
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            PetIdentityContent(repository = repository, onBack = {})
        }
    }
}

/** Groups tab with nothing grouped and nothing to suggest: the empty state explains what to do next. */
@Composable
fun PetIdentityEmptyPreview() {
    val context = LocalContext.current
    val repository = remember {
        if (!WorkManager.isInitialized()) WorkManager.initialize(context.applicationContext, Configuration.Builder().build())
        PreviewPetRepository(grouped = false)
    }
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            PetIdentityContent(repository = repository, onBack = {})
        }
    }
}

private class PreviewPetRepository(private val grouped: Boolean = true) : PetIdentityRepository {
    private val source = { id: Long -> PetMediaSource(MediaKey("external", id), 1, Uri.EMPTY) }
    private val observations = listOf(
        PetObservationCard("a", source(1), PetBox(0f, 0f, .45f, 1f), PetSpecies.Uncertain, .8f, null, false, PetModelCatalog.Fingerprint),
        PetObservationCard("b", source(2), PetBox(.5f, 0f, 1f, 1f), PetSpecies.Cat, .9f, null, false, PetModelCatalog.Fingerprint),
        PetObservationCard("c", source(3), PetBox(0f, 0f, 1f, 1f), PetSpecies.Dog, .85f, "rex", false, PetModelCatalog.Fingerprint),
        PetObservationCard("d", source(4), PetBox(0f, 0f, 1f, 1f), PetSpecies.Cat, .88f, null, false, PetModelCatalog.Fingerprint),
        PetObservationCard("e", source(5), PetBox(0f, 0f, 1f, 1f), PetSpecies.Cat, .92f, null, false, PetModelCatalog.Fingerprint),
    ).filter { grouped || it.identityId == null }
    private val groups = if (grouped) listOf(PetIdentityCard("rex", PetSpecies.Dog, "Rex", 1, observations.first { it.id == "c" })) else emptyList()
    override val summary = MutableStateFlow(PetSummary(1, true, PetModelCatalog.Fingerprint, groups.size.toLong(), observations.size.toLong(), observations.count { it.identityId == null }.toLong(), 0, "undo-1"))
    override val progress = MutableStateFlow(PetIdentityProgress(processed = 1_200, total = 8_400))
    override suspend fun currentSummary() = summary.value
    override suspend fun setAnalysisEnabled(expectedRevision: Long, enabled: Boolean, modelFingerprint: String?) = true
    override suspend fun identitiesPage(afterId: String?, limit: Int) = PetPage(groups, null)
    override suspend fun observationsPage(identityId: String?, filter: PetObservationFilter, afterId: String?, limit: Int) =
        PetPage(
            observations.filter {
                (identityId == null || it.identityId == identityId) && when (filter) {
                    PetObservationFilter.All -> !it.excluded
                    PetObservationFilter.Unassigned -> !it.excluded && it.identityId == null
                    PetObservationFilter.Excluded -> it.excluded
                }
            },
            null,
        )
    override suspend fun eligibleSources(afterKey: MediaKey?, limit: Int) = PetSourcePage(emptyList(), null)
    override suspend fun isCurrent(source: PetMediaSource) = false
    override suspend fun isAnalyzed(source: PetMediaSource, modelFingerprint: String) = false
    override suspend fun commitAnalysis(source: PetMediaSource, modelFingerprint: String, observations: List<PetAnalyzedObservation>) = false
    override suspend fun observationEmbedding(observationId: String, modelFingerprint: String) = FloatArray(512).apply { this[0] = 1f }
    override suspend fun referenceEmbeddingsPage(species: PetSpecies, modelFingerprint: String, afterId: String?, limit: Int) =
        PetPage(emptyList<PetReferenceEmbedding>(), null)
    // The cats share one embedding direction, so grouping suggests them as one possible pet.
    override suspend fun unassignedEmbeddingsPage(species: PetSpecies, modelFingerprint: String, afterId: String?, limit: Int) =
        PetPage(
            if (grouped && species == PetSpecies.Cat) observations.filter { it.species == PetSpecies.Cat && it.identityId == null }.map { PetUnassignedEmbedding(it, FloatArray(512).apply { this[0] = 1f }) }
            else emptyList(),
            null,
        )
    override suspend fun edit(expectedRevision: Long, edit: PetEdit) = PetEditResult(true, expectedRevision + 1, "undo-${expectedRevision + 1}")
    override suspend fun undo(expectedRevision: Long, token: String) = PetEditResult(true, expectedRevision + 1)
}
