package com.ugallery.feature.pdfstudio

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Verify a queued archive without importing it or mutating the project/source database. */
internal object PdfPortableArchive {
    const val MANIFEST_LIMIT = 2_000_000
    const val EXPANDED_LIMIT = 500L * 1024 * 1024

    suspend fun verify(file: File, project: PdfProject) {
        val expected =
            project.copy(assets = project.assets.filter { it.hash in project.usedAssets() })
        val names = expected.assets.map { "assets/${it.hash}" }.toSet() + "manifest.json"
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().take(names.size + 1).toList()
            require(entries.size == names.size && entries.map { it.name }.toSet() == names)
            require(entries.none { it.isDirectory })
            var expanded = 0L
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                zip.getInputStream(entry).use { input ->
                    if (entry.name == "manifest.json") {
                        val buffer = ByteArray(64 * 1024)
                        val data = java.io.ByteArrayOutputStream()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(data.size() + n <= MANIFEST_LIMIT)
                            data.write(buffer, 0, n)
                        }
                        val bytes = data.toByteArray()
                        expanded += bytes.size
                        require(PdfCodec.decode(bytes.toString(Charsets.UTF_8)) == expected)
                    } else {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            expanded += n
                            require(expanded <= EXPANDED_LIMIT)
                            digest.update(buffer, 0, n)
                        }
                        require(
                            digest.digest().joinToString("") { "%02x".format(it) } ==
                                entry.name.removePrefix("assets/")
                        )
                    }
                    require(expanded <= EXPANDED_LIMIT)
                }
            }
        }
    }
}
