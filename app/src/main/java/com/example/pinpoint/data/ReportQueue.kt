package com.example.pinpoint.data

import android.content.ContentValues
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class QueuedReport(val id: String,val ownerId: String,val project: String,val draft: ReportDraft,
    val photoFile: String?,val state: String,val error: String?,val createdAt: Long,
    val originalFile: String?=null,val originalMime: String?=null,val originalState: String?=null,val uploadUrl: String?=null)

/** Durable metadata and private photo copies survive worker/process restarts. */
class ReportQueue private constructor(private val context: Context) : SQLiteOpenHelper(context,"pinpoint_queue.db",null,2) {
    private val revision=MutableStateFlow(0L)
    private val gson=Gson()
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("""
        create table queue(id text primary key,owner_id text not null,project text not null,draft text not null,
        photo_file text,state text not null,error text,created_at integer not null,
        original_file text,original_mime text,original_state text,upload_url text)
    """.trimIndent()) }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
        if(oldVersion<2) listOf("original_file","original_mime","original_state","upload_url").forEach {
            db.execSQL("alter table queue add column $it text")
        }
    }

    suspend fun enqueue(backend: SupabaseBackend,id: String,draft: ReportDraft,image: Uri?,keepOriginal: Boolean=false): String = withContext(Dispatchers.IO) {
        draft.validate()
        val owner=backend.auth.currentUser?.id ?: error("Sign in before saving a report.")
        UUID.fromString(id)
        val existing=items(owner,backend.url).firstOrNull { it.id==id }
        if (existing!=null) {
            require(existing.draft==draft) { "A queued report cannot change after saving. Start a new report instead." }
            schedule(owner)
            return@withContext id
        }
        val file=image?.let {
            val directory=File(context.filesDir,"queued_photos").apply { check(exists() || mkdirs()) }
            // Compress before touching the disk: a corrupt or vanished source must not leave a partial temp file.
            val jpeg=PhotoCompressor.jpeg(context,it)
            File(directory,"$id.jpg").also { target ->
                val temporary=File(directory,"$id.tmp")
                try {
                    temporary.outputStream().use { output -> output.write(jpeg) }
                    check(temporary.renameTo(target)) { "Could not save the selected photo." }
                } finally { temporary.delete() }
            }.absolutePath
        }
        var original: File?=null
        var originalMime: String?=null
        try {
            if(image!=null && keepOriginal) {
                originalMime=context.contentResolver.getType(image) ?: if(image.toString().endsWith(".jpg")) "image/jpeg" else null
                require(originalMime in listOf("image/jpeg","image/png","image/webp")) { "Original uploads support JPEG, PNG and WebP. Turn off the original copy for other formats." }
                val directory=File(context.filesDir,"queued_photos")
                original=File(directory,"$id.original")
                val temporary=File(directory,"$id.original.tmp")
                try {
                    context.contentResolver.openInputStream(image).use { input ->
                        requireNotNull(input) { "Could not open the original photo." }
                        temporary.outputStream().use { output ->
                            val buffer=ByteArray(32768);var total=0L
                            while(true) {
                                val count=input.read(buffer);if(count<0) break
                                total+=count
                                require(total<=10485760) { "The original exceeds 10 MB. Turn off the original copy or choose a smaller photo." }
                                output.write(buffer,0,count)
                            }
                            require(total>0) { "The original photo is empty." }
                        }
                    }
                    check(temporary.renameTo(original)) { "Could not save the original photo." }
                } finally { temporary.delete() }
            }
            writableDatabase.insertOrThrow("queue",null,ContentValues().apply {
                put("id",id);put("owner_id",owner);put("project",backend.url);put("draft",gson.toJson(draft))
                put("photo_file",file);put("state","Queued");put("created_at",System.currentTimeMillis())
                put("original_file",original?.absolutePath);put("original_mime",originalMime)
                put("original_state",if(original!=null) "Waiting for Wi-Fi" else null)
            })
        } catch(error: Exception) { file?.let { File(it).delete() };original?.delete();throw error }
        revision.value++
        schedule(owner)
        id
    }
    fun observe(owner: String,project: String)=revision.map { items(owner,project) }
    @Synchronized fun items(owner: String,project: String): List<QueuedReport> {
        val result=mutableListOf<QueuedReport>()
        readableDatabase.query("queue",null,"owner_id=? and project=?",arrayOf(owner,project),null,null,"created_at desc").use { rows ->
            while(rows.moveToNext()) {
                fun text(name: String)=rows.getString(rows.getColumnIndexOrThrow(name))
                result+=QueuedReport(text("id"),text("owner_id"),text("project"),gson.fromJson(text("draft"),ReportDraft::class.java),
                    text("photo_file"),text("state"),text("error"),rows.getLong(rows.getColumnIndexOrThrow("created_at")),
                    text("original_file"),text("original_mime"),text("original_state"),text("upload_url"))
            }
        }
        return result
    }
    @Synchronized fun setState(id: String,state: String,error: String?=null) {
        writableDatabase.update("queue",ContentValues().apply { put("state",state);put("error",error) },"id=?",arrayOf(id))
        revision.value++
    }
    fun retry(owner: String,project: String) {
        items(owner,project).filter { it.state=="Failed" }.forEach { setState(it.id,"Queued") }
        items(owner,project).filter { it.originalState=="Failed" }.forEach { setOriginalState(it.id,"Waiting for Wi-Fi") }
        schedule(owner)
    }
    fun schedule(owner: String) {
        val project=SupabaseBackend.create(context)?.url ?: return
        val pending=items(owner,project)
        if(pending.none { it.state in listOf("Queued","Sending") }) {
            // An empty ordinary drain would become a predecessor of the next urgent report.
            if(pending.any { it.state=="Synced" && it.originalFile!=null && it.originalState !in listOf("Uploaded","Failed") }) {
                scheduleOriginals(owner)
            }
            return
        }
        val builder=OneTimeWorkRequestBuilder<ReportSyncWorker>().setInputData(workDataOf("owner" to owner))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        // A short report/photo send is user-initiated. Preserve durable work when quota is used.
        builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        val request=builder.build()
        // Appending avoids losing a newly queued item as an existing drain finishes.
        WorkManager.getInstance(context).enqueueUniqueWork("pinpoint-reports-$owner",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
    @Synchronized fun setOriginalState(id: String,state: String) {
        writableDatabase.update("queue",ContentValues().apply {put("original_state",state)},"id=?",arrayOf(id))
        revision.value++
    }
    @Synchronized fun saveUploadSession(id: String,url: String?) {
        writableDatabase.update("queue",ContentValues().apply {put("upload_url",url)},"id=?",arrayOf(id))
        revision.value++
    }
    fun scheduleOriginals(owner: String) {
        val request=OneTimeWorkRequestBuilder<OriginalUploadWorker>().setInputData(workDataOf("owner" to owner))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("pinpoint-originals-$owner",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
    companion object {
        @Volatile private var instance: ReportQueue?=null
        fun get(context: Context)=instance ?: synchronized(this) { instance ?: ReportQueue(context.applicationContext).also { instance=it } }
    }
}

class ReportSyncWorker(context: Context,parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    // WorkManager uses a foreground service for expedited requests on Android 11 and earlier.
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel="pinpoint-report-uploads"
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel,"Report uploads",NotificationManager.IMPORTANCE_LOW))
        }
        val notification=NotificationCompat.Builder(applicationContext,channel)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sending saved reports")
            .setContentText("Your report is being sent when a connection is available.")
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        return ForegroundInfo(0x10000000 or (id.hashCode() and 0x0fffffff),notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val backend=SupabaseBackend.create(applicationContext) ?: return@withContext Result.success()
        val owner=inputData.getString("owner") ?: return@withContext Result.failure()
        if (backend.auth.currentUser?.id!=owner) return@withContext Result.success()
        val queue=ReportQueue.get(applicationContext)
        for(item in queue.items(owner,backend.url).filter { it.state in listOf("Queued","Sending") }) {
            if(backend.auth.currentUser?.id!=owner) return@withContext Result.success()
            try {
                queue.setState(item.id,"Sending")
                val photo=item.photoFile?.let { path ->
                    val file=File(path)
                    check(file.isFile) { "Saved photo is missing. This report needs attention." }
                    file.readBytes()
                }
                PinPointRepository(backend).submit(item.id,item.draft,photo,owner)
                queue.setState(item.id,"Synced")
                item.photoFile?.let { File(it).delete() }
                if(item.originalFile!=null) queue.scheduleOriginals(owner)
            } catch(error: Exception) {
                if(error is CancellationException) { queue.setState(item.id,"Queued"); throw error }
                if(backend.auth.currentUser?.id!=owner) { queue.setState(item.id,"Queued");return@withContext Result.success() }
                val retryable=error is java.io.IOException && (error !is BackendException || error.status>=500 || error.status in listOf(408,429))
                queue.setState(item.id,if(retryable) "Queued" else "Failed","${error.javaClass.simpleName}: ${error.message}")
                if(retryable) return@withContext Result.retry()
            }
        }
        queue.scheduleOriginals(owner)
        Result.success()
    }
}

