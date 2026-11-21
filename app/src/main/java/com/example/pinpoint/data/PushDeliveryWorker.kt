package com.example.pinpoint.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException

/** Retries the server outbox without putting server credentials in the APK. */
class PushDeliveryWorker(context: Context,parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
        val backend=SupabaseBackend.create(applicationContext) ?: return Result.success()
        val owner=inputData.getString("owner") ?: return Result.failure()
        val report=inputData.getString("report") ?: return Result.failure()
        if(backend.auth.currentUser?.id!=owner) return Result.success()
        return try {
            PinPointRepository(backend).deliverPush(report,owner)
            Result.success()
        } catch(error: Exception) {
            if(error is CancellationException) throw error
            if(backend.auth.currentUser?.id!=owner) Result.success()
            else if(error is BackendException && error.status in listOf(400,401,403,404)) Result.failure()
            else Result.retry()
        }
    }
    companion object {
        fun schedule(context: Context,owner: String,report: String) {
            val request=OneTimeWorkRequestBuilder<PushDeliveryWorker>()
                .setInputData(workDataOf("owner" to owner,"report" to report))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("pinpoint-push-$owner-$report",ExistingWorkPolicy.KEEP,request)
        }
    }
}
