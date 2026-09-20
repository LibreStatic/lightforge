package com.ugallery.feature.localsharing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal object PeerCodec {
    fun entry(e: LocalSharingEntry) =
        JSONObject()
            .put("id", e.sourceId)
            .put("revision", e.revision)
            .put("name", e.name)
            .put("mime", e.mime)
            .put("bytes", e.bytes)
            .put("sha", e.sha256)
            .put("modified", e.modifiedMillis)
            .put("sanitized", e.sanitized)

    fun entry(j: JSONObject) =
        LocalSharingEntry(
                j.getString("id"),
                j.getString("revision"),
                j.getString("name"),
                j.getString("mime"),
                j.getLong("bytes"),
                j.getString("sha"),
                j.getLong("modified"),
                j.getBoolean("sanitized"),
            )
            .also { it.validate() }

    fun manifest(m: LocalSharingManifest) =
        JSONObject()
            .put("v", 1)
            .put("strip", m.stripLocation)
            .put("entries", JSONArray().apply { m.entries.forEach { put(entry(it)) } })

    fun manifest(j: JSONObject): LocalSharingManifest {
        require(j.getInt("v") == 1)
        val a = j.getJSONArray("entries")
        require(a.length() in 1..LOCAL_SHARING_MAX_FILES)
        return LocalSharingManifest(
                List(a.length()) { entry(a.getJSONObject(it)) },
                j.getBoolean("strip"),
            )
            .also { it.validate() }
    }

    fun peer(p: LocalSharingPeer) =
        JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("host", p.host)
            .put("port", p.port)
            .put("revoked", p.revoked)
            .put("send", p.canSend)

    fun peer(j: JSONObject) =
        LocalSharingPeer(
                j.getString("id"),
                j.getString("name"),
                j.getString("host"),
                j.getInt("port"),
                j.getBoolean("revoked"),
                j.getBoolean("send"),
            )
            .also {
                require(
                    peerHash(it.id) &&
                        peerName(it.name) &&
                        peerHost(it.host) &&
                        it.port in 1024..65535
                )
            }

    fun transfer(t: LocalSharingTransfer) =
        JSONObject()
            .put("v", 1)
            .put("id", t.id)
            .put("peer", t.peerId)
            .put("direction", t.direction.name)
            .put("created", t.createdAt)
            .put("status", t.status.name)
            .put("selection", JSONArray(t.selection))
            .put("strip", t.stripLocation)
            .put("manifest", t.manifest?.let(::manifest))
            .put("files", JSONArray(t.preparedFiles))
            .put("done", t.bytesDone)
            .put("pause", t.pauseRequested)
            .put("cancel", t.cancelRequested)
            .put("child", t.localTaskId)
            .put("failure", t.failure)
            .put("resume", t.resumeStatus?.name)
            .put("choices", JSONObject().apply { t.choices.forEach { (k, v) -> put(k, v.name) } })

    fun transfer(j: JSONObject): LocalSharingTransfer {
        require(j.getInt("v") == 1)
        val choices = j.getJSONObject("choices")
        require(choices.length() <= LOCAL_SHARING_MAX_FILES)
        return LocalSharingTransfer(
                j.getString("id").also(::peerUuid),
                j.getString("peer").also { require(peerHash(it)) },
                LocalSharingDirection.valueOf(j.getString("direction")),
                j.getLong("created"),
                LocalSharingStatus.valueOf(j.getString("status")),
                strings(j, "selection"),
                j.getBoolean("strip"),
                j.optJSONObject("manifest")?.let(::manifest),
                strings(j, "files"),
                j.getLong("done"),
                j.getBoolean("pause"),
                j.getBoolean("cancel"),
                optional(j, "child"),
                optional(j, "failure"),
                choices.keys().asSequence().associateWith {
                    LocalSharingConflictChoice.valueOf(choices.getString(it))
                },
                optional(j, "resume")?.let(LocalSharingStatus::valueOf),
            )
            .also {
                require(it.selection.all { s -> s.startsWith("content://") && s.length <= 4096 })
                require(
                    it.preparedFiles.all { f ->
                        f.matches(Regex("[a-zA-Z0-9._/-]{1,200}")) &&
                            !f.contains("..") &&
                            !f.startsWith('/')
                    }
                )
                require(it.bytesDone in 0..LOCAL_SHARING_MAX_TOTAL_BYTES)
            }
    }

    private fun strings(j: JSONObject, key: String): List<String> {
        val a = j.getJSONArray(key)
        require(a.length() <= LOCAL_SHARING_MAX_FILES)
        return List(a.length()) { a.getString(it) }
    }

    private fun optional(j: JSONObject, key: String) = if (j.isNull(key)) null else j.getString(key)

    fun invitation(i: LocalSharingInvitation) =
        "ugallery-peer:" +
            JSONObject()
                .put("v", 1)
                .put("host", i.host)
                .put("port", i.port)
                .put("pin", i.pin)
                .put("secret", i.secret)
                .put("expires", i.expiresAt)
                .toString()

    fun invitation(text: String): LocalSharingInvitation {
        require(text.length <= 1024 && text.startsWith("ugallery-peer:"))
        val j = JSONObject(text.removePrefix("ugallery-peer:"))
        require(j.getInt("v") == 1)
        return LocalSharingInvitation(
                j.getString("host"),
                j.getInt("port"),
                j.getString("pin"),
                j.getString("secret"),
                j.getLong("expires"),
            )
            .also { it.validate() }
    }

    fun write(out: DataOutputStream, j: JSONObject) {
        val b = j.toString().toByteArray(Charsets.UTF_8)
        require(b.size in 1..PEER_FRAME_LIMIT)
        out.writeInt(b.size)
        out.write(b)
        out.flush()
    }

    fun read(input: DataInputStream): JSONObject {
        val n = input.readInt()
        if (n !in 1..PEER_FRAME_LIMIT) throw IOException("frame")
        val b = ByteArray(n)
        input.readFully(b)
        return JSONObject(String(b, Charsets.UTF_8))
    }
}
