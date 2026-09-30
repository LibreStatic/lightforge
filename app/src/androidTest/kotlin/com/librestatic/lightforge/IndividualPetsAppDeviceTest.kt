package com.librestatic.lightforge

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.feature.petrecognition.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real local models + production scan/inference/Room/UI. Duplicated views test workflow, not accuracy. */
class IndividualPetsAppDeviceTest {
    @Test fun realModelAnalysisReviewedIdentitySurvivesRecreationAndRemovalPreservesOriginals() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        check(InstrumentationRegistry.getArguments().getString("petFixture") == "lightforge-pet-models")
        val device = UiDevice.getInstance(instrumentation)
        val resolver = context.contentResolver
        val fixture = File(context.filesDir,"pet-fixtures")
        val name = "pet-app-${UUID.randomUUID()}"
        val evidence = File(context.filesDir,name).apply { mkdirs() }
        val pack = File(context.cacheDir,"$name.zip")
        val sources = mutableListOf<Uri>()
        val database = GalleryDatabaseFactory.open(context)
        val repository = GalleryPetIdentityRepository(database,resolver)
        val store = PetModelStore(context)
        val catSha = "2533197401eebe9410ea4d063f86c43fbd2666f3e8165a38aca155c0d09c21be"
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun capture(step:String) { device.takeScreenshot(File(evidence,"$step.png")); device.dumpWindowHierarchy(File(evidence,"$step.xml")) }
        fun await(description:String,timeout:Long=30_000,ready:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+timeout
            while(SystemClock.elapsedRealtime()<end) {
                if(runCatching(ready).getOrDefault(false)) return
                SystemClock.sleep(150)
            }
            capture("timeout-before-close")
            error("Timed out: $description")
        }
        fun find(selector:BySelector,direction:Direction=Direction.DOWN):UiObject2 {
            val end=SystemClock.elapsedRealtime()+25_000
            while(SystemClock.elapsedRealtime()<end) {
                try {
                    device.findObject(selector)?.let { if(!it.visibleBounds.isEmpty) return it }
                    val screen=device.findObject(By.res("pet-identity-screen"))
                    val scroll=screen?.takeIf { it.isScrollable } ?: device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.width().toLong()*it.visibleBounds.height() }
                    scroll?.scroll(direction,.65f)
                } catch(_:StaleObjectException) { }
                device.waitForIdle()
            }
            capture("missing-control-before-close"); error("Missing pet control $selector")
        }
        fun click(selector:BySelector,direction:Direction=Direction.DOWN) {
            var done=false
            repeat(3) { attempt -> if(!done) try { find(selector.enabled(true),direction).click(); done=true }
                catch(stale:StaleObjectException) { if(attempt==2) throw stale } }
            device.waitForIdle()
        }
        fun tag(value:String,direction:Direction=Direction.DOWN)=click(By.res(value),direction)
        fun openPets() {
            click(By.desc(context.getString(com.librestatic.lightforge.feature.photos.R.string.open_settings)))
            await("Settings route") { device.hasObject(By.text(context.getString(com.librestatic.lightforge.feature.settings.R.string.settings_title))) }
            click(By.text(context.getString(com.librestatic.lightforge.feature.settings.R.string.settings_page_ai)))
            await("Local analysis settings") { device.hasObject(By.text(context.getString(com.librestatic.lightforge.feature.settings.R.string.settings_page_ai))) }
            tag("settings-pet-identity")
            await("Pets route") { device.hasObject(By.res("pet-identity-screen")) }
        }
        fun ownedObservations():List<PetObservationCard> = runBlocking {
            val found=mutableListOf<PetObservationCard>();var after:String?=null
            do {
                val page=repository.observationsPage(afterId=after,limit=100)
                found+=page.items.filter { row -> sources.any { it.lastPathSegment?.toLong()==row.source.key.mediaStoreId } }
                check(page.nextId==null || page.nextId!=after); after=page.nextId
            } while(after!=null)
            found
        }
        fun selectObservation(id:String) {
            tag("pet-review",Direction.UP)
            // Page navigation is real UI, never a repository selection mutation.
            repeat(100) {
                val screen=find(By.res("pet-identity-screen"))
                try { screen.scroll(Direction.UP,1f) } catch(_:StaleObjectException) { }
                repeat(20) {
                    try {
                        val node=device.findObject(By.res("pet-select-$id"))
                        if(node!=null && !node.visibleBounds.isEmpty) {
                            click(By.res("pet-select-$id"))
                            await("Selected exact observation $id") { device.findObject(By.res("pet-select-$id"))?.isChecked==true }
                            return
                        }
                        val current=device.findObject(By.res("pet-identity-screen"))
                        if(current?.scroll(Direction.DOWN,.7f)==false) return@repeat
                    } catch(_:StaleObjectException) { }
                }
                val next=device.findObject(By.res("pet-next"))
                check(next!=null && next.isEnabled) { "Owned observation $id missing from review pages" }
                tag("pet-next")
            }
            error("Pet review exceeded bounded page count")
        }
        fun clearOwnedPetNamespace() {
            // Acceptance-only isolated app; preserve media, person namespaces and every other package.
            check(context.packageName=="com.librestatic.lightforge.pdfacceptance")
            runBlocking { val state=repository.currentSummary(); if(state.enabled || state.identityCount>0 || state.observationCount>0) check(repository.setAnalysisEnabled(state.revision,false,null)) }
            store.delete()
        }
        var primary:Throwable?=null
        try {
            clearOwnedPetNamespace()
            ZipOutputStream(pack.outputStream()).use { zip ->
                for((entry,file) in listOf("detector.tflite" to File(fixture,"efficientdet_lite0.tflite"),"recognition.tflite" to File(fixture,"pet-recognition-small-fp16.tflite"))) {
                    zip.putNextEntry(ZipEntry(entry));file.inputStream().use { it.copyTo(zip,128*1024) };zip.closeEntry()
                }
            }
            store.importPack(Uri.fromFile(pack)) // Same verified package lifecycle as production local import.
            assertTrue(store.installed())
            val bytes=File(fixture,"cat.jpg").readBytes();assertEquals(catSha,sha(bytes))
            repeat(2) { index ->
                val uri=requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME,"$name-$index.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/$name");put(MediaStore.Images.Media.IS_PENDING,1)
                }))
                sources+=uri
                requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
                assertEquals(1,resolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null))
            }
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                try {
                    val keys=sources.map { "external_primary:${it.lastPathSegment}" }
                    await("Production scanner sees owned photos",90_000) { runBlocking { database.semanticDao().mediaForEncodedKeys(keys).size==2 } }
                    openPets()
                    tag("pet-enable")
                    await("Opt-in committed") { runBlocking { repository.currentSummary().enabled } }
                    tag("pet-analyze")
                    await("Real production detections for both sources",300_000) { ownedObservations().size==2 && !device.hasObject(By.res("pet-progress")) }
                    val observations=ownedObservations()
                    assertTrue(observations.all { it.species==PetSpecies.Uncertain && it.identityId==null })
                    observations.forEach { assertEquals(512,runBlocking { repository.observationEmbedding(it.id,PetModelCatalog.Fingerprint) }!!.size) }
                    capture("real-analysis")
                    val first=observations.first();val second=observations.last()
                    selectObservation(first.id);tag("pet-species-cat")
                    await("First species explicitly reviewed") { ownedObservations().first { it.id==first.id }.species==PetSpecies.Cat }
                    selectObservation(first.id);tag("pet-create-group")
                    click(By.res("pet-name"))
                    await("Name has focus") { device.hasObject(By.clazz("android.widget.EditText").focused(true)) }
                    find(By.clazz("android.widget.EditText").focused(true)).text=name
                    await("Exact group name entered") { device.hasObject(By.clazz("android.widget.EditText").text(name)) }
                    tag("pet-name-confirm")
                    await("Named group committed") { ownedObservations().first { it.id==first.id }.identityId!=null }
                    val identityId=requireNotNull(ownedObservations().first { it.id==first.id }.identityId)
                    selectObservation(second.id);tag("pet-species-cat")
                    await("Second species explicitly reviewed") { ownedObservations().first { it.id==second.id }.species==PetSpecies.Cat }
                    selectObservation(second.id);tag("pet-suggest")
                    tag("pet-accept-$identityId")
                    await("Reviewed same group has two sources") { ownedObservations().all { it.identityId==identityId } }
                    click(By.text(context.getString(com.librestatic.lightforge.feature.petrecognition.R.string.pet_groups)),Direction.UP)
                    tag("pet-open-$identityId")
                    capture("group-before-recreate")
                    scenario.recreate()
                    await("Pets route restored") { device.hasObject(By.res("pet-identity-screen")) }
                    assertEquals(2,runBlocking { repository.observationsPage(identityId=identityId).items.size })
                    assertEquals(name,runBlocking { database.petIdentityDao().identity(identityId) }!!.name)
                    assertTrue(ownedObservations().all { it.identityId==identityId })
                    capture("group-after-recreate")
                    tag("pet-remove",Direction.UP)
                    assertTrue(runBlocking { repository.currentSummary().enabled })
                    tag("pet-remove-confirm")
                    await("Opt-out clears pet namespace and closes/deletes model") {
                        runBlocking { val state=repository.currentSummary();!state.enabled && state.identityCount==0L && state.observationCount==0L && state.undoToken==null } && !store.installed()
                    }
                    sources.forEach { assertEquals(catSha,sha(requireNotNull(resolver.openInputStream(it)).use { stream -> stream.readBytes() })) }
                    capture("removed-originals-intact")
                    File(evidence,"result.json").writeText(JSONObject().put("status","PASS").put("modelFingerprint",PetModelCatalog.Fingerprint)
                        .put("identityIdBeforeRemoval",identityId).put("name",name).put("observations",JSONArray(observations.map { it.id }))
                        .put("originalHashes",JSONArray(listOf(catSha,catSha))).put("recreationPersisted",true)
                        .put("groupingReviewed",true).put("automaticAssignment",false).put("duplicateViewsAreAccuracyBenchmark",false).toString(2))
                } catch(failure:Throwable) { capture("failure-before-scenario-close");throw failure }
            }
        } catch(failure:Throwable) { primary=failure;throw failure }
        finally {
            var cleanup:Throwable?=null
            fun clean(action:()->Unit) { try { action() } catch(failure:Throwable) { if(cleanup==null) cleanup=failure else cleanup!!.addSuppressed(failure) } }
            sources.forEach { uri -> clean { resolver.delete(uri,null,null) } }
            clean { clearOwnedPetNamespace() };clean { database.close() };clean { pack.delete() }
            cleanup?.let { if(primary!=null) primary!!.addSuppressed(it) else throw it }
        }
    }
}
