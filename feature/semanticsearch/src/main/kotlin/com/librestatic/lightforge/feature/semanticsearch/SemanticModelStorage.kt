package com.librestatic.lightforge.feature.semanticsearch

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
    private val appContext = context.applicationContext
    private val root = context.filesDir.resolve("semantic-models").apply { mkdirs() }

    fun directory(model: SemanticModelDescriptor) = root.resolve("${model.id}-${model.version}")
    fun partial(model: SemanticModelDescriptor) = root.resolve("${model.id}-${model.version}.partial")
    fun installed(model: SemanticModelDescriptor) = runCatching {
        directory(model).resolve("complete.marker").readText() == model.packageSha256 &&
            SemanticModelIntegrity.pins(model).all { (name, pin) -> directory(model).resolve(name).let { it.isFile && it.length() == pin.bytes } }
    }.getOrDefault(false)

    fun installedModel(model: SemanticModelDescriptor): InstalledSemanticModel? =
        directory(model).takeIf { installed(model) }?.let { InstalledSemanticModel(model, it) }

    fun supersedesDownloadFailure(model: SemanticModelDescriptor, workId: String): Boolean = runCatching {
        if (!installed(model)) return@runCatching false
        val receipt = directory(model).resolve("installation-receipt.txt").readLines()
        receipt.firstOrNull() == model.packageSha256 && workId in receipt.drop(1)
    }.getOrDefault(false)

    fun delete(model: SemanticModelDescriptor) {
        partial(model).delete()
        SemanticModelAccess.revoke(directory(model))
    }

    fun download(model: SemanticModelDescriptor, cancellation: SemanticDownloadCancellation = SemanticDownloadCancellation(), onProgress: (Long) -> Unit) {
        val partial = partial(model)
        var downloaded = partial.takeIf(File::isFile)?.length() ?: 0L
        if (downloaded > model.packageBytes) {
            partial.delete()
            downloaded = 0L
        }
        require(StatFs(root.absolutePath).availableBytes >= model.packageBytes * 4 - downloaded) {
            "Not enough free storage for model installation"
        }
        cancellation.checkCurrent()
        val connection = open(model.packageUrl, downloaded, cancellation)
        try {
        if (downloaded > 0L && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            partial.delete()
            downloaded = 0L
            connection.disconnect()
            return download(model, cancellation, onProgress)
        }
        require(connection.responseCode in 200..299) { "Model download failed: HTTP ${connection.responseCode}" }
        partial.parentFile?.mkdirs()
        connection.inputStream.buffered().use { input ->
            FileOutputStream(partial, downloaded > 0L).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    cancellation.checkCurrent()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    require(downloaded <= model.packageBytes) { "Model package exceeds catalog size" }
                    onProgress(downloaded)
                }
            }
        }
        cancellation.checkCurrent()
        } finally { cancellation.unregister(connection); connection.disconnect() }
        if (downloaded != model.packageBytes) errorAfterDeleting(partial, "Incomplete model package")
        if (partial.sha256() != model.packageSha256) errorAfterDeleting(partial, "Model package digest mismatch")
        if (!SemanticPackageSignature.verify(partial, model.signatureBase64)) errorAfterDeleting(partial, "Model package signature mismatch")
        cancellation.checkCurrent()
        installVerified(model, partial, cancellation)
        partial.delete()
    }

    private fun errorAfterDeleting(file: File, message: String): Nothing {
        file.delete()
        error(message)
    }

    internal fun installVerified(model: SemanticModelDescriptor, archive: File, cancellation: SemanticDownloadCancellation = SemanticDownloadCancellation()) {
        cancellation.checkCurrent()
        val destination = directory(model)
        val expectedEpoch = SemanticModelAccess.epoch(destination)
        // A receipt acknowledges only requests that existed before this successful installation.
        // A later failed download has a new UUID and remains visible; no unavailable WorkInfo clock API.
        val supersededDownloads = runCatching {
            androidx.work.WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWork(SemanticModelDownloadWorker.uniqueName(model.id)).get().map { it.id.toString() }
        }.getOrDefault(emptyList())
        require(archive.length() == model.packageBytes && archive.sha256() == model.packageSha256 &&
            SemanticPackageSignature.verify(archive, model.signatureBase64)) { "Unverified semantic package" }
        cancellation.checkCurrent()
        check(SemanticModelAccess.canReplace(destination)) { "Semantic model is currently in use" }
        val staging = File(destination.path + ".staging-" + java.util.UUID.randomUUID()).apply { check(mkdirs()) }
        var expandedBytes = 0L
        val names = mutableSetOf<String>()
        try {
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            while (true) {
                cancellation.checkCurrent()
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in RequiredFiles && names.add(entry.name)) { "Unexpected model package entry" }
                val output = staging.resolve(entry.name)
                require(output.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                FileOutputStream(output).buffered().use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        cancellation.checkCurrent()
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
        LiteRtSemanticEmbeddingInference(appContext, InstalledSemanticModel(model, staging)).use { inference ->
            inference.embedText("a photo")
        }
        staging.resolve("installation-receipt.txt").writeText(
            (listOf(model.packageSha256) + supersededDownloads).joinToString("\n"),
        )
        cancellation.checkCurrent()
        check(!Thread.currentThread().isInterrupted) { "Semantic installation interrupted" }
        SemanticModelAccess.publish(destination, staging, expectedEpoch)
        } finally { staging.deleteRecursively() }
    }

    private fun open(rawUrl: String, offset: Long, cancellation: SemanticDownloadCancellation): HttpURLConnection {
        var url = URL(rawUrl)
        repeat(MaxRedirects + 1) {
            require(url.protocol == "https" && url.host in AllowedHosts) { "Unapproved model host" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            cancellation.register(connection)
            try {
                if (connection.responseCode !in RedirectCodes) return connection
                val location = connection.getHeaderField("Location") ?: error("Redirect without location")
                url = URL(url, location)
            } catch (error: Throwable) {
                cancellation.unregister(connection); connection.disconnect(); throw error
            }
            cancellation.unregister(connection); connection.disconnect()
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
