package com.example.pinpoint.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.pinpoint.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Only an explicit Submit uses this path. Restoration/retries remain durable WorkManager work. */
object UserReportTransfer {
    const val NAMESPACE = "pinpoint-user-reports"

    fun schedule(context: Context, reportId: String): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val backend = SupabaseBackend.create(context) ?: return false
        val owner = backend.auth.currentUser?.id ?: return false
        val item = ReportQueue.get(context).items(owner, backend.url)
            .singleOrNull { it.id == reportId && it.state in listOf("Queued", "Sending") } ?: return false
        return schedule(context, item)
    }

    @RequiresApi(34)
    internal fun build(context: Context, item: QueuedReport): JobInfo = JobInfo.Builder(
        item.id.hashCode() and Int.MAX_VALUE,
        ComponentName(context, UserReportTransferService::class.java)
    ).setUserInitiated(true)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setEstimatedNetworkBytes(16_384L, 65_536L + (item.photoFile?.let { java.io.File(it).length() } ?: 0L))
        .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        .setExtras(PersistableBundle().apply {
            putString("report", item.id)
            putString("owner", item.ownerId)
            putString("project", item.project)
        }).build()

    @RequiresApi(34)
    internal fun schedule(context: Context, item: QueuedReport): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java).forNamespace(NAMESPACE)
        val request = build(context, item)
        // A rare UUID hash collision must not cancel another citizen-requested transfer.
        val existing = scheduler.getPendingJob(request.id)
        if (existing != null) return existing.extras.getString("report") == item.id &&
            existing.extras.getString("owner") == item.ownerId && existing.extras.getString("project") == item.project
        return try {
            scheduler.schedule(request) == JobScheduler.RESULT_SUCCESS
        } catch (_: IllegalStateException) {
            false // Platform limits: the queue already has durable WorkManager recovery.
        } catch (_: SecurityException) {
            false // App no longer visible/eligible; never bypass platform restrictions.
        }
    }

    fun cancel(context: Context, owner: String) {
        if (Build.VERSION.SDK_INT < 34) return
        val scheduler = context.getSystemService(JobScheduler::class.java).forNamespace(NAMESPACE)
        scheduler.allPendingJobs.filter { it.extras.getString("owner") == owner }
            .forEach { scheduler.cancel(it.id) }
    }
}

@RequiresApi(34)
// Native transfers use their own API-34 namespace, isolated from WorkManager's job IDs.
@android.annotation.SuppressLint("SpecifyJobSchedulerIdRange")
class UserReportTransferService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val owner = params.extras.getString("owner") ?: return false
        val report = params.extras.getString("report") ?: return false
        val project = params.extras.getString("project") ?: return false
        if (!params.isUserInitiatedJob) return false
        val channel = "pinpoint-report-uploads"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Report uploads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, params.jobId,
            Intent(this, MainActivity::class.java).putExtra("reportId", report),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sending saved report")
            .setContentText("Your report and photo are being sent.")
            .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        setNotification(params, params.jobId, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
        val task = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var retry = false
            try {
                retry = ReportDrain.run(applicationContext, owner, project, report)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                retry = true
            } finally {
                if (running[params.jobId] === coroutineContext[Job]) {
                    running.remove(params.jobId)
                    jobFinished(params, retry)
                }
            }
        }
        running[params.jobId] = task
        task.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return params.stopReason != JobParameters.STOP_REASON_USER
    }

    override fun onDestroy() {
        running.clear()
        scope.cancel()
        super.onDestroy()
    }
}
