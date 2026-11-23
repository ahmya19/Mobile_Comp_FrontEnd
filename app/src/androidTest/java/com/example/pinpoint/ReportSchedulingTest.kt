package com.example.pinpoint

import android.content.ContentValues
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.pinpoint.data.ReportQueue
import com.example.pinpoint.data.ReportSyncWorker
import com.example.pinpoint.data.OriginalUploadWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Local WorkManager scheduler tests. Upload execution is a probe, never a live API call. */
@RunWith(AndroidJUnit4::class)
class ReportSchedulingTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val owner=UUID.randomUUID().toString()
    private lateinit var queue: ReportQueue
    private lateinit var manager: WorkManagerImpl

    @Before fun setup() {
        SchedulingProbeWorker.runs.set(0)
        val factory=object: WorkerFactory() {
            override fun createWorker(appContext: Context,name: String,parameters: WorkerParameters): ListenableWorker {
                check(name in listOf(ReportSyncWorker::class.java.name,OriginalUploadWorker::class.java.name)) { "Unexpected worker in isolated scheduler test" }
                return SchedulingProbeWorker(appContext,parameters)
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        manager=WorkManager.getInstance(context) as WorkManagerImpl
        // The isolated in-memory WorkManager does not create the app database directory.
        context.getDatabasePath("pinpoint_queue.db").parentFile?.mkdirs()
        queue=ReportQueue.get(context)
    }

    @After fun cleanup() {
        manager.cancelAllWork().result.get(5,TimeUnit.SECONDS)
        queue.writableDatabase.delete("queue","owner_id=?",arrayOf(owner))
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun row(state: String="Queued",project: String=BuildConfig.SUPABASE_URL,original: Boolean=false) {
        queue.writableDatabase.insertOrThrow("queue",null,ContentValues().apply {
            put("id",UUID.randomUUID().toString());put("owner_id",owner);put("project",project)
            put("draft","""{"category":"Other","description":"Local scheduling fixture","location":"Local fixture","barangay":"Local fixture"}""")
            put("state",state);put("created_at",System.currentTimeMillis())
            if(original) {put("original_file","local-scheduler-fixture.original");put("original_state","Waiting for Wi-Fi")}
        })
    }
    private fun scheduled(): WorkInfo {
        queue.schedule(owner)
        return manager.getWorkInfosForUniqueWork("pinpoint-reports-$owner").get(5,TimeUnit.SECONDS).single()
    }

    @Test fun pendingReportWaitsOfflineThenRunsWithExpeditedPriority() {
        row()
        val work=scheduled()
        assertEquals(WorkInfo.State.ENQUEUED,work.state)
        assertEquals(0,SchedulingProbeWorker.runs.get())
        val spec=requireNotNull(manager.workDatabase.workSpecDao().getWorkSpec(work.id.toString()))
        assertTrue("User-submitted reports need expedited priority",spec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,spec.outOfQuotaPolicy)
        assertEquals(NetworkType.CONNECTED,spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL,spec.backoffPolicy)
        assertEquals(WorkRequest.MIN_BACKOFF_MILLIS,spec.backoffDelayDuration)
        requireNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(work.id)
        assertEquals(WorkInfo.State.SUCCEEDED,manager.getWorkInfoById(work.id).get(5,TimeUnit.SECONDS).state)
        assertEquals(1,SchedulingProbeWorker.runs.get())
    }

    @Test fun completedAndOtherProjectRowsDoNotCreateReportWork() {
        row(state="Synced")
        row(project="https://other-project.invalid")
        queue.schedule(owner)
        assertTrue(manager.getWorkInfosForUniqueWork("pinpoint-reports-$owner").get(5,TimeUnit.SECONDS).isEmpty())
        assertEquals(0,SchedulingProbeWorker.runs.get())
    }

    @Test fun offlineRestorationCannotPutAnEmptyOrdinaryJobAheadOfTheNextReport() {
        // Actual call sequence: signed-in account restoration, then an offline form submission.
        queue.schedule(owner)
        row()
        queue.schedule(owner)
        val work=manager.getWorkInfosForUniqueWork("pinpoint-reports-$owner").get(5,TimeUnit.SECONDS)
        assertEquals("Restoration must not add an empty predecessor",1,work.size)
        assertEquals(WorkInfo.State.ENQUEUED,work.single().state)
        assertTrue(requireNotNull(manager.workDatabase.workSpecDao().getWorkSpec(work.single().id.toString())).expedited)
        requireNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(work.single().id)
        assertEquals(1,SchedulingProbeWorker.runs.get())
    }

    @Test fun waitingOriginalIsScheduledWithoutAnEmptyReportPredecessor() {
        row(state="Synced",original=true)
        queue.schedule(owner)
        assertTrue(manager.getWorkInfosForUniqueWork("pinpoint-reports-$owner").get(5,TimeUnit.SECONDS).isEmpty())
        val original=manager.getWorkInfosForUniqueWork("pinpoint-originals-$owner").get(5,TimeUnit.SECONDS).single()
        val spec=requireNotNull(manager.workDatabase.workSpecDao().getWorkSpec(original.id.toString()))
        assertFalse(spec.expedited)
        assertEquals(NetworkType.UNMETERED,spec.constraints.requiredNetworkType)
        assertEquals(0,SchedulingProbeWorker.runs.get())
    }

    @Test fun realWorkerProvidesTheLegacyForegroundNotificationWithoutStartingAnUpload()=runBlocking {
        val worker=TestListenableWorkerBuilder<ReportSyncWorker>(context).build()
        val info=worker.getForegroundInfo()
        assertNotEquals(0,info.notificationId)
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) assertEquals("pinpoint-report-uploads",info.notification.channelId)
        assertEquals("Sending saved reports",info.notification.extras.getCharSequence("android.title").toString())
        assertFalse(info.notification.extras.toString().contains(owner))
        assertEquals(0,SchedulingProbeWorker.runs.get())
    }
}

class SchedulingProbeWorker(context: Context,parameters: WorkerParameters): Worker(context,parameters) {
    override fun doWork(): Result { runs.incrementAndGet();return Result.success() }
    companion object { val runs=AtomicInteger(0) }
}