class OriginalUploadWorker(context: Context,parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val backend=SupabaseBackend.create(applicationContext) ?: return@withContext Result.success()
        val owner=inputData.getString("owner") ?: return@withContext Result.failure()
        if(backend.auth.currentUser?.id!=owner) return@withContext Result.success()
        val queue=ReportQueue.get(applicationContext)
        for(item in queue.items(owner,backend.url).filter { it.state=="Synced" && it.originalFile!=null && it.originalState !in listOf("Uploaded","Failed") }) {
            try {
                queue.setOriginalState(item.id,"Uploading on Wi-Fi")
                WifiOriginals(applicationContext,backend).upload(item) { url -> queue.saveUploadSession(item.id,url) }
                backend.json("rest/v1/rpc/attach_original","POST",mapOf("p_report_id" to item.id),expectedUserId=owner)
                queue.setOriginalState(item.id,"Uploaded")
                queue.saveUploadSession(item.id,null)
                File(requireNotNull(item.originalFile)).delete()
            } catch(error: Exception) {
                val retryable=error is CancellationException || error is java.io.IOException &&
                    (error !is BackendException || error.status>=500 || error.status in listOf(408,409,429))
                queue.setOriginalState(item.id,if(retryable) "Waiting for Wi-Fi" else "Failed")
                if(error is CancellationException) throw error
                if(backend.auth.currentUser?.id!=owner) return@withContext Result.success()
                if(retryable) return@withContext Result.retry()
            }
        }
        Result.success()
    }
}
