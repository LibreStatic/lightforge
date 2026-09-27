package com.librestatic.lightforge.feature.remotebackup

import android.content.Context
import android.util.AtomicFile
import com.librestatic.lightforge.core.remotestorage.*
import com.librestatic.lightforge.feature.settings.LocalRestoreOrganizationOptions
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Private, bounded, write-ahead state. Invalid journals remain visible and are never resumed. */
internal class RemoteBackupStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "remote-backup").apply { mkdirs() }
    private val profileFile = File(root, "profiles.json")

    fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(root, id).apply { mkdirs() }
    }

    fun archive(id: String) = File(directory(id), "archive.lightforge.zip")

    fun partial(id: String) = File(directory(id), "archive.partial")

    fun lease(id: String) = File(directory(id), "lease")

    fun profiles(): List<RemoteProfile> =
        synchronized(lock) {
            if (!profileFile.exists() && !File(profileFile.path + ".bak").exists()) emptyList()
            else
                read(profileFile).getJSONArray("profiles").let { a ->
                    require(a.length() <= 100)
                    List(a.length()) { profile(a.getJSONObject(it)) }
                }
        }

    fun saveProfile(value: RemoteProfile) =
        synchronized(lock) {
            value.validate()
            val current = profiles().filterNot { it.id == value.id } + value
            require(current.size <= 100)
            write(
                profileFile,
                JSONObject()
                    .put("version", 1)
                    .put("profiles", JSONArray().apply { current.forEach { put(profile(it)) } }),
            )
        }

    fun create(task: RemoteBackupTask) =
        synchronized(lock) {
            val file = File(directory(task.id), "task.json")
            require(get(task.id) == null)
            save(task)
        }

    fun get(id: String): RemoteBackupTask? =
        synchronized(lock) {
            val file = File(directory(id), "task.json")
            if (!file.exists() && !File(file.path + ".bak").exists()) null else decode(read(file))
        }

    fun update(id: String, transform: (RemoteBackupTask) -> RemoteBackupTask): RemoteBackupTask =
        synchronized(lock) {
            val before = get(id) ?: throw IOException("Missing task")
            val after = transform(before)
            require(
                after.id == before.id &&
                    after.direction == before.direction &&
                    after.createdAt == before.createdAt
            )
            require(
                after.profile.copy(trustedHostKey = before.profile.trustedHostKey) == before.profile
            )
            require(
                after.sources == before.sources &&
                    after.organization == before.organization &&
                    after.remote == before.remote &&
                    after.name == before.name
            )
            if (before.terminal)
                require(after.status == before.status && after.localTaskId == before.localTaskId)
            save(after)
            after
        }

    fun list(): List<RemoteBackupTask> =
        synchronized(lock) {
            root
                .listFiles()
                .orEmpty()
                .filter {
                    it.isDirectory &&
                        runCatching { UUID.fromString(it.name).toString() == it.name }
                            .getOrDefault(false)
                }
                .map { dir ->
                    runCatching { get(dir.name) ?: throw IOException("Missing task") }
                        .getOrElse {
                            RemoteBackupTask(
                                dir.name,
                                corruptProfile,
                                RemoteBackupDirection.Upload,
                                dir.lastModified(),
                                status = RemoteBackupStatus.NeedsReview,
                                failure = "corrupt",
                            )
                        }
                }
                .sortedByDescending { it.createdAt }
        }

    fun probes(): List<RemoteProfileProbe> =
        synchronized(lock) {
            val file = File(root, "probes.json")
            if (!file.exists() && !File(file.path + ".bak").exists()) emptyList()
            else
                read(file).getJSONArray("probes").let { a ->
                    require(a.length() <= 100)
                    List(a.length()) { i ->
                        a.getJSONObject(i).let { j ->
                            val names = j.getJSONArray("residuals")
                            require(names.length() <= 1000)
                            RemoteProfileProbe(
                                j.getString("profileId"),
                                j.nullString("identity"),
                                j.nullString("failure"),
                                j.nullString("observedHostKey"),
                                List(names.length()) { names.getString(it) },
                                j.nullBoolean("encrypted"),
                                j.nullBoolean("signed"),
                                j.nullBoolean("atomicPublish"),
                            )
                        }
                    }
                }
        }

    fun saveProbe(value: RemoteProfileProbe) =
        synchronized(lock) {
            val previous = probes().singleOrNull { it.profileId == value.profileId }
            val updated =
                value.copy(
                    residuals =
                        (previous?.residuals.orEmpty() + value.residuals).distinct().take(1000)
                )
            val all = probes().filterNot { it.profileId == value.profileId } + updated
            require(all.size <= 100)
            write(
                File(root, "probes.json"),
                JSONObject()
                    .put("version", 1)
                    .put(
                        "probes",
                        JSONArray().apply {
                            all.forEach {
                                put(
                                    JSONObject()
                                        .put("profileId", it.profileId)
                                        .put("identity", it.identity)
                                        .put("failure", it.failure)
                                        .put("observedHostKey", it.observedHostKey)
                                        .put("residuals", JSONArray(it.residuals))
                                        .put("encrypted", it.encrypted)
                                        .put("signed", it.signed)
                                        .put("atomicPublish", it.atomicPublish)
                                )
                            }
                        },
                    ),
            )
        }

    private fun save(task: RemoteBackupTask) {
        require(UUID.fromString(task.id).toString() == task.id)
        task.profile.validate()
        RemoteNames.requireChild(task.name)
        task.staging?.let(RemoteNames::requireChild)
        task.residuals.forEach(RemoteNames::requireChild)
        require(task.sources.all { it.startsWith("content://") })
        require(task.archiveSha == null || Regex("[a-f0-9]{64}").matches(task.archiveSha))
        require(
            task.totalBytes in 0..RemoteBackupController.MaxArchiveBytes &&
                task.bytesDone in 0..RemoteBackupController.MaxArchiveBytes &&
                task.files in 0..10000
        )
        require(
            task.sources.size <= 10000 &&
                task.sources.sumOf { it.length.toLong() } <= 4L * 1024 * 1024
        )
        require(task.residuals.size <= 1000)
        write(File(directory(task.id), "task.json"), encode(task))
    }

    private fun read(file: File): JSONObject {
        val atomic = AtomicFile(file)
        return atomic.openRead().use {
            val bytes = RemoteBackupIO.readBounded(it, 16 * 1024 * 1024)
            require(bytes.size <= 16 * 1024 * 1024)
            JSONObject(bytes.toString(Charsets.UTF_8)).also { json ->
                require(json.getInt("version") == 1)
            }
        }
    }

    private fun write(file: File, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        require(bytes.size <= 16 * 1024 * 1024)
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (error: Throwable) {
            atomic.failWrite(out)
            throw error
        }
        changes.value += 1
    }

    companion object {
        val changes = MutableStateFlow(0L)
        private val lock = Any()
        private val corruptProfile =
            RemoteProfile(
                id = "00000000-0000-0000-0000-000000000000",
                name = "",
                protocol = RemoteProtocol.SFTP,
                host = "",
                username = "",
                root = "",
            )

        private fun profile(p: RemoteProfile) =
            JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("protocol", p.protocol.name)
                .put("host", p.host)
                .put("port", p.port)
                .put("username", p.username)
                .put("root", p.root)
                .put("share", p.share)
                .put("domain", p.domain)
                .put("authKind", p.authKind.name)
                .put("trustedHostKey", p.trustedHostKey)

        private fun profile(j: JSONObject) =
            RemoteProfile(
                    j.getString("id"),
                    j.getString("name"),
                    RemoteProtocol.valueOf(j.getString("protocol")),
                    j.getString("host"),
                    j.getInt("port"),
                    j.getString("username"),
                    j.getString("root"),
                    j.getString("share"),
                    j.getString("domain"),
                    RemoteAuthKind.valueOf(j.getString("authKind")),
                    j.nullString("trustedHostKey"),
                )
                .also { it.validate() }

        private fun encode(t: RemoteBackupTask): JSONObject =
            JSONObject()
                .put("version", 1)
                .put("id", t.id)
                .put("profile", profile(t.profile))
                .put("direction", t.direction.name)
                .put("createdAt", t.createdAt)
                .put("status", t.status.name)
                .put("phase", t.phase.name)
                .put("sources", JSONArray(t.sources))
                .put("organization", t.organization)
                .put(
                    "remote",
                    t.remote?.let {
                        JSONObject()
                            .put("name", it.name)
                            .put("size", it.size)
                            .put("modified", it.modifiedMillis)
                            .put("regular", it.regularFile)
                    },
                )
                .put("name", t.name)
                .put("staging", t.staging)
                .put("residuals", JSONArray(t.residuals))
                .put("bytesDone", t.bytesDone)
                .put("totalBytes", t.totalBytes)
                .put("files", t.files)
                .put("archiveSha", t.archiveSha)
                .put("pauseRequested", t.pauseRequested)
                .put("cancelRequested", t.cancelRequested)
                .put("failure", t.failure)
                .put("observedHostKey", t.observedHostKey)
                .put("localTaskId", t.localTaskId)
                .put("restoreGallery", t.restoreGallery)
                .put("restoreDestination", t.restoreDestination)
                .put("importGlobalRules", t.restoreOptions.importGlobalRules)
                .put("allowPartial", t.restoreOptions.allowPartial)

        private fun decode(j: JSONObject): RemoteBackupTask {
            fun strings(key: String, limit: Int): List<String> =
                j.getJSONArray(key).let { a ->
                    require(a.length() <= limit)
                    List(a.length()) { a.getString(it) }
                }
            return RemoteBackupTask(
                    id = j.getString("id").also { require(UUID.fromString(it).toString() == it) },
                    profile = profile(j.getJSONObject("profile")),
                    direction = RemoteBackupDirection.valueOf(j.getString("direction")),
                    createdAt = j.getLong("createdAt"),
                    status = RemoteBackupStatus.valueOf(j.getString("status")),
                    phase = RemoteBackupPhase.valueOf(j.getString("phase")),
                    sources = strings("sources", 10000),
                    organization = j.getBoolean("organization"),
                    remote =
                        j.optJSONObject("remote")?.let {
                            RemoteEntry(
                                RemoteNames.requireChild(it.getString("name")),
                                it.getLong("size"),
                                it.getLong("modified"),
                                it.getBoolean("regular"),
                            )
                        },
                    name = j.getString("name"),
                    staging = j.nullString("staging"),
                    residuals = strings("residuals", 1000),
                    bytesDone = j.getLong("bytesDone"),
                    totalBytes = j.getLong("totalBytes"),
                    files = j.getInt("files"),
                    archiveSha = j.nullString("archiveSha"),
                    pauseRequested = j.getBoolean("pauseRequested"),
                    cancelRequested = j.getBoolean("cancelRequested"),
                    failure = j.nullString("failure"),
                    observedHostKey = j.nullString("observedHostKey"),
                    localTaskId = j.nullString("localTaskId"),
                    restoreGallery = j.getBoolean("restoreGallery"),
                    restoreDestination = j.nullString("restoreDestination"),
                    restoreOptions =
                        LocalRestoreOrganizationOptions(
                            j.getBoolean("importGlobalRules"),
                            j.getBoolean("allowPartial"),
                        ),
                )
                .also {
                    require(
                        it.bytesDone >= 0 &&
                            it.totalBytes >= 0 &&
                            it.files in 0..10000 &&
                            it.sources.sumOf { s -> s.length.toLong() } <= 4L * 1024 * 1024
                    )
                }
        }

        private fun JSONObject.nullBoolean(key: String): Boolean? =
            if (isNull(key)) null else getBoolean(key)

        private fun JSONObject.nullString(key: String): String? =
            if (isNull(key)) null else getString(key)
    }
}
