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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class QueuedReport(val id: String,val ownerId: String,val project: String,val draft: ReportDraft,
    val photoFile: String?,val state: String,val error: String?,val createdAt: Long,
    val originalFile: String?=null,val originalMime: String?=null,val originalState: String?=null,val uploadUrl: String?=null)

/** Account- and project-isolated unsent form draft (distinct from the upload queue). */
data class SavedFormDraft(
    val ownerId: String,
    val project: String,
    val category: String,
    val description: String,
    val location: String,
    val barangay: String,
    val latitude: String,
    val longitude: String,
    val keepOriginal: Boolean,
    val photoFile: String?,
    val updatedAt: Long
)

/** Durable metadata and private photo copies survive worker/process restarts. */
class ReportQueue private constructor(private val context: Context) : SQLiteOpenHelper(context,"pinpoint_queue.db",null,3) {
    private val revision=MutableStateFlow(0L)
    private val draftRevision=MutableStateFlow(0L)
    private val gson=Gson()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            create table queue(id text primary key,owner_id text not null,project text not null,draft text not null,
            photo_file text,state text not null,error text,created_at integer not null,
            original_file text,original_mime text,original_state text,upload_url text)
        """.trimIndent())
        createDraftsTable(db)
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
        if(oldVersion<2) listOf("original_file","original_mime","original_state","upload_url").forEach {
            db.execSQL("alter table queue add column $it text")
        }
        if(oldVersion<3) createDraftsTable(db)
    }
    private fun createDraftsTable(db: SQLiteDatabase) {
        db.execSQL("""
            create table if not exists drafts(
                owner_id text not null,project text not null,category text not null,description text not null,
                location text not null,barangay text not null,latitude text not null,longitude text not null,
                keep_original integer not null default 1,photo_file text,updated_at integer not null,
                primary key(owner_id,project)
            )
        """.trimIndent())
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
        clearDraft(owner,backend.url)
        CameraCaptureHelper.deleteCaptureUri(context,image)
        revision.value++
        schedule(owner)
        id
    }

    /** Policy-safe local removal for unsent offline queue rows ONLY. Refuses Synced or Sending rows. */
    @Synchronized fun deleteUnsent(owner: String,project: String,id: String): Boolean {
        val target=items(owner,project).firstOrNull { it.id==id } ?: return false
        require(target.state in listOf("Queued","Failed")) {
            "Submitted or actively syncing reports cannot be deleted."
        }
        val removed=writableDatabase.delete("queue","id=? and owner_id=? and project=? and state in ('Queued','Failed')",
            arrayOf(id,owner,project))
        if(removed>0) {
            target.photoFile?.let { File(it).takeIf { f -> f.isFile }?.delete() }
            target.originalFile?.let { File(it).takeIf { f -> f.isFile }?.delete() }
            revision.value++
            return true
        }
        return false
    }

    /** Policy-safe local edit for unsent offline queue rows ONLY. Refuses Synced or Sending rows. */
    @Synchronized fun updateUnsent(owner: String,project: String,id: String,updated: ReportDraft): Boolean {
        updated.validate()
        val target=items(owner,project).firstOrNull { it.id==id } ?: return false
        require(target.state in listOf("Queued","Failed")) {
            "Submitted or actively syncing reports cannot be edited."
        }
        val changed=writableDatabase.update("queue",ContentValues().apply {
            put("draft",gson.toJson(updated))
            if(target.state=="Failed") { put("state","Queued"); putNull("error") }
        },"id=? and owner_id=? and project=? and state in ('Queued','Failed')",arrayOf(id,owner,project))
        if(changed>0) { revision.value++; return true }
        return false
    }

    /** Atomically moves ONE row to Sending and returns its CURRENT stored content, or null if it was deleted, already
     *  synced or no longer belongs to this account/project. Serialized with deleteUnsent/updateUnsent, so the worker can
     *  never submit a report the user just removed or an outdated copy of one the user just edited. */
    @Synchronized fun claimForSend(owner: String,project: String,id: String): QueuedReport? {
        val changed=writableDatabase.update("queue",ContentValues().apply { put("state","Sending") },
            "id=? and owner_id=? and project=? and state in ('Queued','Sending')",arrayOf(id,owner,project))
        if(changed==0) return null
        revision.value++
        return items(owner,project).firstOrNull { it.id==id }
    }

    /** Save an account- and project-isolated form draft (never leaks across accounts or projects). */
    suspend fun saveDraft(owner: String,project: String,category: String,description: String,
        location: String,barangay: String,latitude: String,longitude: String,
        keepOriginal: Boolean,image: Uri?): SavedFormDraft = withContext(Dispatchers.IO) {
        val previous=loadDraft(owner,project)
        val photoFile: String? = when {
            image==null -> { previous?.photoFile?.let { File(it).takeIf { f -> f.isFile }?.delete() }; null }
            previous?.photoFile!=null && Uri.fromFile(File(previous.photoFile))==image -> previous.photoFile
            else -> {
                val directory=File(context.filesDir,"draft_photos").apply { check(exists() || mkdirs()) }
                val jpeg=PhotoCompressor.jpeg(context,image)
                val key=UUID.nameUUIDFromBytes("$owner|$project".toByteArray()).toString()
                val target=File(directory,"$key.jpg")
                val tmp=File(directory,"$key.tmp")
                try {
                    tmp.outputStream().use { it.write(jpeg) }
                    check(tmp.renameTo(target)) { "Could not save the draft photo." }
                } finally { tmp.delete() }
                if(previous?.photoFile!=null && previous.photoFile!=target.absolutePath) {
                    File(previous.photoFile).takeIf { it.isFile }?.delete()
                }
                target.absolutePath
            }
        }
        val now=System.currentTimeMillis()
        synchronized(this@ReportQueue) {
            writableDatabase.insertWithOnConflict("drafts",null,ContentValues().apply {
                put("owner_id",owner);put("project",project);put("category",category);put("description",description)
                put("location",location);put("barangay",barangay);put("latitude",latitude);put("longitude",longitude)
                put("keep_original",if(keepOriginal) 1 else 0);put("photo_file",photoFile);put("updated_at",now)
            },SQLiteDatabase.CONFLICT_REPLACE)
            draftRevision.value++
        }
        SavedFormDraft(owner,project,category,description,location,barangay,latitude,longitude,keepOriginal,photoFile,now)
    }

    @Synchronized fun loadDraft(owner: String,project: String): SavedFormDraft? {
        readableDatabase.query("drafts",null,"owner_id=? and project=?",arrayOf(owner,project),null,null,null,"1").use { rows ->
            if(!rows.moveToNext()) return null
            fun text(name: String)=rows.getString(rows.getColumnIndexOrThrow(name))
            val file=text("photo_file")?.takeIf { File(it).isFile }
            return SavedFormDraft(
                ownerId=text("owner_id"),project=text("project"),category=text("category"),
                description=text("description"),location=text("location"),barangay=text("barangay"),
                latitude=text("latitude"),longitude=text("longitude"),
                keepOriginal=rows.getInt(rows.getColumnIndexOrThrow("keep_original"))!=0,
                photoFile=file,updatedAt=rows.getLong(rows.getColumnIndexOrThrow("updated_at"))
            )
        }
    }

    @Synchronized fun clearDraft(owner: String,project: String) {
        val existing=loadDraft(owner,project)
        writableDatabase.delete("drafts","owner_id=? and project=?",arrayOf(owner,project))
        existing?.photoFile?.let { File(it).takeIf { f -> f.isFile }?.delete() }
        draftRevision.value++
    }

    fun observeDraft(owner: String,project: String)=draftRevision.map { loadDraft(owner,project) }

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
    /** A kill between "Synced" and deleting the compressed copy would otherwise leave a private photo on disk forever. */
    fun purgeSyncedPhotos(owner: String,project: String) {
        items(owner,project).filter { it.state=="Synced" }.forEach { item ->
            item.photoFile?.let { path -> File(path).takeIf { it.isFile }?.delete() }
            // The same window exists for the optional original: once its attach was acknowledged ("Uploaded") the local copy
            // must go; a kill between the acknowledgement and the delete would otherwise leave a private original forever.
            if(item.originalState=="Uploaded") item.originalFile?.let { path -> File(path).takeIf { it.isFile }?.delete() }
        }
    }
    fun schedule(owner: String) {
        val project=SupabaseBackend.create(context)?.url ?: return
        purgeSyncedPhotos(owner,project)
        CameraCaptureHelper.purgeStaleCaptures(context)
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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,WorkRequest.MIN_BACKOFF_MILLIS,TimeUnit.MILLISECONDS)
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
        val workerStartedAt=System.currentTimeMillis()
        val backend=SupabaseBackend.create(applicationContext) ?: return@withContext Result.success()
        val owner=inputData.getString("owner") ?: return@withContext Result.failure()
        if (backend.auth.currentUser?.id!=owner) return@withContext Result.success()
        val queue=ReportQueue.get(applicationContext)
        for(listed in queue.items(owner,backend.url).filter { it.state in listOf("Queued","Sending") }) {
            if(backend.auth.currentUser?.id!=owner) return@withContext Result.success()
            val submitStartedAt=System.currentTimeMillis()
            val prior=recentTimings[listed.id]
            val attemptNumber=(prior?.attempts ?: 0)+1
            try {
                // The list above is a snapshot: re-claim the row and use its CURRENT content (it may have been deleted or edited).
                val item=queue.claimForSend(owner,backend.url,listed.id) ?: continue
                val photo=item.photoFile?.let { path ->
                    val file=File(path)
                    check(file.isFile) { "Saved photo is missing. This report needs attention." }
                    file.readBytes()
                }
                PinPointRepository(backend).submit(item.id,item.draft,photo,owner)
                val syncedAt=System.currentTimeMillis()
                recordTiming(item.id,SyncTiming(
                    firstWorkerStartMs=prior?.firstWorkerStartMs ?: workerStartedAt,
                    lastSubmitStartMs=submitStartedAt,
                    syncedMs=syncedAt,
                    attempts=attemptNumber,
                    lastErrorKind=prior?.lastErrorKind
                ))
                queue.setState(item.id,"Synced")
                item.photoFile?.let { File(it).delete() }
                if(item.originalFile!=null) queue.scheduleOriginals(owner)
            } catch(error: Exception) {
                recordTiming(listed.id,SyncTiming(
                    firstWorkerStartMs=prior?.firstWorkerStartMs ?: workerStartedAt,
                    lastSubmitStartMs=submitStartedAt,
                    syncedMs=null,
                    attempts=attemptNumber,
                    lastErrorKind=error.javaClass.simpleName
                ))
                if(error is CancellationException) { queue.setState(listed.id,"Queued"); throw error }
                if(backend.auth.currentUser?.id!=owner) { queue.setState(listed.id,"Queued");return@withContext Result.success() }
                val retryable=error is java.io.IOException && (error !is BackendException || error.status>=500 || error.status in listOf(408,429))
                queue.setState(listed.id,if(retryable) "Queued" else "Failed","${error.javaClass.simpleName}: ${error.message}")
                if(retryable) return@withContext Result.retry()
            }
        }
        queue.scheduleOriginals(owner)
        Result.success()
    }

    /** In-process non-sensitive timing markers for diagnosing submission latency (BG-024). */
    data class SyncTiming(
        val firstWorkerStartMs: Long,
        val lastSubmitStartMs: Long,
        val syncedMs: Long?,
        val attempts: Int,
        val lastErrorKind: String?
    )

    companion object {
        val recentTimings: ConcurrentHashMap<String,SyncTiming> = ConcurrentHashMap()
        private fun recordTiming(reportId: String,timing: SyncTiming) {
            if(recentTimings.size>=64 && !recentTimings.containsKey(reportId)) {
                recentTimings.entries.minByOrNull { it.value.firstWorkerStartMs }?.key?.let { recentTimings.remove(it) }
            }
            recentTimings[reportId]=timing
        }
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
                WifiOriginals.create(applicationContext,backend).upload(item) { url -> queue.saveUploadSession(item.id,url) }
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
