package com.example.pinpoint.data

import android.content.Context
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Opening offline must not lose device registration when connectivity returns. */
class DeviceRegistrationWorker(context: Context,parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
        val backend=SupabaseBackend.create(applicationContext) ?: return Result.success()
        val owner=inputData.getString("owner") ?: return Result.failure()
        if(backend.auth.currentUser?.id!=owner) return Result.success()
        val installation=applicationContext.getSharedPreferences("pinpoint_device",0).getString("id",null) ?: return Result.failure()
        return try {
            val token=FirebaseMessaging.getInstance().token.await()
            PinPointRepository(backend).registerDevice(installation,token,owner)
            Result.success()
        } catch(error: Exception) {
            if(error is CancellationException) throw error
            if(backend.auth.currentUser?.id!=owner) Result.success()
            else if(error is BackendException && error.status in listOf(400,401,403,404)) Result.failure()
            else Result.retry()
        }
    }
    companion object {
        fun schedule(context: Context,owner: String) {
            val request=OneTimeWorkRequestBuilder<DeviceRegistrationWorker>().setInputData(workDataOf("owner" to owner))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("pinpoint-device-$owner",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
        }
    }
}
