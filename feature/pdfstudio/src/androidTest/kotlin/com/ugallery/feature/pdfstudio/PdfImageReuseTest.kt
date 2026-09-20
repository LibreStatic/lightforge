package com.ugallery.feature.pdfstudio

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PdfImageReuseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun repeatedJpegAndPngPlacementsShareObjectsWithoutDroppingDrawsInEitherQuality(): Unit =
        runBlocking {
            PDFBoxResourceLoader.init(context)
            val engine = IsolatedPdfEngine(context)
            for (jpeg in listOf(true, false)) {
                val files =
                    List(2) { index ->
                        File.createTempFile(
                                "image-reuse-",
                                if (jpeg) ".jpg" else ".png",
                                context.cacheDir,
                            )
                            .also { file ->
                                val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(if (index == 0) Color.RED else Color.BLUE)
                                try {
                                    file.outputStream().use {
                                        bitmap.compress(
                                            if (jpeg) Bitmap.CompressFormat.JPEG
                                            else Bitmap.CompressFormat.PNG,
                                            95,
                                            it,
                                        )
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                    }
                val output = File.createTempFile("image-reuse-", ".pdf", context.cacheDir)
                try {
                    val assets =
                        files.map {
                            PdfAsset(
                                PdfProjectRepository.sha256(it),
                                if (jpeg) "image/jpeg" else "image/png",
                                120,
                                80,
                            )
                        }
                    val project =
                        PdfProject(
                            name = "Shared image objects",
                            assets = assets,
                            pages =
                                List(3) {
                                    PdfPage(
                                        images =
                                            List(24) { n ->
                                                PdfImage(
                                                    asset = assets[n % 2].hash,
                                                    x = 10.0 + n % 6 * 30,
                                                    y = 10.0 + n / 6 * 40,
                                                    width = 20.0,
                                                    height = 30.0,
                                                    rotation = n % 4 * 90,
                                                )
                                            }
                                    )
                                },
                        )
                    for (compact in listOf(false, true)) {
                        engine.export(project, files, output, compact)
                        assertEquals(3, engine.inspect(output).size)
                        PDDocument.load(output).use { document ->
                            val objects =
                                Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
                            document.pages.forEach { page ->
                                assertEquals(2, page.resources.xObjectNames.count())
                                page.resources.xObjectNames.forEach {
                                    objects.add(page.resources.getXObject(it).cosObject)
                                }
                                val parser = PDFStreamParser(page)
                                parser.parse()
                                assertEquals(
                                    24,
                                    parser.tokens.filterIsInstance<Operator>().count {
                                        it.name == "Do"
                                    },
                                )
                            }
                            assertEquals(2, objects.size)
                        }
                    }
                } finally {
                    files.forEach { it.delete() }
                    output.delete()
                }
            }
        }
}
