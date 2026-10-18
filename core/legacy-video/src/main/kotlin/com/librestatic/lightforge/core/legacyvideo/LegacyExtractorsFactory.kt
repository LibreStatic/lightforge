package com.librestatic.lightforge.core.legacyvideo

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flv.FlvExtractor
import com.librestatic.lightforge.core.legacyvideo.flv.LegacyFlvExtractor

/**
 * [DefaultExtractorsFactory] that swaps Media3's [FlvExtractor] for [LegacyFlvExtractor], which also
 * demuxes Sorenson Spark / VP6 video and tolerates unsupported audio codecs. Order is preserved.
 */
@UnstableApi
class LegacyExtractorsFactory(
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = swap(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = swap(delegate.createExtractors(uri, responseHeaders))

    private fun swap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { i ->
            val e = extractors[i]
            if (e is FlvExtractor) LegacyFlvExtractor() else e
        }

    companion object {
        @JvmStatic
        fun create(): LegacyExtractorsFactory = LegacyExtractorsFactory()
    }
}
