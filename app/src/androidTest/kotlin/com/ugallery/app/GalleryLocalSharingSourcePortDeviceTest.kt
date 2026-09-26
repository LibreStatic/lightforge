package com.ugallery.app

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.model.MediaKind
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryLocalSharingSourcePortDeviceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File
    @Before fun setup() { root=File(context.cacheDir,"share/peer-source-"+UUID.randomUUID()).apply { check(mkdirs()) } }
    @After fun cleanup() { check(root.deleteRecursively()) }
    private fun original(name:String="original.jpg",gps:Boolean=true):File {
        val file=File(root,name)
        Bitmap.createBitmap(24,18,Bitmap.Config.ARGB_8888).also { it.eraseColor(0xff789abc.toInt()) }.let { bitmap ->
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) } } finally { bitmap.recycle() }
        }
        if(gps) ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_GPS_LATITUDE,"43/1,44/1,168/100");setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N");setAttribute(ExifInterface.TAG_GPS_LONGITUDE,"7/1,25/1,30/1");setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF,"E");saveAttributes() }
        return file
    }
    private fun uri(file:File)=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file).toString()
    private fun sha(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun destination(name:String)=File(root,name).apply { check(mkdir()) }
    @Test fun originalsAreImmutableAndEqualBytesKeepSeparateSourceIdentities()=runBlocking {
        val one=original();val two=File(root,"second.jpg").also { one.copyTo(it) };val hash=sha(one)
        val port=GalleryLocalSharingSourcePort(context);val selected=listOf(uri(one),uri(two));port.retain("device-test",selected)
        val destination=destination("out");val sources=port.prepare(selected,false,destination) { }
        assertEquals(2,sources.size);assertNotEquals(sources[0].entry.sourceId,sources[1].entry.sourceId)
        assertEquals(sources[0].entry.sha256,sources[1].entry.sha256)
        sources.forEach { assertEquals(hash,sha(File(destination,it.fileName)));assertFalse(it.entry.sanitized) }
        val repeated=port.prepare(listOf(selected.first()),false,destination("repeat")) { }.single()
        assertEquals(sources[0].entry.sourceId,repeated.entry.sourceId);assertEquals(sources[0].entry.revision,repeated.entry.revision)
        assertEquals(hash,sha(one));assertEquals(hash,sha(two))
    }
    @Test fun locationRemovalPublishesOnlyVerifiedDerivativeAndLeavesGpsOriginalUntouched()=runBlocking {
        val source=original();val hash=sha(source);val port=GalleryLocalSharingSourcePort(context);val destination=destination("stripped")
        val prepared=port.prepare(listOf(uri(source)),true,destination) { }.single()
        assertTrue(prepared.entry.sanitized);assertNotEquals(hash,prepared.entry.sha256)
        port.requireLocationFree(File(destination,prepared.fileName),MediaKind.Image)
        assertEquals(hash,sha(source));assertNotNull(ExifInterface(source.path).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertTrue(destination.listFiles()!!.all { it.name.endsWith(".payload") })
    }
    @Test fun sanitizationFailureAndCancellationNeverReturnAnOriginalFallback()=runBlocking {
        val invalid=File(root,"broken.jpg").apply { writeText("not an image") };val port=GalleryLocalSharingSourcePort(context)
        assertTrue(runCatching { port.prepare(listOf(uri(invalid)),true,destination("bad")) { } }.isFailure)
        assertEquals("not an image",invalid.readText());assertFalse(File(root,"bad/0.payload").exists())
        val source=original();val hash=sha(source)
        assertTrue(runCatching { port.prepare(listOf(uri(source)),false,destination("cancel")) { throw java.io.InterruptedIOException("injected cancellation") } }.isFailure)
        assertEquals(hash,sha(source));assertTrue(File(root,"cancel").listFiles()!!.isEmpty())
    }
    @Test fun duplicateUriAndNonemptyAttemptAreRejectedWithoutChangingBytes()=runBlocking {
        val source=original();val hash=sha(source);val port=GalleryLocalSharingSourcePort(context);val output=destination("existing")
        val foreign=File(output,"foreign").apply { writeText("keep") }
        assertTrue(runCatching { port.prepare(listOf(uri(source)),false,output) { } }.isFailure)
        assertEquals("keep",foreign.readText());assertEquals(hash,sha(source))
        assertTrue(runCatching { port.retain("device-test",listOf(uri(source),uri(source))) }.isFailure)
    }
}
