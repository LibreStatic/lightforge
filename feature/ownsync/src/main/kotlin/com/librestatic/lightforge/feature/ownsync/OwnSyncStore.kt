package com.librestatic.lightforge.feature.ownsync

import android.content.Context
import android.util.AtomicFile
import com.librestatic.lightforge.core.remotestorage.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Every externally visible write follows an AtomicFile intent; corrupt records remain visible. */
class OwnSyncStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "own-sync").apply { mkdirs() }

    internal fun directory(id: String): File {
        uuid(id)
        return File(root, "runs/$id").apply { mkdirs() }
    }

    internal fun lease(job: String): File {
        uuid(job)
        return File(root, "jobs/$job.lease").apply { parentFile!!.mkdirs() }
    }

    internal fun stage(id: String) = File(directory(id), "source.partial")

    fun jobs(): List<OwnSyncJob> =
        synchronized(lock) {
            files("jobs")
                .mapNotNull { runCatching { job(it) }.getOrNull() }
                .sortedByDescending { it.createdAt }
        }

    fun job(id: String): OwnSyncJob? =
        synchronized(lock) {
            uuid(id)
            optional(File(root, "jobs/$id.json"))?.let(::decodeJob)?.also { value ->
                require(value.id == id)
                validateNamespace(value)
            }
        }

    fun run(id: String): OwnSyncRun? =
        synchronized(lock) {
            optional(File(directory(id), "run.json"))?.let(::decodeRun)?.also { value ->
                require(value.id == id)
                validateNamespace(value)
            }
        }

    fun runs(): List<OwnSyncRun> =
        synchronized(lock) {
            File(root, "runs")
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && validUuid(it.name) }
                .map { dir ->
                    runCatching { run(dir.name) ?: throw IOException() }
                        .getOrElse {
                            OwnSyncRun(
                                dir.name,
                                dir.name,
                                dir.lastModified(),
                                OwnSyncStatus.NeedsReview,
                                failure = "corrupt",
                            )
                        }
                }
                .sortedByDescending { it.createdAt }
        }

    fun create(job: OwnSyncJob, run: OwnSyncRun) =
        synchronized(lock) {
            require(job(job.id) == null)
            save(job)
            create(run)
        }

    fun create(run: OwnSyncRun) =
        synchronized(lock) {
            require(run(run.id) == null)
            require(runs().none { it.jobId == run.jobId && !it.terminal })
            require(job(run.jobId) != null)
            save(run)
        }

    internal fun updateJob(id: String, change: (OwnSyncJob) -> OwnSyncJob): OwnSyncJob =
        synchronized(lock) {
            val before = job(id) ?: throw IOException()
            val after = change(before)
            require(before.copy(outputs = after.outputs) == after)
            save(after)
            after
        }

    internal fun update(id: String, change: (OwnSyncRun) -> OwnSyncRun): OwnSyncRun =
        synchronized(lock) {
            val before = run(id) ?: throw IOException()
            val after = change(before)
            require(
                after.id == before.id &&
                    after.jobId == before.jobId &&
                    after.createdAt == before.createdAt &&
                    after.restoration == before.restoration
            )
            if (before.terminal) require(after == before)
            save(after)
            after
        }

    private fun validateNamespace(job: OwnSyncJob) {
        require(job.outputs.all { it.path.firstOrNull() == job.namespace })
    }

    private fun validateNamespace(run: OwnSyncRun) {
        val owner = job(run.jobId) ?: throw IOException("Missing sync owner")
        require(
            run.plan.all { entry ->
                entry.path.firstOrNull() == owner.namespace &&
                    (entry.output == null ||
                        owner.outputs.any { output ->
                            output.id == entry.output.id &&
                                output.path == entry.output.path &&
                                output.digest == entry.output.digest &&
                                output.sourceKey == entry.output.sourceKey
                        })
            }
        )
    }

    private fun save(job: OwnSyncJob) {
        validateNamespace(job)
        uuid(job.id)
        job.profile.validate()
        require(
            job.name.isNotBlank() && job.name.length <= 120 && job.tree.startsWith("content://")
        )
        require(job.outputs.size <= 20000)
        job.outputs.forEach {
            ownSyncPath(it.path)
            ownSyncPath(it.sourcePath)
            it.quarantine?.let(RemoteNames::requireChild)
        }
        write(
            File(root, "jobs/${job.id}.json"),
            JSONObject()
                .put("v", 1)
                .put("id", job.id)
                .put("name", job.name)
                .put("tree", job.tree)
                .put("profile", profile(job.profile))
                .put("policy", job.policy.name)
                .put("created", job.createdAt)
                .put("outputs", array(job.outputs.map(::output))),
        )
    }

    private fun save(run: OwnSyncRun) {
        validateNamespace(run)
        uuid(run.id)
        uuid(run.jobId)
        require(run.plan.size <= 30000 && run.residuals.size <= 30000)
        run.plan.forEach {
            uuid(it.id)
            ownSyncPath(it.path)
            it.staging?.let(RemoteNames::requireChild)
        }
        interruption[run.id] = run.pauseRequested || run.cancelRequested
        write(
            File(directory(run.id), "run.json"),
            JSONObject()
                .put("v", 1)
                .put("id", run.id)
                .put("job", run.jobId)
                .put("created", run.createdAt)
                .put("status", run.status.name)
                .put("snapshot", run.snapshot?.let(::snapshot))
                .put(
                    "plan",
                    array(
                        run.plan.map { e ->
                            JSONObject()
                                .put("id", e.id)
                                .put("action", e.action.name)
                                .put("source", e.source?.let(::source))
                                .put("output", e.output?.let(::output))
                                .put("path", JSONArray(e.path))
                                .put("done", e.done)
                                .put("stage", e.staging)
                        }
                    ),
                )
                .put("approved", run.approved)
                .put("mirror", run.mirrorApproved)
                .put("pause", run.pauseRequested)
                .put("cancel", run.cancelRequested)
                .put("failure", run.failure)
                .put("residuals", JSONArray(run.residuals))
                .put("restoration", run.restoration),
        )
    }

    private fun files(folder: String): List<String> =
        File(root, folder)
            .listFiles()
            .orEmpty()
            .mapNotNull { file ->
                file.name
                    .removeSuffix(".bak")
                    .takeIf { it.endsWith(".json") }
                    ?.removeSuffix(".json")
                    ?.takeIf(::validUuid)
            }
            .distinct()

    private fun optional(file: File): JSONObject? =
        if (!file.exists() && !File(file.path + ".bak").exists()) null
        else
            AtomicFile(file).openRead().use {
                JSONObject(OwnSyncIO.bounded(it).toString(Charsets.UTF_8)).also { j ->
                    require(j.getInt("v") == 1)
                }
            }

    private fun write(file: File, json: JSONObject) {
        file.parentFile!!.mkdirs()
        val bytes = json.toString().toByteArray()
        require(bytes.size <= 32 * 1024 * 1024)
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (t: Throwable) {
            atomic.failWrite(out)
            throw t
        }
        changes.value += 1
    }

    private fun decodeJob(j: JSONObject) =
        OwnSyncJob(
            j.getString("id").also(::uuid),
            j.getString("name"),
            j.getString("tree"),
            profile(j.getJSONObject("profile")),
            OwnSyncPolicy.valueOf(j.getString("policy")),
            j.getLong("created"),
            objects(j, "outputs", 20000).map(::output),
        )

    private fun decodeRun(j: JSONObject) =
        OwnSyncRun(
            j.getString("id").also(::uuid),
            j.getString("job").also(::uuid),
            j.getLong("created"),
            OwnSyncStatus.valueOf(j.getString("status")),
            j.optJSONObject("snapshot")?.let(::snapshot),
            objects(j, "plan", 30000).map { e ->
                OwnSyncPlanEntry(
                    e.getString("id").also(::uuid),
                    OwnSyncAction.valueOf(e.getString("action")),
                    e.optJSONObject("source")?.let(::source),
                    e.optJSONObject("output")?.let(::output),
                    strings(e, "path", 65).also(::ownSyncPath),
                    e.getBoolean("done"),
                    e.nullString("stage")?.also(RemoteNames::requireChild),
                )
            },
            j.getBoolean("approved"),
            j.getBoolean("mirror"),
            j.getBoolean("pause"),
            j.getBoolean("cancel"),
            j.nullString("failure"),
            strings(j, "residuals", 30000),
            j.getBoolean("restoration"),
        )

    companion object {
        internal val changes = MutableStateFlow(0L)
        private val interruption = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        internal fun interrupted(id: String): Boolean = interruption[id] == true

        private val lock = Any()

        private fun validUuid(s: String) =
            runCatching { UUID.fromString(s).toString() == s }.getOrDefault(false)

        private fun uuid(s: String) {
            require(validUuid(s))
        }

        private fun array(values: List<JSONObject>) =
            JSONArray().apply { values.forEach { put(it) } }

        private fun objects(j: JSONObject, key: String, max: Int): List<JSONObject> =
            j.getJSONArray(key).let { a ->
                require(a.length() <= max)
                List(a.length()) { a.getJSONObject(it) }
            }

        private fun strings(j: JSONObject, key: String, max: Int): List<String> =
            j.getJSONArray(key).let { a ->
                require(a.length() <= max)
                List(a.length()) { a.getString(it) }
            }

        private fun JSONObject.nullString(key: String): String? =
            if (isNull(key)) null else getString(key)

        private fun digest(d: RemoteDigest) = JSONObject().put("size", d.size).put("sha", d.sha256)

        private fun digest(j: JSONObject) = RemoteDigest(j.getLong("size"), j.getString("sha"))

        private fun source(s: OwnSyncSourceEntry) =
            JSONObject()
                .put("key", s.key)
                .put("uri", s.uri)
                .put("path", JSONArray(s.path))
                .put("mime", s.mime)
                .put("digest", digest(s.digest))
                .put("modified", s.modified)

        private fun source(j: JSONObject) =
            OwnSyncSourceEntry(
                j.getString("key"),
                j.getString("uri").also { require(it.startsWith("content://")) },
                strings(j, "path", 65).also(::ownSyncPath),
                j.getString("mime"),
                digest(j.getJSONObject("digest")),
                j.getLong("modified"),
            )

        private fun output(o: OwnSyncOutput) =
            JSONObject()
                .put("id", o.id)
                .put("key", o.sourceKey)
                .put("sourcePath", JSONArray(o.sourcePath))
                .put("path", JSONArray(o.path))
                .put("digest", digest(o.digest))
                .put("quarantine", o.quarantine)

        private fun output(j: JSONObject) =
            OwnSyncOutput(
                j.getString("id").also(::uuid),
                j.getString("key"),
                strings(j, "sourcePath", 65).also(::ownSyncPath),
                strings(j, "path", 65).also(::ownSyncPath),
                digest(j.getJSONObject("digest")),
                j.nullString("quarantine")?.also(RemoteNames::requireChild),
            )

        private fun snapshot(s: OwnSyncSnapshot) =
            JSONObject()
                .put("entries", array(s.entries.map(::source)))
                .put("issues", JSONArray(s.issues))
                .put(
                    "directories",
                    JSONArray().apply { s.directories.forEach { put(JSONArray(it)) } },
                )

        private fun snapshot(j: JSONObject) =
            OwnSyncSnapshot(
                objects(j, "entries", 10000).map(::source),
                strings(j, "issues", 10001),
                j.getJSONArray("directories").let { a ->
                    require(a.length() <= 20000)
                    List(a.length()) { i ->
                        a.getJSONArray(i).let { b ->
                            require(b.length() <= 64)
                            List(b.length()) { b.getString(it).also(RemoteNames::requireChild) }
                        }
                    }
                },
            )

        private fun profile(p: RemoteProfile) =
            JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("protocol", p.protocol.name)
                .put("host", p.host)
                .put("port", p.port)
                .put("user", p.username)
                .put("root", p.root)
                .put("share", p.share)
                .put("domain", p.domain)
                .put("auth", p.authKind.name)
                .put("pin", p.trustedHostKey)

        private fun profile(j: JSONObject) =
            RemoteProfile(
                    j.getString("id"),
                    j.getString("name"),
                    RemoteProtocol.valueOf(j.getString("protocol")),
                    j.getString("host"),
                    j.getInt("port"),
                    j.getString("user"),
                    j.getString("root"),
                    j.getString("share"),
                    j.getString("domain"),
                    RemoteAuthKind.valueOf(j.getString("auth")),
                    j.nullString("pin"),
                )
                .also { it.validate() }
    }
}
