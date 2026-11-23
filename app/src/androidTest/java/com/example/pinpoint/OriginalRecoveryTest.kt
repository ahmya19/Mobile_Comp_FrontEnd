package com.example.pinpoint

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.example.pinpoint.FakeSupabaseServer.Rpc
import com.example.pinpoint.data.OriginalLink
import com.example.pinpoint.data.OriginalUploadWorker
import com.example.pinpoint.data.QueuedReport
import com.example.pinpoint.data.ReportDraft
import com.example.pinpoint.data.ReportQueue
import com.example.pinpoint.data.SupabaseBackend
import com.example.pinpoint.data.WifiOriginals
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Crash-boundary matrix for the ORIGINAL-photo resumable upload (TUS) and its attach step. LOGIC TESTS ONLY: the resumable-upload
 * server is a model placed behind WifiOriginals' unchanged URL/host/port/token rules (OriginalLink seam). This does NOT test the real
 * Wi-Fi-bound HTTPS transport, cellular exclusion or Supabase's TUS server; that needs the real transport run on a device.
 * A "process death" is modelled by aborting the upload and rebuilding WifiOriginals from the persisted queue row; real SIGKILL
 * persistence of that row is covered by process_death_harness.py (S6).
 */
@RunWith(AndroidJUnit4::class)
class OriginalRecoveryTest {
    @get:Rule val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val project = "https://testproj.supabase.co".toHttpUrl()
    private val chunk = 6L * 1024 * 1024
    private lateinit var server: FakeSupabaseServer
    private lateinit var backend: SupabaseBackend
    private lateinit var queue: ReportQueue
    private lateinit var tus: FakeTus
    private val alice = "alice@orig.test"
    private val bob = "bob@orig.test"
    private fun uid(email: String) = server.userId(email)

