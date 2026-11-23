package com.example.pinpoint

import android.Manifest
import android.app.NotificationManager
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
import com.example.pinpoint.FakeSupabaseServer.Refresh
import com.example.pinpoint.FakeSupabaseServer.Rpc
import com.example.pinpoint.data.PinPointMessagingService
import com.example.pinpoint.data.ReportDraft
import com.example.pinpoint.data.ReportQueue
import com.example.pinpoint.data.ReportSyncWorker
import com.example.pinpoint.data.SupabaseBackend
import com.example.pinpoint.data.cancelReportNotifications
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Deterministic failure matrix for Auth/session handling and the durable queue. Everything talks to a loopback fake
 * server through a debug-only backend seam and an isolated session store: no hosted call, no phone, no real account.
 * Emulator evidence only: it does not demonstrate OEM battery behavior, cellular handoff or delivery latency.
 */
@RunWith(AndroidJUnit4::class)
class AuthQueueFailureTest {
    @get:Rule val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: FakeSupabaseServer
    private lateinit var backend: SupabaseBackend
    private lateinit var queue: ReportQueue
    private val draft = ReportDraft("Road Damage", "A large pothole near the school.", "Main Road", "Alangilan")
    private val alice = "alice@failure.test"
    private val bob = "bob@failure.test"

