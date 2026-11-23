package com.example.pinpoint

import android.Manifest
import android.content.Context
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
import com.example.pinpoint.data.ReportDraft
import com.example.pinpoint.data.ReportQueue
import com.example.pinpoint.data.ReportSyncWorker
import com.example.pinpoint.data.SupabaseBackend
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The sync worker lists the Queued rows once and then submits them one by one. A user may delete or edit an UNSENT row
 * (policy-safe local actions) while the worker is busy with an earlier row. The worker must re-claim each row atomically
 * and use the CURRENT stored content; it must never submit a report the user already removed or an outdated draft.
 */
@RunWith(AndroidJUnit4::class)
class QueueClaimRaceTest {
    @get:Rule val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: FakeSupabaseServer
    private lateinit var backend: SupabaseBackend
    private lateinit var queue: ReportQueue
    private val alice = "alice@race.test"
    private fun uid() = server.userId(alice)
    private fun draft(text: String) = ReportDraft("Road Damage", text, "Main Road", "Alangilan")

    @Before fun setup() {
        server = FakeSupabaseServer(); backend = SupabaseBackend.installForTesting(context, server.url, "sb_publishable_race_test")
        runBlocking { backend.auth.signIn(alice, "not-a-real-password") }
        val factory = object : WorkerFactory() { override fun createWorker(c: Context, n: String, p: WorkerParameters): ListenableWorker = SchedulingProbeWorker(c, p) }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        context.getDatabasePath("pinpoint_queue.db").parentFile?.mkdirs(); queue = ReportQueue.get(context)
    }
    @After fun cleanup() {
        queue.writableDatabase.delete("queue", "project=?", arrayOf(server.url)); runCatching { runBlocking { backend.auth.signOut() } }
        (WorkManager.getInstance(context) as WorkManagerImpl).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase(); WorkManagerImpl.setDelegate(null); SupabaseBackend.resetForTesting(); server.close()
    }

    /** Enqueues A (older) then B (newer). The worker processes B first and is held inside its RPC while [during] runs. */
    private fun withWorkerHeldOnB(during: (aId: String, bId: String) -> Unit): Pair<String, String> {
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        runBlocking { queue.enqueue(backend, a, draft("Report A original text"), null); Thread.sleep(5); queue.enqueue(backend, b, draft("Report B text"), null) }
        server.rpcBehaviors.add(Rpc.HOLD_THEN_OK)
        var result: ListenableWorker.Result? = null
        val worker = thread {
            result = runBlocking { TestListenableWorkerBuilder<ReportSyncWorker>(context).setInputData(workDataOf("owner" to uid())).build().doWork() }
        }
        assertTrue("the worker reached B's submit", server.rpcEntered.await(15, TimeUnit.SECONDS))
        during(a, b)
        server.releaseHold.countDown(); worker.join(30_000)
        assertEquals(ListenableWorker.Result.success(), result)
        return a to b
    }

    @Test fun anUnsentReportDeletedWhileTheWorkerIsBusyIsNeverSubmitted() {
        val (a, b) = withWorkerHeldOnB { aId, _ -> assertTrue(queue.deleteUnsent(uid(), server.url, aId)) }
        assertEquals("only B was committed", listOf(uid() to b), server.creations.toList())
        assertFalse("the deleted report must not exist on the server", server.reports.containsKey(a))
        assertNull(queue.items(uid(), server.url).firstOrNull { it.id == a })
    }

    @Test fun anUnsentReportEditedWhileTheWorkerIsBusyIsSubmittedWithTheEditedContent() {
        val (a, _) = withWorkerHeldOnB { aId, _ -> assertTrue(queue.updateUnsent(uid(), server.url, aId, draft("Report A EDITED text"))) }
        assertTrue("A was submitted", server.reports.containsKey(a))
        assertEquals("the edit, not the stale copy, reached the server", "Report A EDITED text", server.reportDescriptions[a])
        assertEquals("Synced", queue.items(uid(), server.url).single { it.id == a }.state)
    }
}
