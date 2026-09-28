package com.librestatic.lightforge.core.ml

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.librestatic.lightforge.core.model.RecognizedLine
import com.librestatic.lightforge.core.model.RecognizedText
import com.librestatic.lightforge.core.model.RecognizedWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * User-initiated OCR for the photo viewer's text selection. Unlike [BundledMlKitOcrInference] it
 * keeps element-level boxes (needed to select word by word) and never persists anything.
 */
class InteractiveTextRecognizer : Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): RecognizedText = withContext(Dispatchers.Default) {
        val width = bitmap.width.coerceAtLeast(1).toFloat()
        val height = bitmap.height.coerceAtLeast(1).toFloat()
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        val lines = mutableListOf<RecognizedLine>()
        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val lineIndex = lines.size
                val words = line.elements.mapNotNull { element ->
                    val box = element.boundingBox ?: return@mapNotNull null
                    RecognizedWord(
                        text = element.text,
                        left = box.left / width,
                        top = box.top / height,
                        right = box.right / width,
                        bottom = box.bottom / height,
                        lineIndex = lineIndex,
                    )
                }
                if (words.isNotEmpty()) lines += RecognizedLine(words)
            }
        }
        RecognizedText(lines)
    }

    override fun close() = recognizer.close()
}
