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
                check(name==ReportSyncWorker::class.java.name) { "Unexpected worker in isolated scheduler test" }
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

    private fun row(state: String="Queued",project: String=BuildConfig.SUPABASE_URL) {
        queue.writableDatabase.insertOrThrow("queue",null,ContentValues().apply {
            put("id",UUID.randomUUID().toString());put("owner_id",owner);put("project",project)
            put("draft","""{"category":"Other","description":"Local scheduling fixture","location":"Local fixture","barangay":"Local fixture"}""")
            put("state",state);put("created_at",System.currentTimeMillis())
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
        requireNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(work.id)
        assertEquals(WorkInfo.State.SUCCEEDED,manager.getWorkInfoById(work.id).get(5,TimeUnit.SECONDS).state)
        assertEquals(1,SchedulingProbeWorker.runs.get())
    }

    @Test fun completedAndOtherProjectRowsDoNotConsumeExpeditedQuota() {
        row(state="Synced")
        row(project="https://other-project.invalid")
        val work=scheduled()
        val spec=requireNotNull(manager.workDatabase.workSpecDao().getWorkSpec(work.id.toString()))
        assertFalse(spec.expedited)
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
