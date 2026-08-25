package com.ugallery.feature.semanticsearch

import android.content.Context
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipInputStream

internal class SemanticModelStorage(context: Context) {
    private val root = context.filesDir.resolve("semantic-models").apply { mkdirs() }

    fun directory(model: SemanticModelDescriptor) = root.resolve("${model.id}-${model.version}")
    fun partial(model: SemanticModelDescriptor) = root.resolve("${model.id}-${model.version}.partial")
    fun installed(model: SemanticModelDescriptor) = directory(model).resolve("complete.marker").isFile

    fun installedModel(model: SemanticModelDescriptor): InstalledSemanticModel? =
        directory(model).takeIf { installed(model) }?.let { InstalledSemanticModel(model, it) }

    fun delete(model: SemanticModelDescriptor) {
        partial(model).delete()
        directory(model).deleteRecursively()
    }

    fun download(model: SemanticModelDescriptor, onProgress: (Long) -> Unit) {
        val partial = partial(model)
        var downloaded = partial.takeIf(File::isFile)?.length() ?: 0L
        if (downloaded > model.packageBytes) {
            partial.delete()
            downloaded = 0L
        }
        require(StatFs(root.absolutePath).availableBytes >= model.packageBytes * 4 - downloaded) {
            "Not enough free storage for model installation"
        }
        val connection = open(model.packageUrl, downloaded)
        if (downloaded > 0L && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            partial.delete()
            downloaded = 0L
            connection.disconnect()
            return download(model, onProgress)
        }
        require(connection.responseCode in 200..299) { "Model download failed: HTTP ${connection.responseCode}" }
        partial.parentFile?.mkdirs()
        connection.inputStream.buffered().use { input ->
            FileOutputStream(partial, downloaded > 0L).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    require(downloaded <= model.packageBytes) { "Model package exceeds catalog size" }
                    onProgress(downloaded)
                }
            }
        }
        connection.disconnect()
        if (downloaded != model.packageBytes) errorAfterDeleting(partial, "Incomplete model package")
        if (partial.sha256() != model.packageSha256) errorAfterDeleting(partial, "Model package digest mismatch")
        if (!SemanticPackageSignature.verify(partial, model.signatureBase64)) errorAfterDeleting(partial, "Model package signature mismatch")
        install(model, partial)
    }

    private fun errorAfterDeleting(file: File, message: String): Nothing {
        file.delete()
        error(message)
    }

    private fun install(model: SemanticModelDescriptor, archive: File) {
        val destination = directory(model)
        val staging = File(destination.path + ".staging").apply { deleteRecursively(); mkdirs() }
        var expandedBytes = 0L
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in RequiredFiles) { "Unexpected model package entry" }
                val output = staging.resolve(entry.name)
                require(output.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                FileOutputStream(output).buffered().use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        expandedBytes += count
                        require(expandedBytes <= model.packageBytes * 3) { "Model package expands beyond limit" }
                        stream.write(buffer, 0, count)
                    }
                }
            }
        }
        require(RequiredFiles.all { staging.resolve(it).isFile }) { "Incomplete model package contents" }
        staging.resolve("complete.marker").writeText(model.packageSha256)
        destination.deleteRecursively()
        require(staging.renameTo(destination)) { "Unable to activate downloaded model" }
        archive.delete()
    }

    private fun open(rawUrl: String, offset: Long): HttpURLConnection {
        var url = URL(rawUrl)
        repeat(MaxRedirects + 1) {
            require(url.protocol == "https" && url.host in AllowedHosts) { "Unapproved model host" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            if (connection.responseCode !in RedirectCodes) return connection
            val location = connection.getHeaderField("Location") ?: error("Redirect without location")
            connection.disconnect()
            url = URL(url, location)
        }
        error("Too many model download redirects")
    }

    private fun File.sha256() = inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val RequiredFiles = setOf("image.tflite", "text.tflite", "vocab.json", "merges.txt", "NOTICE.txt")
        val AllowedHosts = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
        val RedirectCodes = setOf(301, 302, 303, 307, 308)
        const val MaxRedirects = 5
    }
}

internal object SemanticPackageSignature {
    fun verify(file: File, encodedSignature: String): Boolean = runCatching {
        val verifier = verifier()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                verifier.update(buffer, 0, count)
            }
        }
        verifier.verify(Base64.getDecoder().decode(encodedSignature))
    }.getOrDefault(false)

    fun verify(payload: ByteArray, encodedSignature: String): Boolean = runCatching {
        verifier().run { update(payload); verify(Base64.getDecoder().decode(encodedSignature)) }
    }.getOrDefault(false)

    private fun verifier(): Signature {
        val key = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(PublicKeyBase64)),
        )
        return Signature.getInstance("SHA256withECDSA").apply { initVerify(key) }
    }

    // Exported from the release key described in tools/semantic-models/README.md. Intentionally public.
    private const val PublicKeyBase64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEY94ttgccpbwvSJuFduG+h8NIKIrpuO/dAXfvocrpmNbjj9w5WirbWT2rbH87eGgBuEVLXj/A7h51yg4tpHKThA=="
}
