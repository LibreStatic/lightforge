package com.ugallery.feature.settings

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class LocalBackupTaskKind {
    Backup,
    RestoreFolder,
    RestoreGallery,
}

enum class LocalBackupTaskStatus {
    Queued,
    Running,
    Paused,
    WaitingPermission,
    Failed,
    NeedsReview,
    Cancelling,
    Cancelled,
    Completed,
}

enum class LocalBackupTaskPhase {
    Preparing,
    ArchiveReady,
    Publishing,
    Restoring,
    Committing,
    Cleanup,
    Done,
}

data class LocalBackupTaskOutput(
    val uri: String,
    val index: Int,
    val bytes: Long = 0,
    val sha256: String = "",
    val verified: Boolean = false,
)

data class LocalBackupTask(
    val id: String,
    val kind: LocalBackupTaskKind,
    val createdAt: Long,
    val name: String,
    val sources: List<String> = emptyList(),
    val snapshotSource: String? = null,
    val snapshotManifestSha: String? = null,
    val destination: String? = null,
    val organization: Boolean = false,
    val options: LocalRestoreOrganizationOptions = LocalRestoreOrganizationOptions(),
    val status: LocalBackupTaskStatus = LocalBackupTaskStatus.Queued,
    val phase: LocalBackupTaskPhase = LocalBackupTaskPhase.Preparing,
    val pauseRequested: Boolean = false,
    val cancelRequested: Boolean = false,
    val folder: String? = null,
    val outputs: List<LocalBackupTaskOutput> = emptyList(),
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val importedObjects: Int = 0,
    val failure: String? = null,
    val updatedAt: Long = createdAt,
) {
    val terminal
        get() =
            status == LocalBackupTaskStatus.Completed || status == LocalBackupTaskStatus.Cancelled
}

