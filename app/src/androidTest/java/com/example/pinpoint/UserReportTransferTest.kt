package com.example.pinpoint

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pinpoint.data.QueuedReport
import com.example.pinpoint.data.ReportDraft
import com.example.pinpoint.data.UserReportTransfer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Emulator-only contracts; no credential, upload, radio or app-data mutation. */
@RunWith(AndroidJUnit4::class)
class UserReportTransferTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun emulatorOnly() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        assumeTrue(Build.VERSION.SDK_INT>=34)
    }

    @Test fun serviceIsPlatformProtectedAndPermissionDeclared() {
        val service=context.packageManager.getServiceInfo(
            ComponentName(context,"com.example.pinpoint.data.UserReportTransferService"),0)
        assertFalse(service.exported)
        assertEquals(android.app.job.JobService.PERMISSION_BIND,service.permission)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RUN_USER_INITIATED_JOBS))
    }

    @Test fun explicitTransferCarriesIdentityAndAllowsMeteredReportPhoto() {
        val item=QueuedReport(UUID.randomUUID().toString(),UUID.randomUUID().toString(),BuildConfig.SUPABASE_URL,
            ReportDraft("Other","Local fixture","Local fixture","Local fixture"),null,"Queued",null,0L)
        val job=UserReportTransfer.build(context,item)
        assertTrue(job.isUserInitiated)
        assertEquals(JobInfo.NETWORK_TYPE_ANY,job.networkType)
        assertEquals(item.id,job.extras.getString("report"))
        assertEquals(item.ownerId,job.extras.getString("owner"))
        assertEquals(item.project,job.extras.getString("project"))
        assertEquals(setOf("report","owner","project"),job.extras.keySet())
        assertTrue(job.estimatedNetworkUploadBytes>0)
        assertFalse(job.isPersisted) // Reboot recovery belongs to the existing durable queue/WorkManager.
        assertFalse(job.isRequireCharging)
        assertFalse(job.isRequireDeviceIdle)
    }

    @Test fun buildingRequestDoesNotScheduleAutomaticWork() {
        val scheduler=context.getSystemService(JobScheduler::class.java).forNamespace(UserReportTransfer.NAMESPACE)
        val before=scheduler.allPendingJobs.map { it.id }.toSet()
        val item=QueuedReport(UUID.randomUUID().toString(),UUID.randomUUID().toString(),BuildConfig.SUPABASE_URL,
            ReportDraft("Other","Local fixture","Local fixture","Local fixture"),null,"Queued",null,0L)
        assertEquals(UserReportTransfer.build(context,item).id,UserReportTransfer.build(context,item).id)
        assertEquals(before,scheduler.allPendingJobs.map { it.id }.toSet())
    }

    @Test fun missingCitizenReportCannotScheduleJob() {
        val scheduler=context.getSystemService(JobScheduler::class.java).forNamespace(UserReportTransfer.NAMESPACE)
        val before=scheduler.allPendingJobs.map { it.id }.toSet()
        assertFalse(UserReportTransfer.schedule(context,UUID.randomUUID().toString()))
        assertEquals(before,scheduler.allPendingJobs.map { it.id }.toSet())
    }
}