    @Before fun setup() {
        server = FakeSupabaseServer()
        backend = SupabaseBackend.installForTesting(context, server.url, "sb_publishable_failure_test")
        // Scheduling must not run real workers in parallel with the explicit worker calls below.
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, name: String, parameters: WorkerParameters): ListenableWorker =
                SchedulingProbeWorker(appContext, parameters)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        context.getDatabasePath("pinpoint_queue.db").parentFile?.mkdirs()
        queue = ReportQueue.get(context)
    }

    @After fun cleanup() {
        runCatching { runBlocking { backend.auth.takeIf { it.currentUser != null }?.signOut() } }
        queue.writableDatabase.delete("queue", "project=?", arrayOf(server.url))
        (WorkManager.getInstance(context) as WorkManagerImpl).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase(); WorkManagerImpl.setDelegate(null)
        SupabaseBackend.resetForTesting(); server.close()
        cleanupReportNotifications()
        File(context.cacheDir, "failure-photo.jpg").delete()
    }

    private fun cleanupReportNotifications() = cancelReportNotifications(context)
    private fun signIn(email: String) = runBlocking { backend.auth.signIn(email, "not-a-real-password") }
    private fun uid(email: String) = server.userId(email)
    private fun runSync(owner: String): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<ReportSyncWorker>(context).setInputData(workDataOf("owner" to owner)).build().doWork()
    }
    private fun enqueue(id: String = UUID.randomUUID().toString(), photo: Uri? = null): String = runBlocking { queue.enqueue(backend, id, draft, photo) }
    private fun state(owner: String, id: String) = queue.items(owner, server.url).single { it.id == id }.state
    private fun photoUri(): Uri {
        val file = File(context.cacheDir, "failure-photo.jpg")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(90, 70, 180)) }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }; bitmap.recycle()
        return Uri.fromFile(file)
    }

    @Test fun revokedRefreshTokenLeavesWorkQueuedAndSignInDeliversItExactlyOnce() {
        server.nextExpiresIn = 30; server.refreshBehavior = Refresh.REVOKED_400
        signIn(alice)
        val id = enqueue()
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
        assertNull("a revoked refresh token must end the local session", backend.auth.currentUser)
        assertEquals("Queued", state(uid(alice), id)); assertEquals(0, server.creations.size)
        server.nextExpiresIn = 3600; server.refreshBehavior = Refresh.OK
        signIn(alice)
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
        assertEquals("Synced", state(uid(alice), id))
        assertEquals(listOf(uid(alice) to id), server.creations.toList())
        runSync(uid(alice)); assertEquals("a second run must not create a duplicate", 1, server.creations.size)
    }

    @Test fun transientRefreshFailuresKeepTheSessionAndRetry() {
        for (behavior in listOf(Refresh.SERVER_503, Refresh.DROP)) {
            server.nextExpiresIn = 30; server.refreshBehavior = Refresh.OK
            signIn(alice)
            val id = enqueue()
            server.refreshBehavior = behavior
            assertEquals("$behavior", ListenableWorker.Result.retry(), runSync(uid(alice)))
            assertNotNull("$behavior must not sign the user out", backend.auth.currentUser)
            assertEquals("Queued", state(uid(alice), id)); assertEquals(0, server.creations.size)
            server.refreshBehavior = Refresh.OK; server.nextExpiresIn = 3600
            assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
            assertEquals("Synced", state(uid(alice), id))
            server.creations.clear(); runBlocking { backend.auth.signOut() }
        }
    }

    @Test fun lostAcknowledgementAfterPhotoUploadAndCommitConvergesToOneReportAndOnePhoto() {
        signIn(alice)
        val id = enqueue(photo = photoUri())
        server.rpcBehaviors += Rpc.DROP_AFTER_COMMIT                  // the server commits, the response is lost
        assertEquals(ListenableWorker.Result.retry(), runSync(uid(alice)))
        assertEquals("Queued", state(uid(alice), id))
        assertEquals(1, server.photos.size); assertEquals(1, server.creations.size)
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))   // duplicate upload is verified, RPC is idempotent
        assertEquals("Synced", state(uid(alice), id))
        assertEquals(1, server.photos.size); assertEquals(1, server.creations.size)
        assertFalse("the saved photo copy is removed only after acknowledgement", queue.items(uid(alice), server.url).single().photoFile?.let { File(it).exists() } ?: false)
    }

    @Test fun uploadedPhotoWithoutCommitIsReusedOnRetry() {
        signIn(alice)
        val id = enqueue(photo = photoUri())
        server.rpcBehaviors += Rpc.DROP_BEFORE_COMMIT
        assertEquals(ListenableWorker.Result.retry(), runSync(uid(alice)))
        assertEquals(1, server.photos.size); assertEquals(0, server.creations.size)
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
        assertEquals("Synced", state(uid(alice), id)); assertEquals(1, server.photos.size); assertEquals(1, server.creations.size)
    }

    @Test fun transientPhotoSocketDropBeforeWriteRecoversInSingleWorkerRun() {
        signIn(alice)
        val id = enqueue(photo = photoUri())
        server.photoBehaviors += Rpc.DROP_BEFORE_COMMIT
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
        assertEquals("Synced", state(uid(alice), id))
        assertEquals(1, server.photos.size); assertEquals(1, server.creations.size)
        assertEquals("two photo POST attempts in one worker run", 2,
            server.requests.count { it.method == "POST" && it.path.startsWith("/storage/v1/object/report-images/") })
    }

    @Test fun transientPhotoSocketDropAfterWriteReusesPhotoInSingleWorkerRun() {
        signIn(alice)
        val id = enqueue(photo = photoUri())
        server.photoBehaviors += Rpc.DROP_AFTER_COMMIT
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
        assertEquals("Synced", state(uid(alice), id))
        assertEquals(1, server.photos.size); assertEquals(1, server.creations.size)
        assertEquals("duplicate check GET confirmed uploaded bytes", 1,
            server.requests.count { it.method == "GET" && it.path.startsWith("/storage/v1/object/authenticated/report-images/") })
    }

    @Test fun persistentPhotoSocketDropStillRetriesThroughWorkManager() {
        signIn(alice)
        val id = enqueue(photo = photoUri())
        server.photoBehaviors += Rpc.DROP_BEFORE_COMMIT
        server.photoBehaviors += Rpc.DROP_BEFORE_COMMIT
        assertEquals(ListenableWorker.Result.retry(), runSync(uid(alice)))
        assertEquals("Queued", state(uid(alice), id))
        assertEquals(0, server.photos.size); assertEquals(0, server.creations.size)
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
        assertEquals("Synced", state(uid(alice), id))
        assertEquals(1, server.photos.size); assertEquals(1, server.creations.size)
    }

    @Test fun processDeathMidSendIsRecoveredFromTheDurableRow() {
        signIn(alice)
        val id = enqueue()
        queue.setState(id, "Sending")                                // what a killed process leaves behind
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
        assertEquals("Synced", state(uid(alice), id)); assertEquals(1, server.creations.size)
    }

    @Test fun captivePortalPageIsRetriedNotMarkedFailed() {
        signIn(alice)
        val id = enqueue()
        server.rpcBehaviors += Rpc.HTML_200
        assertEquals(ListenableWorker.Result.retry(), runSync(uid(alice)))
        assertEquals("a web page instead of data is a connection problem, not a permanent failure", "Queued", state(uid(alice), id))
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
        assertEquals("Synced", state(uid(alice), id)); assertEquals(1, server.creations.size)
    }

    @Test fun serverErrorsRetryButValidationErrorsFailUntilTheUserRetries() {
        signIn(alice)
        val transient = enqueue(); server.rpcBehaviors += Rpc.SERVER_500
        assertEquals(ListenableWorker.Result.retry(), runSync(uid(alice))); assertEquals("Queued", state(uid(alice), transient))
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice))); assertEquals("Synced", state(uid(alice), transient))
        val rejected = enqueue(); server.rpcBehaviors += Rpc.VALIDATION_400
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
        assertEquals("Failed", state(uid(alice), rejected)); assertEquals(1, server.creations.size)
        queue.retry(uid(alice), server.url)                           // the user's "Retry uploads" button
        assertEquals("Queued", state(uid(alice), rejected))
        runSync(uid(alice)); assertEquals("Synced", state(uid(alice), rejected)); assertEquals(2, server.creations.size)
    }

    @Test fun tokenTheServerRejectsIsRefreshedOnceAndTheRequestRepeated() {
        signIn(alice)
        val id = enqueue()
        server.rejectOnce += server.latestAccessToken(uid(alice))!!   // e.g. a skewed device clock hides the expiry
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
        assertEquals("Synced", state(uid(alice), id)); assertEquals(1, server.creations.size)
        assertEquals("exactly one refresh", 1, server.requests.count { it.path == "/auth/v1/token" && it.query.contains("refresh_token") })
    }

    @Test fun accountSwitchNeverUploadsAnotherAccountsQueuedWorkAndReturnsToIt() {
        signIn(alice)
        val aliceReport = enqueue()
        runBlocking { backend.auth.signOut() }
        signIn(bob)
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))        // a late worker for alice while bob is active
        assertEquals("no request may carry alice's report or token", 0, server.requests.count { it.path.contains("submit_report") })
        assertEquals("Queued", state(uid(alice), aliceReport))
        assertTrue(queue.items(uid(bob), server.url).isEmpty())
        val bobReport = enqueue()
        assertEquals(ListenableWorker.Result.success(), runSync(uid(bob)))
        assertEquals(listOf(uid(bob) to bobReport), server.creations.toList())
        runBlocking { backend.auth.signOut() }; signIn(alice)
        assertEquals(ListenableWorker.Result.success(), runSync(uid(alice)))
        assertEquals(setOf(uid(bob) to bobReport, uid(alice) to aliceReport), server.creations.toSet())
        assertEquals("Synced", state(uid(alice), aliceReport)); assertTrue(queue.items(uid(bob), server.url).all { it.id == bobReport })
    }

    @Test fun lateWorkerCompletionAfterSwitchStaysWithItsOwnerAndKeepsTheNewSession() {
        signIn(alice)
        val id = enqueue()
        server.rpcBehaviors += Rpc.HOLD_THEN_OK
        var result: ListenableWorker.Result? = null
        val worker = thread { result = runSync(uid(alice)) }
        assertTrue("worker reached the server", server.rpcEntered.await(15, TimeUnit.SECONDS))
        runBlocking { backend.auth.signOut() }; signIn(bob)           // the user switches while the upload is in flight
        server.releaseHold.countDown(); worker.join(20_000)
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(uid(alice) to id), server.creations.toList())
        assertEquals("Synced", state(uid(alice), id))
        assertEquals("bob's session is untouched", uid(bob), backend.auth.currentUser?.id)
        assertTrue(queue.items(uid(bob), server.url).isEmpty())
        val submit = server.requests.single { it.path.contains("submit_report") }
        assertTrue("the in-flight request used alice's own token", submit.bearer!!.contains(uid(alice)))
    }

    @Test fun signOutRevokesOnlyTheLocalSessionAndClearsTheAccountState() {
        signIn(alice)
        runBlocking { backend.auth.signOut() }
        val logout = server.requests.single { it.path == "/auth/v1/logout" }
        assertEquals("scope=local", logout.query)
        assertNull(backend.auth.currentUser)
        val reopened = runCatching { runBlocking { backend.auth.accessToken() } }
        assertTrue("no token after sign-out", reopened.isFailure)
    }

    @Test fun notificationsAreRecipientBoundAndSignOutClearsThem() {
        signIn(alice)
        val service = PinPointMessagingService()
        ContextWrapperAttach.attach(service, context)
        fun message(user: String, tag: String) = RemoteMessage.Builder("test@fcm.googleapis.com").setMessageId(tag)
            .addData("userId", user).addData("reportId", UUID.randomUUID().toString()).addData("notificationId", tag)
            .addData("title", "Report updated").addData("body", "Your report is under review.").build()
        service.onMessageReceived(message(uid(bob), "for-bob"))
        service.onMessageReceived(message(uid(alice), "for-alice"))
        val manager = context.getSystemService(NotificationManager::class.java)
        val tags = { manager.activeNotifications.filter { it.id == 0 }.mapNotNull { it.tag } }
        // notify() and cancel() are asynchronous to the system server, so wait for the state instead of sampling once.
        fun awaitTags(expected: List<String>, label: String) {
            val deadline = System.currentTimeMillis() + 10_000
            while (tags() != expected && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals(label, expected, tags())
        }
        awaitTags(listOf("for-alice"), "only the signed-in recipient's notification is shown")
        cancelReportNotifications(context)
        awaitTags(emptyList(), "sign-out clears the account's notifications")
    }
}

/** FirebaseMessagingService has no public way to attach a base context outside the framework. */
object ContextWrapperAttach {
    fun attach(wrapper: android.content.ContextWrapper, base: Context) {
        android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(wrapper, base)
    }
}