/** Private non-cache task snapshots. Every mutation is fsynced through AtomicFile. */
class LocalBackupTaskStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "local-backup-tasks")

    fun directory(id: String): File {
        require(validId(id))
        return File(root, id)
    }

    fun archive(id: String) = File(directory(id), "archive.ugallery.zip")

    fun partialArchive(id: String) = File(directory(id), "archive.partial")

    fun lockFile(id: String) = File(directory(id), "executor.lock")

    fun create(task: LocalBackupTask) =
        synchronized(lock) {
            require(read(task.id) == null)
            save(task)
        }

    fun read(id: String): LocalBackupTask? =
        synchronized(lock) {
            val file = File(directory(id), "task.json")
            if (!file.exists() && !File(file.path + ".bak").exists()) return@synchronized null
            val bytes =
                AtomicFile(file).openRead().use { input ->
                    val result = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (result.size() + count > MaxBytes)
                            throw IOException("Task snapshot too large")
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray()
                }
            decode(JSONObject(bytes.toString(Charsets.UTF_8))).also { require(it.id == id) }
        }

    fun update(id: String, change: (LocalBackupTask) -> LocalBackupTask): LocalBackupTask =
        synchronized(lock) {
            val before = requireNotNull(read(id))
            val after = change(before).copy(updatedAt = System.currentTimeMillis())
            require(
                after.id == before.id &&
                    after.kind == before.kind &&
                    after.createdAt == before.createdAt
            )
            save(after)
            after
        }

    fun list(): List<LocalBackupTask> =
        synchronized(lock) {
            root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && validId(it.name) }
                .mapNotNull { directory ->
                    try {
                        read(directory.name)
                    } catch (_: Exception) {
                        // Never hide a damaged durable task or hand its Gallery journal to cleanup.
                        LocalBackupTask(
                            directory.name,
                            LocalBackupTaskKind.RestoreGallery,
                            directory.lastModified(),
                            "",
                            status = LocalBackupTaskStatus.NeedsReview,
                            failure = "corrupt",
                        )
                    }
                }
                .sortedByDescending { it.createdAt }
        }

    fun retainedGalleryOperations(): Set<String> =
        list()
            .filter { it.kind == LocalBackupTaskKind.RestoreGallery && !it.terminal }
            .mapTo(mutableSetOf()) { it.id }

    fun forget(id: String) =
        synchronized(lock) {
            check(requireNotNull(read(id)).terminal)
            if (!directory(id).deleteRecursively()) throw IOException("Private task cleanup failed")
            revisions.value += 1
        }

    private fun save(task: LocalBackupTask) {
        require(validId(task.id) && task.sources.size <= BackupManifest.MAX_ENTRIES)
        require(task.sources.sumOf { it.length.toLong() } <= 4L * 1024 * 1024)
        require(task.outputs.size <= BackupManifest.MAX_ENTRIES)
        directory(task.id).mkdirs()
        val data = encode(task).toString().toByteArray()
        require(data.size <= MaxBytes)
        val file = AtomicFile(File(directory(task.id), "task.json"))
        val output = file.startWrite()
        try {
            output.write(data)
            file.finishWrite(output)
            revisions.value += 1
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        private val lock = Any()
        internal val revisions = kotlinx.coroutines.flow.MutableStateFlow(0L)
        private const val MaxBytes = 16 * 1024 * 1024

        fun validId(value: String) =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

        private fun encode(task: LocalBackupTask) =
            JSONObject().apply {
                put("version", 1)
                put("id", task.id)
                put("kind", task.kind.name)
                put("created", task.createdAt)
                put("updated", task.updatedAt)
                put("name", task.name)
                put("sources", JSONArray(task.sources))
                put("snapshotSource", task.snapshotSource ?: JSONObject.NULL)
                put("snapshotManifestSha", task.snapshotManifestSha ?: JSONObject.NULL)
                put("destination", task.destination ?: JSONObject.NULL)
                put("organization", task.organization)
                put("global", task.options.importGlobalRules)
                put("partial", task.options.allowPartial)
                put("status", task.status.name)
                put("phase", task.phase.name)
                put("pause", task.pauseRequested)
                put("cancel", task.cancelRequested)
                put("folder", task.folder ?: JSONObject.NULL)
                put("done", task.filesDone)
                put("total", task.filesTotal)
                put("bytes", task.bytesDone)
                put("imported", task.importedObjects)
                put("failure", task.failure ?: JSONObject.NULL)
                put(
                    "outputs",
                    JSONArray(
                        task.outputs.map { output ->
                            JSONObject().apply {
                                put("uri", output.uri)
                                put("index", output.index)
                                put("bytes", output.bytes)
                                put("sha", output.sha256)
                                put("verified", output.verified)
                            }
                        }
                    ),
                )
            }

        private fun decode(json: JSONObject): LocalBackupTask {
            require(json.getInt("version") == 1)
            val sources = json.getJSONArray("sources")
            val outputs = json.getJSONArray("outputs")
            require(
                sources.length() <= BackupManifest.MAX_ENTRIES &&
                    outputs.length() <= BackupManifest.MAX_ENTRIES
            )
            return LocalBackupTask(
                    id = json.getString("id"),
                    kind = LocalBackupTaskKind.valueOf(json.getString("kind")),
                    createdAt = json.getLong("created"),
                    name = json.getString("name"),
                    sources =
                        List(sources.length()) {
                            sources.getString(it).also { uri ->
                                require(uri.startsWith("content://") && uri.length <= 8192)
                            }
                        },
                    snapshotSource =
                        if (json.isNull("snapshotSource")) null
                        else json.getString("snapshotSource"),
                    snapshotManifestSha =
                        if (json.isNull("snapshotManifestSha")) null
                        else json.getString("snapshotManifestSha"),
                    destination =
                        if (json.isNull("destination")) null else json.getString("destination"),
                    organization = json.getBoolean("organization"),
                    options =
                        LocalRestoreOrganizationOptions(
                            json.getBoolean("global"),
                            json.getBoolean("partial"),
                        ),
                    status = LocalBackupTaskStatus.valueOf(json.getString("status")),
                    phase = LocalBackupTaskPhase.valueOf(json.getString("phase")),
                    pauseRequested = json.getBoolean("pause"),
                    cancelRequested = json.getBoolean("cancel"),
                    folder = if (json.isNull("folder")) null else json.getString("folder"),
                    outputs =
                        List(outputs.length()) { index ->
                            outputs.getJSONObject(index).let { output ->
                                LocalBackupTaskOutput(
                                    output.getString("uri"),
                                    output.getInt("index"),
                                    output.getLong("bytes"),
                                    output.getString("sha"),
                                    output.getBoolean("verified"),
                                )
                            }
                        },
                    filesDone = json.getInt("done"),
                    filesTotal = json.getInt("total"),
                    bytesDone = json.getLong("bytes"),
                    importedObjects = json.getInt("imported"),
                    failure = if (json.isNull("failure")) null else json.getString("failure"),
                    updatedAt = json.getLong("updated"),
                )
                .also { require(validId(it.id)) }
        }
    }
}
