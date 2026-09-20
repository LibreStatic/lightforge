package com.ugallery.app

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.feature.localsharing.*
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Two real acceptance apps. The host owns the TLS-transparent relay and kills only the sender. */
class LocalSharingAppDeviceTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val args
        get() = InstrumentationRegistry.getArguments()

    private val device
        get() = UiDevice.getInstance(instrumentation)

    private val store
        get() = LocalSharingStore(context)

    private val root
        get() = File(context.filesDir, "peer-app-probe").apply { mkdirs() }

    private val record
        get() = File(root, "sender.json")

    private fun guard(phase: String) {
        assertEquals("com.ugallery.app.pdfacceptance", context.packageName)
        assertEquals("ugallery-peer-two-avd", args.getString("peerFixture"))
        assertEquals(phase, args.getString("peerPhase"))
        File(root, "abort-test").takeIf { it.exists() }?.let { assertTrue(it.delete()) }
    }

    private fun marker(value: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", value + "\n") })
    }

    private fun digest(input: InputStream): Pair<Long, String> =
        input.use {
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            var size = 0L
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                size += n
                require(size <= 32L * 1024 * 1024)
                md.update(buffer, 0, n)
            }
            size to md.digest().joinToString("") { b -> "%02x".format(b) }
        }

    private suspend fun await(timeout: Long = 180000, predicate: () -> Boolean) {
        try {
            withTimeout(timeout) {
                while (!predicate()) {
                    check(!File(root, "abort-test").exists()) {
                        "Host requested bounded fixture shutdown"
                    }
                    delay(100)
                }
            }
        } catch (failure: Throwable) {
            try {
                navigationEvidence("checkpoint-not-reached")
            } catch (evidenceFailure: Throwable) {
                failure.addSuppressed(evidenceFailure)
            }
            throw failure
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            require(result.size < 2048)
            result.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun scrollList(forward: Boolean): Boolean {
        val list =
            nodes()
                .filter { it.isScrollable && it.isVisibleToUser }
                .maxByOrNull {
                    val bounds = Rect()
                    it.getBoundsInScreen(bounds)
                    bounds.width().toLong() * bounds.height()
                } ?: return false
        return list.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    private fun navigationEvidence(tag: String) {
        // Only structural IDs/state/bounds, never text, clipboard, code contents, or QR pixels.
        val rows = JSONArray()
        nodes().forEach {
            val bounds = Rect()
            it.getBoundsInScreen(bounds)
            rows.put(
                JSONObject()
                    .put("id", it.viewIdResourceName)
                    .put("bounds", bounds.toShortString())
                    .put("enabled", it.isEnabled)
                    .put("visible", it.isVisibleToUser)
                    .put("scrollable", it.isScrollable)
                    .put("clickable", it.isClickable)
            )
        }
        val peers = JSONArray()
        store.peers().forEach { peer ->
            peers.put(
                JSONObject()
                    .put("id", peer.id)
                    .put("host", peer.host)
                    .put("port", peer.port)
                    .put("canSend", peer.canSend)
                    .put("revoked", peer.revoked)
            )
        }
        val pending = JSONArray()
        LocalSharingReceiver.state.value.pending.forEach { pending.put(it.pin) }
        File(root, "navigation.json")
            .writeText(
                JSONObject()
                    .put("requested", tag)
                    .put("nodes", rows)
                    .put("peers", peers)
                    .put("pendingPins", pending)
                    .toString()
            )
    }

    private fun taskClick(tag: String) {
        require(tag.startsWith("peer-review-") || tag.startsWith("peer-resume-"))
        val until = SystemClock.elapsedRealtime() + 30000
        while (SystemClock.elapsedRealtime() < until) {
            check(!File(root, "abort-test").exists()) { "Host requested bounded fixture shutdown" }
            nodes()
                .firstOrNull { it.viewIdResourceName == tag && it.isEnabled && it.isVisibleToUser }
                ?.let {
                    if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        device.waitForIdle()
                        return
                    }
                }
            // History follows all source/profile cards. Move monotonically toward it: UiObject2
            // gesture false previously reversed prematurely and never reached the actual tagged
            // row.
            scrollList(true)
            device.waitForIdle()
            SystemClock.sleep(100)
        }
        navigationEvidence(tag)
        error("Missing exact peer task action: $tag")
    }

    private fun find(selector: BySelector, timeout: Long = 25000): UiObject2 {
        val until = SystemClock.elapsedRealtime() + timeout
        var direction = Direction.UP
        var steps = 0
        while (SystemClock.elapsedRealtime() < until) {
            check(!File(root, "abort-test").exists()) { "Host requested bounded fixture shutdown" }
            try {
                device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it }
                if (!scrollList(direction == Direction.DOWN) || ++steps >= 9) {
                    direction = if (direction == Direction.UP) Direction.DOWN else Direction.UP
                    steps = 0
                }
            } catch (_: StaleObjectException) {}
            device.waitForIdle()
        }
        navigationEvidence(selector.toString())
        error("Missing peer control: $selector")
    }

    private fun click(selector: BySelector) {
        find(selector.enabled(true)).click()
        device.waitForIdle()
    }

    private fun taggedClick(tag: String) {
        val until = SystemClock.elapsedRealtime() + 25000
        while (SystemClock.elapsedRealtime() < until) {
            find(By.res(tag).enabled(true))
            nodes()
                .firstOrNull { it.viewIdResourceName == tag && it.isEnabled && it.isVisibleToUser }
                ?.let {
                    if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        device.waitForIdle()
                        return
                    }
                }
        }
        navigationEvidence(tag)
        error("Exact tagged peer action was not dispatched: $tag")
    }

    private fun pairingCodeCleared(): Boolean {
        val field = nodes().firstOrNull { it.viewIdResourceName == "peer-code" } ?: return false
        fun editor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable || node.className == "android.widget.EditText") return node
            return (0 until node.childCount).firstNotNullOfOrNull {
                node.getChild(it)?.let(::editor)
            }
        }
        // Inspect only the editable value, never the separate floating label. Neither the
        // invitation nor token is returned or logged; endpoint equality is checked separately.
        val editable = editor(field) ?: return false
        return editable.text.isNullOrEmpty()
    }

    private fun type(tag: String, value: String) {
        val node = find(By.res(tag))
        (if (node.className == "android.widget.EditText") node
            else node.findObject(By.clazz("android.widget.EditText")) ?: node)
            .text = value
        device.waitForIdle()
    }

    private fun hideKeyboard() {
        if (
            device.hasObject(By.pkg("com.google.android.inputmethod.latin")) ||
                device.hasObject(By.pkg("com.android.inputmethod.latin"))
        )
            device.pressBack()
    }

    private fun openRoute() {
        device
            .findObject(By.res("com.android.permissioncontroller:id/permission_allow_button"))
            ?.click()
        click(By.desc(context.getString(com.ugallery.feature.photos.R.string.open_settings)))
        click(By.text(context.getString(com.ugallery.feature.settings.R.string.settings_backup)))
        click(
            By.text(context.getString(com.ugallery.feature.settings.R.string.local_sharing_entry))
        )
        find(By.res("peer-select-sources"))
    }

    private fun selectOriginal(name: String) {
        taggedClick("peer-select-sources")
        val item = By.res("android:id/title").text(name)
        if (!device.wait(Until.hasObject(item), 10000)) {
            val drawer =
                if (device.hasObject(By.desc("Show roots"))) By.desc("Show roots")
                else By.desc("Navigate up")
            click(drawer)
            click(By.res("android:id/title").text("Downloads"))
        }
        click(item)
        find(By.res("peer-prepare"))
        assertTrue(find(By.res("peer-strip-location")).isChecked)
    }

    private suspend fun review(id: String) {
        val previous = requireNotNull(store.transfer(id)).status
        taskClick("peer-review-$id")
        find(By.res("peer-review-dialog"))
        taggedClick("peer-confirm-review")
        await(30000) { store.transfer(id)?.status != previous }
    }

    private suspend fun sent(id: String) {
        await {
            val state = requireNotNull(store.transfer(id))
            if (state.status in setOf(LocalSharingStatus.WaitingPeer, LocalSharingStatus.Paused))
                taskClick("peer-resume-$id")
            check(state.status != LocalSharingStatus.NeedsReview) {
                "Sender needs review: ${state.failure}"
            }
            state.status == LocalSharingStatus.Completed
        }
    }

    private suspend fun pairFromInbox(expectedPin: String?): String {
        val inbox = File(root, "invitation.private")
        await { inbox.exists() && inbox.length() > 0 }
        val code = inbox.readText()
        assertTrue(inbox.delete())
        val invitation = JSONObject(code.removePrefix("ugallery-peer:"))
        val pin = invitation.getString("pin")
        expectedPin?.let { assertEquals(it, pin) }
        type("peer-code", code)
        hideKeyboard()
        taggedClick("peer-pair")
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        await {
            keyStore.containsAlias(context.packageName + ".ugallery-local-sharing-identity-v2")
        }
        val certificate =
            requireNotNull(
                keyStore.getCertificate(context.packageName + ".ugallery-local-sharing-identity-v2")
            )
        val identity =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { b ->
                "%02x".format(b)
            }
        marker("PEER_SENDER_PAIR_REQUEST pin=$identity")
        await { File(root, "pair-approved").exists() }
        assertTrue(File(root, "pair-approved").delete())
        taggedClick("peer-pair")
        find(By.res("peer-code"))
        await {
            store.peers().any { peer ->
                peer.id == pin &&
                    peer.canSend &&
                    !peer.revoked &&
                    peer.host == invitation.getString("host") &&
                    peer.port == invitation.getInt("port")
            } && pairingCodeCleared()
        }
        marker(
            "PEER_ENDPOINT_CONFIRMED pin=$pin port=${invitation.getInt("port")} pairedResponse=true"
        )
        return pin
    }

    private suspend fun selectPeer(pin: String) {
        taggedClick("peer-profile-$pin")
        await(30000) { find(By.res("peer-profile-$pin")).isChecked }
    }

    private fun launch() =
        ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    @Test
    fun receiveConsentImportAndHistoricalRepeatOnRealSecondAndroid() =
        runBlocking<Unit> {
            guard("receiver")
            val before = store.transfers().map { it.id }.toSet()
            val database = GalleryDatabaseFactory.open(context)
            val repeatOnly = args.getString("peerRepeatOnly") == "true"
            val historical =
                if (repeatOnly) {
                    val previous = JSONObject(File(root, "receiver-round-0.json").readText())
                    val transfer = requireNotNull(store.transfer(previous.getString("transfer")))
                    assertEquals(LocalSharingStatus.LocalTaskCreated, transfer.status)
                    assertEquals(previous.getString("receipt"), transfer.localTaskId)
                    val receipt =
                        requireNotNull(
                            database.galleryRestoreReceiptDao().get(transfer.localTaskId!!)
                        )
                    assertEquals(1, receipt.files)
                    val version =
                        database.peerImportDao().operationVersions(transfer.localTaskId!!).single()
                    assertEquals(previous.getString("sha"), version.sha256)
                    assertEquals(
                        previous.getString("sha"),
                        transfer.manifest!!.entries.single().sha256,
                    )
                    transfer
                } else null
            val published = linkedMapOf<Uri, String>()
            var failure: Throwable? = null
            try {
                launch().use {
                    openRoute()
                    type("peer-host", "10.0.2.2")
                    hideKeyboard()
                    taggedClick("peer-start-receive")
                    await {
                        LocalSharingReceiver.state.value.active &&
                            LocalSharingReceiver.state.value.invitation != null
                    }
                    val invitation = requireNotNull(LocalSharingReceiver.state.value.invitation)
                    taggedClick("peer-copy-code")
                    var secret = ""
                    instrumentation.runOnMainSync {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        secret = requireNotNull(clipboard.primaryClip).getItemAt(0).text.toString()
                        clipboard.clearPrimaryClip()
                    }
                    require(secret.startsWith("ugallery-peer:"))
                    File(root, "invitation.private").writeText(secret)
                    secret = ""
                    marker(
                        "PEER_RECEIVER_READY port=${invitation.port} pin=${invitation.pin} pid=${Process.myPid()}"
                    )
                    val expected = File(root, "expected-peer.txt")
                    await { expected.exists() && expected.length() == 64L }
                    val senderPin =
                        expected.readText().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                    assertTrue(expected.delete())
                    await { LocalSharingReceiver.state.value.pending.any { it.pin == senderPin } }
                    val previousPeer = store.peers().singleOrNull { it.id == senderPin }
                    taggedClick("peer-approve-$senderPin")
                    await(30000) {
                        LocalSharingReceiver.state.value.pending.none { it.pin == senderPin } &&
                            store.peers().any { peer ->
                                peer.id == senderPin &&
                                    !peer.revoked &&
                                    if (previousPeer?.canSend == true && !previousPeer.revoked)
                                        peer.canSend &&
                                            peer.host == previousPeer.host &&
                                            peer.port == previousPeer.port
                                    else !peer.canSend && peer.port == invitation.port
                            }
                    }
                    marker("PEER_RECEIVER_APPROVED pin=$senderPin")
                    var first: LocalSharingTransfer? = historical
                    for (round in if (repeatOnly) listOf(1) else listOf(0, 1)) {
                        await {
                            store.transfers().any {
                                it.id !in before &&
                                    it.direction == LocalSharingDirection.Receive &&
                                    it.peerId == senderPin &&
                                    it.status == LocalSharingStatus.AwaitingReceiveConsent
                            }
                        }
                        val offer =
                            store.transfers().single {
                                it.id !in before &&
                                    it.direction == LocalSharingDirection.Receive &&
                                    it.peerId == senderPin &&
                                    it.status == LocalSharingStatus.AwaitingReceiveConsent
                            }
                        assertTrue(requireNotNull(offer.manifest).stripLocation)
                        assertEquals(1, offer.manifest!!.entries.size)
                        assertTrue(offer.manifest!!.entries.single().sanitized)
                        review(offer.id)
                        marker("PEER_RECEIVE_ACCEPTED round=$round transfer=${offer.id}")
                        await(240000) {
                            store.transfer(offer.id)?.status == LocalSharingStatus.ReadyToImport
                        }
                        val entry = offer.manifest!!.entries.single()
                        val received =
                            File(
                                context.filesDir,
                                "local-sharing/tasks/${offer.id}/received-0.partial",
                            )
                        assertEquals(entry.bytes to entry.sha256, digest(received.inputStream()))
                        assertNull(
                            ExifInterface(received).getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                        )
                        assertNull(
                            ExifInterface(received).getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                        )
                        val importer = GalleryLocalSharingImportPort(context, database)
                        val items = importer.review(senderPin, offer.id, offer.manifest!!)
                        assertEquals(
                            if (round == 0) LocalSharingImportDisposition.Add
                            else LocalSharingImportDisposition.AlreadyReceived,
                            items.single().disposition,
                        )
                        review(offer.id)
                        await {
                            store.transfer(offer.id)?.status == LocalSharingStatus.LocalTaskCreated
                        }
                        val op = requireNotNull(store.transfer(offer.id)?.localTaskId)
                        val receipt = requireNotNull(database.galleryRestoreReceiptDao().get(op))
                        val versions = database.peerImportDao().operationVersions(op)
                        assertEquals(if (round == 0) 1 else 0, receipt.files)
                        assertEquals(if (round == 0) 1 else 0, versions.size)
                        if (round == 0) {
                            first = offer
                            val uri = Uri.parse(versions.single().uri)
                            assertEquals(
                                entry.bytes to entry.sha256,
                                digest(requireNotNull(context.contentResolver.openInputStream(uri))),
                            )
                            published[uri] = entry.sha256
                        } else {
                            assertEquals(first!!.manifest, offer.manifest)
                            published.forEach { (uri, sha) ->
                                assertEquals(
                                    sha,
                                    digest(
                                            requireNotNull(
                                                context.contentResolver.openInputStream(uri)
                                            )
                                        )
                                        .second,
                                )
                            }
                        }
                        File(root, "receiver-round-$round.json")
                            .writeText(
                                JSONObject()
                                    .put("transfer", offer.id)
                                    .put("receipt", op)
                                    .put("files", receipt.files)
                                    .put("sha", entry.sha256)
                                    .put("gpsAbsent", true)
                                    .put("round", round)
                                    .toString()
                            )
                        marker(
                            "PEER_IMPORT_RECEIPT round=$round transfer=${offer.id} files=${receipt.files} sha=${entry.sha256}"
                        )
                    }
                    taggedClick("peer-stop-receive")
                    await { !LocalSharingReceiver.state.value.active }
                    marker(
                        if (repeatOnly)
                            "PEER_RECEIVER_REPEAT_PASS stopped=true historicalReceiptPreserved=true repeatCopies=0"
                        else "PEER_RECEIVER_PASS stopped=true copies=1 repeatCopies=0"
                    )
                }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                GalleryLocalSharingReceiveService.stop(context)
                File(root, "invitation.private").delete()
                try {
                    published.forEach { (uri, expectedSha) ->
                        assertEquals(
                            expectedSha,
                            digest(requireNotNull(context.contentResolver.openInputStream(uri)))
                                .second,
                        )
                        assertEquals(1, context.contentResolver.delete(uri, null, null))
                    }
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                } finally {
                    database.close()
                }
            }
        }

    @Test
    fun sendOriginalThroughSafAndWaitForHostProcessDeath() =
        runBlocking<Unit> {
            guard("sender-prepare")
            val prior = record.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
            val reused =
                prior?.let { saved ->
                    require(
                        saved.getString("name").matches(Regex("peer-original-[a-f0-9-]{36}\\.jpg"))
                    )
                    val task = requireNotNull(store.transfer(saved.getString("transfer")))
                    assertTrue(
                        task.status in
                            setOf(LocalSharingStatus.AwaitingReview, LocalSharingStatus.WaitingPeer)
                    )
                    if (task.status == LocalSharingStatus.WaitingPeer) {
                        assertEquals(LocalSharingStatus.Queued, task.resumeStatus)
                        assertEquals(0L, task.bytesDone)
                        assertFalse(task.pauseRequested || task.cancelRequested)
                    }
                    assertEquals(LocalSharingDirection.Send, task.direction)
                    assertEquals(saved.getString("peer"), task.peerId)
                    assertEquals(saved.getString("selection"), task.selection.single())
                    assertEquals(
                        saved.getLong("sourceBytes") to saved.getString("sourceSha"),
                        digest(
                            context.contentResolver.openInputStream(
                                Uri.parse(saved.getString("source"))
                            )!!
                        ),
                    )
                    val taskRoot = File(context.filesDir, "local-sharing/tasks/${task.id}")
                    val prepared = File(taskRoot, task.preparedFiles.single())
                    require(
                        prepared.canonicalPath.startsWith(taskRoot.canonicalPath + File.separator)
                    )
                    val entry = task.manifest!!.entries.single()
                    assertEquals(
                        saved.getLong("preparedBytes") to saved.getString("preparedSha"),
                        digest(prepared.inputStream()),
                    )
                    assertEquals(entry.bytes to entry.sha256, digest(prepared.inputStream()))
                    assertTrue(
                        context.contentResolver.persistedUriPermissions.any {
                            it.uri.toString() == task.selection.single() && it.isReadPermission
                        }
                    )
                    marker(
                        "PEER_REUSE_PREPARED transfer=${task.id} sourceSha=${saved.getString("sourceSha")} preparedSha=${entry.sha256} persistedGrant=true approvedSnapshot=${task.status == LocalSharingStatus.WaitingPeer}"
                    )
                    task
                }
            if (prior == null) {
                val name = "peer-original-${UUID.randomUUID()}.jpg"
                val uri =
                    requireNotNull(
                        context.contentResolver.insert(
                            MediaStore.Downloads.getContentUri("external_primary"),
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            },
                        )
                    )
                val pixels = IntArray(2048 * 2048)
                val random = java.util.Random(835831L)
                for (i in pixels.indices) pixels[i] =
                    0xff000000.toInt() or random.nextInt(0x1000000)
                val bitmap = Bitmap.createBitmap(pixels, 2048, 2048, Bitmap.Config.ARGB_8888)
                context.contentResolver.openOutputStream(uri)!!.use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it))
                }
                bitmap.recycle()
                context.contentResolver.openFileDescriptor(uri, "rw")!!.use {
                    ExifInterface(it.fileDescriptor).apply {
                        setAttribute(ExifInterface.TAG_GPS_LATITUDE, "12/1,20/1,24/1")
                        setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                        setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "56/1,46/1,48/1")
                        setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                        saveAttributes()
                    }
                }
                assertEquals(
                    1,
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    ),
                )
                context.contentResolver.openInputStream(uri)!!.use {
                    assertNotNull(ExifInterface(it).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                }
                val original = digest(context.contentResolver.openInputStream(uri)!!)
                record.writeText(
                    JSONObject()
                        .put("source", uri.toString())
                        .put("name", name)
                        .put("sourceSha", original.second)
                        .put("sourceBytes", original.first)
                        .put("oldPid", Process.myPid())
                        .toString()
                )
            }
            val name = JSONObject(record.readText()).getString("name")
            launch().use {
                openRoute()
                val pin = pairFromInbox(prior?.getString("peer"))
                selectPeer(pin)
                val transfer =
                    reused
                        ?: run {
                            selectOriginal(name)
                            val baseline = store.transfers().map { t -> t.id }.toSet()
                            taggedClick("peer-prepare")
                            await {
                                store.transfers().any { t ->
                                    t.id !in baseline &&
                                        t.direction == LocalSharingDirection.Send &&
                                        t.status == LocalSharingStatus.AwaitingReview
                                }
                            }
                            store.transfers().single { t ->
                                t.id !in baseline && t.direction == LocalSharingDirection.Send
                            }
                        }
                val entry = requireNotNull(transfer.manifest).entries.single()
                assertTrue(entry.sanitized)
                assertTrue(entry.bytes > 1024 * 1024)
                val saved =
                    JSONObject(record.readText())
                        .put("oldPid", Process.myPid())
                        .put("reusedPrepared", reused != null)
                        .put("transfer", transfer.id)
                        .put("peer", pin)
                        .put("preparedSha", entry.sha256)
                        .put("preparedBytes", entry.bytes)
                        .put("selection", transfer.selection.single())
                record.writeText(saved.toString())
                if (transfer.status == LocalSharingStatus.WaitingPeer) {
                    marker("PEER_APPROVED_SNAPSHOT_RESUMED transfer=${transfer.id}")
                    taskClick("peer-resume-${transfer.id}")
                } else review(transfer.id)
                await {
                    store.transfer(transfer.id)?.status in
                        setOf(LocalSharingStatus.WaitingPeer, LocalSharingStatus.Transferring)
                }
                marker(
                    "PEER_SEND_WAITING transfer=${transfer.id} pid=${Process.myPid()} bytes=${entry.bytes} sha=${entry.sha256}"
                )
                await { File(root, "receive-approved").exists() }
                assertTrue(File(root, "receive-approved").delete())
                if (store.transfer(transfer.id)?.status == LocalSharingStatus.WaitingPeer)
                    taskClick("peer-resume-${transfer.id}")
                await { store.transfer(transfer.id)?.status == LocalSharingStatus.Transferring }
                marker("PEER_SEND_TRANSFERRING transfer=${transfer.id} pid=${Process.myPid()}")
                await(180000) { false }
                error("Host did not perform the required actual sender process death")
            }
        }

    @Test
    fun reopenSameSenderTransferAndRepeatWithoutAnotherGalleryCopy() =
        runBlocking<Unit> {
            guard("sender-verify")
            val saved = JSONObject(record.readText())
            val id = saved.getString("transfer")
            val uri = Uri.parse(saved.getString("source"))
            assertNotEquals(saved.getInt("oldPid"), Process.myPid())
            assertEquals(
                saved.getString("sourceSha"),
                digest(context.contentResolver.openInputStream(uri)!!).second,
            )
            assertTrue(
                context.contentResolver.persistedUriPermissions.any {
                    it.uri.toString() == saved.getString("selection") && it.isReadPermission
                }
            )
            launch().use {
                openRoute()
                sent(id)
                val completed = requireNotNull(store.transfer(id))
                assertEquals(
                    saved.getString("preparedSha"),
                    completed.manifest!!.entries.single().sha256,
                )
                await { File(root, "first-imported").exists() }
                assertTrue(File(root, "first-imported").delete())
                selectPeer(saved.getString("peer"))
                selectOriginal(saved.getString("name"))
                val before = store.transfers().map { t -> t.id }.toSet()
                taggedClick("peer-prepare")
                await {
                    store.transfers().any { t ->
                        t.id !in before && t.status == LocalSharingStatus.AwaitingReview
                    }
                }
                val second =
                    store.transfers().single { t ->
                        t.id !in before && t.direction == LocalSharingDirection.Send
                    }
                assertEquals(completed.manifest, second.manifest)
                review(second.id)
                sent(second.id)
                await { File(root, "repeat-imported").exists() }
                assertTrue(File(root, "repeat-imported").delete())
                assertEquals(
                    saved.getString("sourceSha"),
                    digest(context.contentResolver.openInputStream(uri)!!).second,
                )
                File(root, "sender-result.json")
                    .writeText(
                        saved
                            .put("newPid", Process.myPid())
                            .put("repeat", second.id)
                            .put("sameTransferCompleted", true)
                            .put("persistedGrant", true)
                            .put("sourceUnchanged", true)
                            .toString()
                    )
                marker(
                    "PEER_SENDER_RECOVERY_PASS transfer=$id repeat=${second.id} pid=${Process.myPid()} sourceUnchanged=true"
                )
            }
            assertEquals(1, context.contentResolver.delete(uri, null, null))
            assertTrue(record.delete())
        }

    @Test
    fun repeatPreviouslyImportedOriginalWithoutNewGalleryCopy() =
        runBlocking<Unit> {
            guard("sender-repeat")
            val saved = JSONObject(record.readText())
            val original = Uri.parse(saved.getString("source"))
            assertEquals(
                saved.getLong("sourceBytes") to saved.getString("sourceSha"),
                digest(requireNotNull(context.contentResolver.openInputStream(original))),
            )
            assertTrue(
                context.contentResolver.persistedUriPermissions.any {
                    it.uri.toString() == saved.getString("selection") && it.isReadPermission
                }
            )
            val previous = requireNotNull(store.transfer(saved.getString("transfer")))
            assertEquals(LocalSharingStatus.Completed, previous.status)
            val manifest = requireNotNull(previous.manifest)
            assertEquals(saved.getString("preparedSha"), manifest.entries.single().sha256)
            val prepared =
                File(
                    context.filesDir,
                    "local-sharing/tasks/${previous.id}/${previous.preparedFiles.single()}",
                )
            assertEquals(
                saved.getLong("preparedBytes") to saved.getString("preparedSha"),
                digest(prepared.inputStream()),
            )
            launch().use {
                openRoute()
                val pin = pairFromInbox(saved.getString("peer"))
                selectPeer(pin)
                selectOriginal(saved.getString("name"))
                val before = store.transfers().map { it.id }.toSet()
                taggedClick("peer-prepare")
                await {
                    store.transfers().any {
                        it.id !in before && it.status == LocalSharingStatus.AwaitingReview
                    }
                }
                val next =
                    store.transfers().single {
                        it.id !in before && it.direction == LocalSharingDirection.Send
                    }
                assertEquals(manifest, next.manifest)
                marker(
                    "PEER_REPEAT_PREPARED transfer=${next.id} previous=${previous.id} sha=${manifest.entries.single().sha256}"
                )
                review(next.id)
                sent(next.id)
                await { File(root, "repeat-imported").exists() }
                assertTrue(File(root, "repeat-imported").delete())
                assertEquals(
                    saved.getLong("sourceBytes") to saved.getString("sourceSha"),
                    digest(requireNotNull(context.contentResolver.openInputStream(original))),
                )
                assertEquals(LocalSharingStatus.Completed, store.transfer(previous.id)?.status)
                File(root, "sender-repeat-result.json")
                    .writeText(
                        JSONObject()
                            .put("previous", previous.id)
                            .put("repeat", next.id)
                            .put("sameManifest", true)
                            .put("sourceUnchanged", true)
                            .put("persistedGrant", true)
                            .put("sha", manifest.entries.single().sha256)
                            .put("pid", Process.myPid())
                            .toString()
                    )
                marker(
                    "PEER_SENDER_REPEAT_PASS transfer=${next.id} previous=${previous.id} sourceUnchanged=true"
                )
            }
        }
}