    /** Resumable-upload server model. Offsets are authoritative on the "server"; faults are consumed per PATCH. */
    class FakeTus : OriginalLink {
        sealed class Fault { object LoseAck : Fault(); class Partial(val bytes: Int) : Fault(); class Status(val code: Int) : Fault() }
        class Session(val path: String, val length: Long) { val data = ByteArrayOutputStream() }
        val objects = ConcurrentHashMap<String, ByteArray>()
        val sessions = LinkedHashMap<String, Session>()
        val log = CopyOnWriteArrayList<String>()                 // "METHOD kind [offset]" per request, in order
        val tokens = CopyOnWriteArrayList<String>()
        val patchOffsets = CopyOnWriteArrayList<Long>()
        val faults = java.util.ArrayDeque<Fault>()
        val rejectOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var wifiLost = false
        @Volatile var headStatus: Int? = null
        @Volatile var createdSessions = 0
        override fun ensure() { if (wifiLost) throw IOException("Wi-Fi changed. The original will resume on Wi-Fi.") }
        private fun reply(req: Request, code: Int, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)) =
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("m").apply { headers.forEach { (k, v) -> header(k, v) } }
                .body(body.toResponseBody("application/octet-stream".toMediaType())).build()
        override suspend fun execute(request: Request): Response {
            val token = request.header("Authorization").orEmpty().removePrefix("Bearer "); tokens += token
            val path = request.url.encodedPath
            if (rejectOnce.remove(token)) { log += "${request.method} rejected"; return reply(request, 401) }
            when {
                request.method == "GET" && path.startsWith("/storage/v1/object/authenticated/report-originals/") -> {
                    log += "GET object"; val name = path.removePrefix("/storage/v1/object/authenticated/report-originals/")
                    return objects[name]?.let { reply(request, 200, body = it) } ?: reply(request, 404)
                }
                request.method == "POST" && path == "/storage/v1/upload/resumable" -> {
                    val meta = request.header("Upload-Metadata").orEmpty().split(',').associate { it.substringBefore(' ') to String(android.util.Base64.decode(it.substringAfter(' '), 0)) }
                    val id = UUID.randomUUID().toString(); sessions[id] = Session(meta.getValue("objectName"), request.header("Upload-Length")!!.toLong()); createdSessions++
                    log += "POST create"; return reply(request, 201, mapOf("Location" to "/storage/v1/upload/resumable/$id"))
                }
                request.method == "HEAD" && path.startsWith("/storage/v1/upload/resumable/") -> {
                    log += "HEAD"; headStatus?.let { headStatus = null; return reply(request, it) }
                    val s = sessions[path.substringAfterLast('/')] ?: return reply(request, 404)
                    return reply(request, 200, mapOf("Upload-Offset" to s.data.size().toString()))
                }
                request.method == "PATCH" && path.startsWith("/storage/v1/upload/resumable/") -> {
                    val s = sessions[path.substringAfterLast('/')] ?: return reply(request, 404)
                    val offset = request.header("Upload-Offset")!!.toLong(); patchOffsets += offset; log += "PATCH $offset"
                    if (offset != s.data.size().toLong()) return reply(request, 409)
                    val body = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                    when (val fault = faults.poll()) {
                        is Fault.Status -> return reply(request, fault.code)
                        is Fault.Partial -> { s.data.write(body, 0, minOf(fault.bytes, body.size)); throw IOException("connection reset mid-chunk") }
                        Fault.LoseAck -> { apply(s, path, body); throw IOException("acknowledgement lost") }
                        null -> { apply(s, path, body); return reply(request, 204, mapOf("Upload-Offset" to s.data.size().toString())) }
                    }
                }
            }
            return reply(request, 404)
        }
        private fun apply(s: Session, path: String, body: ByteArray) {
            s.data.write(body)
            if (s.data.size().toLong() == s.length) { objects[s.path] = s.data.toByteArray(); sessions.remove(path.substringAfterLast('/')) }
        }
    }

    @Before fun setup() {
        server = FakeSupabaseServer(); backend = SupabaseBackend.installForTesting(context, server.url, "sb_publishable_original_test")
        runBlocking { backend.auth.signIn(alice, "not-a-real-password") }
        val factory = object : WorkerFactory() { override fun createWorker(c: Context, n: String, p: WorkerParameters): ListenableWorker = SchedulingProbeWorker(c, p) }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        context.getDatabasePath("pinpoint_queue.db").parentFile?.mkdirs(); queue = ReportQueue.get(context)
        tus = FakeTus(); WifiOriginals.installForTesting { WifiOriginals.Companion.TestSetup(project, tus) }
    }

    @After fun cleanup() {
        queue.writableDatabase.query("queue", arrayOf("photo_file", "original_file"), "project=?", arrayOf(server.url), null, null, null).use { c ->
            while (c.moveToNext()) for (i in 0..1) c.getString(i)?.let { File(it).delete() } }
        queue.writableDatabase.delete("queue", "project=?", arrayOf(server.url))
        runCatching { runBlocking { backend.auth.signOut() } }
        WifiOriginals.resetForTesting()
        (WorkManager.getInstance(context) as WorkManagerImpl).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase(); WorkManagerImpl.setDelegate(null); SupabaseBackend.resetForTesting(); server.close()
        File(context.cacheDir, "orig-src.jpg").delete()
    }

    /** A decodable JPEG followed by seeded random padding: valid for the compressor, > one 6 MiB chunk for the original. */
    private fun originalFile(extraBytes: Int = 7 * 1024 * 1024): Pair<Uri, ByteArray> {
        val small = ByteArrayOutputStream().also { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(10, 120, 200)) }.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val bytes = small + ByteArray(extraBytes).also { Random(42).nextBytes(it) }
        val file = File(context.cacheDir, "orig-src.jpg").also { it.writeBytes(bytes) }
        return Uri.fromFile(file) to bytes
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun enqueueSyncedOriginal(owner: String = alice): Triple<String, ByteArray, QueuedReport> {
        val (uri, bytes) = originalFile(); val id = UUID.randomUUID().toString()
        runBlocking { queue.enqueue(backend, id, ReportDraft("Road Damage", "Original recovery pothole", "Main Road", "Alangilan"), uri, keepOriginal = true) }
        queue.setState(id, "Synced")        // the compressed report was already acknowledged; only the original remains
        return Triple(id, bytes, row(owner, id))
    }
    private fun row(owner: String, id: String) = queue.items(uid(owner), server.url).single { it.id == id }
    private fun objectPath(owner: String, id: String) = "${uid(owner)}/$id/original"
    /** Rebuilds the uploader from the PERSISTED row, as a restarted process does. */
    private fun upload(owner: String, id: String, onProgress: (Long) -> Unit = {}) = runBlocking {
        WifiOriginals.create(context, backend).upload(row(owner, id), onProgress) { url -> queue.saveUploadSession(id, url) }
    }
    private fun worker(owner: String): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<OriginalUploadWorker>(context).setInputData(workDataOf("owner" to uid(owner))).build().doWork()
    }
    private class Death : RuntimeException("process death")

    @Test fun deathAfterAnAcknowledgedChunkResumesFromTheServerOffsetWithoutResendingAckedBytes() {
        val (id, bytes, _) = enqueueSyncedOriginal()
        assertThrows(Death::class.java) { upload(alice, id) { throw Death() } }          // first chunk acknowledged, then the process dies
        assertEquals(listOf(0L), tus.patchOffsets); assertNotNull("the session survived in the queue row", row(alice, id).uploadUrl)
        upload(alice, id)
        assertEquals("only the remainder is sent after the restart", listOf(0L, chunk), tus.patchOffsets.toList())
        assertEquals(sha(bytes), sha(tus.objects.getValue(objectPath(alice, id)))); assertEquals(1, tus.createdSessions)
    }

    @Test fun serverAcceptedBytesButAcknowledgementLostIsReconciledByHead() {
        val (id, bytes, _) = enqueueSyncedOriginal(); tus.faults.add(FakeTus.Fault.LoseAck)
        assertThrows(IOException::class.java) { upload(alice, id) }
        upload(alice, id)
        assertEquals("HEAD reported the 6 MiB the client never saw acknowledged; nothing was resent", listOf(0L, chunk), tus.patchOffsets.toList())
        assertTrue(tus.log.contains("HEAD")); assertEquals(sha(bytes), sha(tus.objects.getValue(objectPath(alice, id))))
    }

    @Test fun interruptionMidChunkResumesAtTheNonAlignedServerOffset() {
        val (id, bytes, _) = enqueueSyncedOriginal(); tus.faults.add(FakeTus.Fault.Partial(1_000_000))
        assertThrows(IOException::class.java) { upload(alice, id) }
        upload(alice, id)
        assertEquals("the retry starts exactly where the server stopped, not at 0 or at a chunk boundary", 1_000_000L, tus.patchOffsets[1])
        assertEquals(sha(bytes), sha(tus.objects.getValue(objectPath(alice, id))))
    }

    @Test fun completedUploadWithLostAttachAcknowledgementConvergesToOneAttachAndOneObject() {
        val (id, bytes, _) = enqueueSyncedOriginal(); server.attachBehaviors.add(Rpc.DROP_AFTER_COMMIT)
        assertEquals(ListenableWorker.Result.retry(), worker(alice))
        assertEquals("Waiting for Wi-Fi", row(alice, id).originalState); assertTrue("local original kept until attach is acknowledged", File(row(alice, id).originalFile!!).isFile)
        assertEquals(ListenableWorker.Result.success(), worker(alice))
        assertEquals("Uploaded", row(alice, id).originalState); assertEquals("exactly one attach was ever committed", listOf(id), server.attachCommits.toList())
        assertEquals(1, tus.objects.size); assertEquals(sha(bytes), sha(tus.objects.getValue(objectPath(alice, id))))
        assertEquals("the retry sent no bytes: it verified the immutable object", 2, tus.patchOffsets.size)
        assertFalse("the local original is removed once attached", File(requireNotNull(row(alice, id).originalFile)).isFile)
    }

    @Test fun aTokenTheServerRejectsIsRefreshedOnceAndTheSameRequestRepeated() {
        val (id, bytes, _) = enqueueSyncedOriginal()
        tus.rejectOnce.add(runBlocking { backend.auth.accessToken(uid(alice)) })
        upload(alice, id)
        assertEquals(sha(bytes), sha(tus.objects.getValue(objectPath(alice, id))))
        assertTrue("the rejected request was repeated with a different token", tus.log.first().endsWith("rejected") && tus.tokens.distinct().size >= 2)
    }

    @Test fun anotherAccountCannotUploadOrAttachAnAccountsQueuedOriginal() {
        val (id, _, _) = enqueueSyncedOriginal()
        runBlocking { backend.auth.signOut(); backend.auth.signIn(bob, "not-a-real-password") }
        assertEquals(ListenableWorker.Result.success(), worker(alice))             // worker for A while B is signed in: does nothing
        assertThrows(IllegalStateException::class.java) { upload(alice, id) }       // and a direct attempt is refused before any request
        assertTrue("no request left the device", tus.log.isEmpty()); assertTrue(server.attachCommits.isEmpty())
        assertEquals("Waiting for Wi-Fi", queue.items(uid(alice), server.url).single { it.id == id }.originalState)
    }

    @Test fun wifiLossStopsTransferAtOnceAndTheUploadResumesWhenWifiReturns() {
        val (id, bytes, _) = enqueueSyncedOriginal(); tus.faults.add(FakeTus.Fault.Partial(500_000))
        assertEquals(ListenableWorker.Result.retry(), worker(alice))                       // interrupted mid-chunk: retry, not failure
        assertEquals("Waiting for Wi-Fi", row(alice, id).originalState)
        tus.wifiLost = true; val before = tus.log.size
        assertEquals(ListenableWorker.Result.retry(), worker(alice)); assertEquals("no request while Wi-Fi is lost", before, tus.log.size)
        assertEquals("Waiting for Wi-Fi", row(alice, id).originalState)
        tus.wifiLost = false; assertEquals(ListenableWorker.Result.success(), worker(alice))
        assertEquals(sha(bytes), sha(tus.objects.getValue(objectPath(alice, id)))); assertEquals(listOf(id), server.attachCommits.toList())
    }

    @Test fun noUnmeteredWifiDefersWithoutAnyRequest() {
        val (id, _, _) = enqueueSyncedOriginal()
        WifiOriginals.installForTesting { throw IOException("Waiting for unmetered Wi-Fi to transfer the original photo.") }
        assertEquals(ListenableWorker.Result.retry(), worker(alice)); assertEquals("Waiting for Wi-Fi", row(alice, id).originalState)
        assertTrue(tus.log.isEmpty()); assertTrue(server.attachCommits.isEmpty())
    }

    @Test fun anExpiredSessionIsReplacedByAFreshOneAndTheUploadCompletes() {
        val (id, bytes, _) = enqueueSyncedOriginal()
        assertThrows(Death::class.java) { upload(alice, id) { throw Death() } }
        val oldSession = row(alice, id).uploadUrl; tus.headStatus = 410
        upload(alice, id)
        assertEquals("a replacement session was created", 2, tus.createdSessions)
        assertEquals(sha(bytes), sha(tus.objects.getValue(objectPath(alice, id)))); assertNotNull(oldSession)
    }

    @Test fun anUnauthorizedSessionFailsWithoutSendingBytes() {
        val (id, _, _) = enqueueSyncedOriginal()
        assertThrows(Death::class.java) { upload(alice, id) { throw Death() } }
        tus.headStatus = 403; val patchesBefore = tus.patchOffsets.size
        assertEquals(ListenableWorker.Result.success(), worker(alice))
        assertEquals("Failed", row(alice, id).originalState); assertEquals("no bytes after the refusal", patchesBefore, tus.patchOffsets.size)
        assertTrue(server.attachCommits.isEmpty())
    }

    @Test fun conflictingBytesAtAnExistingObjectPathAreNeverAccepted() {
        val (id, _, _) = enqueueSyncedOriginal(); val path = objectPath(alice, id); val foreign = ByteArray(2048) { 7 }
        tus.objects[path] = foreign
        assertEquals(ListenableWorker.Result.success(), worker(alice))
        assertEquals("Failed", row(alice, id).originalState); assertEquals(0, tus.createdSessions); assertEquals(0, tus.patchOffsets.size)
        assertArrayEquals("the existing object is untouched", foreign, tus.objects.getValue(path)); assertTrue(server.attachCommits.isEmpty())
    }

    @Test fun untrustedSavedSessionUrlsNeverReachTheTransport() {
        val (id, _, _) = enqueueSyncedOriginal()
        for (bad in listOf("https://evil.example.com/storage/v1/upload/resumable/x", "http://testproj.storage.supabase.co/storage/v1/upload/resumable/x",
                "https://testproj.storage.supabase.co:8443/storage/v1/upload/resumable/x", "https://testproj.supabase.co.evil.com/storage/v1/upload/resumable/x")) {
            queue.saveUploadSession(id, bad)
            assertThrows(bad, IllegalArgumentException::class.java) { upload(alice, id) }
        }
        assertTrue("not one request reached the transport", tus.log.isEmpty())
    }

    @Test fun anOriginalLeftBehindAfterTheAttachWasAcknowledgedIsRemovedOnTheNextSchedule() {
        val (id, _, item) = enqueueSyncedOriginal(); val file = File(requireNotNull(item.originalFile))
        queue.setOriginalState(id, "Uploaded")          // attach acknowledged, then the process died before the file delete
        assertTrue(file.isFile); queue.schedule(uid(alice))
        assertFalse("a private original must not outlive its acknowledged attach", file.isFile)
    }
}
