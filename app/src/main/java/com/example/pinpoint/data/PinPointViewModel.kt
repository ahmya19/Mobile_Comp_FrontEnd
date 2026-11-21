package com.example.pinpoint.data

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pinpoint.BuildConfig
import java.util.UUID
import java.io.File
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PinPointState(
    val configured: Boolean = false,
    val user: Citizen? = null,
    val confirmation: PendingEmailConfirmation? = null,
    val busy: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val reports: List<Report> = emptyList(),
    val community: List<Report> = emptyList(),
    val notifications: List<ReportNotification> = emptyList(),
    val notificationsHasMore: Boolean = false,
    val notificationsLoadingMore: Boolean = false,
    val selectedReport: Report? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val photo: ImageBitmap? = null,
    val statusHistory: List<StatusChange> = emptyList(),
    val submittedReportId: String? = null,
    val submissionPending: Boolean = false,
    val hasVoted: Boolean = false,
    val mineFeed: ReportFeedState = ReportFeedState(ReportFilter(mine=true)),
    val communityFeed: ReportFeedState = ReportFeedState(ReportFilter()),
    val pendingUploads: Int = 0
)

class PinPointViewModel(application: Application) : AndroidViewModel(application) {
    private val backend = SupabaseBackend.create(application)
    private val repository = backend?.let(::PinPointRepository)
    private val mutableState = MutableStateFlow(PinPointState(configured = backend != null,
        confirmation = backend?.auth?.confirmation?.value))
    val state = mutableState.asStateFlow()
    private val listeners = mutableListOf<Job>()
    private var detailJob: Job? = null
    private var actionJob: Job? = null
    private var observedUid: String? = null
    private var draftId: String? = null
    private val deviceId: String
    private val queue = ReportQueue.get(application)
    private var remoteMine = emptyList<Report>()
    private var notificationFeed: NotificationFeed? = null
    private var notificationScope: CoroutineScope? = null
    private var queuedItems = emptyList<QueuedReport>()
    private var lastSubmissionId: String? = null
    private var mineFeed: ReportFeed? = null
    private var communityFeed: ReportFeed? = null
    private fun accountChanged(user: AuthUser?) {
        val uid = user?.id
        if (uid != observedUid) {
            observedUid = uid
            listeners.forEach { it.cancel() }
            listeners.clear()
            detailJob?.cancel()
            remoteMine = emptyList()
            notificationFeed = null
            notificationScope?.cancel(); notificationScope=null
            queuedItems = emptyList()
            mineFeed=null
            communityFeed=null
            mutableState.update { PinPointState(configured=true,busy=it.busy,
                confirmation=backend?.auth?.confirmation?.value,
                user=user?.let { cached -> Citizen(cached.id,cached.email,cached.displayName) }) }
            if (uid != null) observeAccount(uid)
        }
    }

    init {
        val preferences = application.getSharedPreferences("pinpoint_device", 0)
        deviceId = preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("id", it).apply()
        }
        backend?.let { viewModelScope.launch { it.auth.users.collect(::accountChanged) } }
        backend?.let { configured -> viewModelScope.launch {
            configured.auth.confirmation.collect { pending -> mutableState.update { it.copy(confirmation=pending) } }
        } }
    }

    private fun observeAccount(uid: String) {
        val repo = repository ?: return
        val mine=ReportFeed(repo,true).also { mineFeed=it }
        val community=ReportFeed(repo,false).also { communityFeed=it }
        mutableState.update { it.copy(loading = true) }
        listeners += viewModelScope.launch {
            requireNotNull(backend).let { configured ->
                queue.observe(uid,configured.url).collect { items ->
                    val committed=items.any { item -> item.state=="Synced" && queuedItems.any { old -> old.id==item.id && old.state!="Synced" } }
                    queuedItems = items
                    refreshMine()
                    if(committed) viewModelScope.launch { mine.refresh();community.refresh() }
                    items.firstOrNull { it.id==lastSubmissionId }?.let { item ->
                        mutableState.update { it.copy(submissionPending=item.state!="Synced") }
                    }
                }
            }
        }
        queue.schedule(uid)
        listeners += viewModelScope.launch {
            while(observedUid==uid) {
                try {
                    val citizen=repo.citizen()
                    if(observedUid==uid) mutableState.update { it.copy(user=citizen) }
                    if(citizen?.official==true && observedUid==uid) repo.pendingPushReports().forEach {
                        PushDeliveryWorker.schedule(getApplication(),uid,it)
                    }
                } catch(error: Exception) { handle(error) }
                kotlinx.coroutines.delay(30000)
            }
        }
        listeners += viewModelScope.launch {
            mine.state.collect { feed ->
                remoteMine=feed.reports
                mutableState.update { it.copy(mineFeed=feed,loading=feed.loading) }
                refreshMine()
            }
        }
        listeners += viewModelScope.launch {
            community.state.collect { feed -> mutableState.update { it.copy(communityFeed=feed,community=feed.reports) } }
        }
        listeners += viewModelScope.launch {
            while(observedUid==uid) { mine.refresh();kotlinx.coroutines.delay(15000) }
        }
        listeners += viewModelScope.launch {
            while(observedUid==uid) { community.refresh();kotlinx.coroutines.delay(15000) }
        }
        val notices=NotificationFeed({ repo.pageNotifications(it) },repo::markRead).also { notificationFeed=it }
        notificationScope?.cancel()
        val noticeScope=CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
            .also { notificationScope=it }
        noticeScope.launch {
            notices.state.collect { feed -> mutableState.update { it.copy(notifications=feed.items,
                notificationsHasMore=feed.next!=null,notificationsLoadingMore=feed.loadingMore,error=feed.error ?: it.error) } }
        }
        noticeScope.launch {
            while(observedUid==uid) { notices.refresh();kotlinx.coroutines.delay(10000) }
        }
        registerPushToken()
    }

    fun authenticate(register: Boolean, name: String, email: String, password: String) = action {
        val repo = requireNotNull(repository)
        if (register) {
            if (!repo.register(name,email,password)) {
                mutableState.update { it.copy(message="Check your inbox and spam folder for a confirmation email. If you already confirmed this account, return to sign in.") }
                return@action
            }
        } else try { repo.signIn(email,password) } catch (error: BackendException) {
            if (error.code != "email_not_confirmed") throw error
            mutableState.update { it.copy(message="Confirm your email to continue. You can request a new code below.") }
            return@action
        }
        mutableState.update { it.copy(user = repo.citizen()) }
    }

    fun verifyEmail(code: String) = action {
        val repo = requireNotNull(repository)
        repo.auth.verifyEmail(code)
        repo.saveProfile(requireNotNull(repo.auth.currentUser).displayName)
        mutableState.update { it.copy(user=repo.citizen(), message="Email confirmed.") }
    }
    fun resendConfirmation() = action {
        requireNotNull(backend).auth.resendConfirmation()
        mutableState.update { it.copy(message="Confirmation requested. Check your inbox and spam folder. Delivery depends on the email provider.") }
    }
    fun enterConfirmation(email: String) = action { requireNotNull(backend).auth.enterConfirmation(email) }
    fun cancelConfirmation() {
        if (state.value.busy) return
        backend?.auth?.cancelConfirmation()
        clearFeedback()
    }

    fun editProfile(name: String) = action {
        val repo = requireNotNull(repository)
        repo.saveProfile(name)
        mutableState.update { it.copy(user = repo.citizen(), message = "Profile saved.") }
    }

    fun signOut() = action {
        // If device removal fails, keep the account signed in so the user can retry.
        requireNotNull(repository).removeDevice(deviceId)
        backend?.auth?.currentUser?.id?.let { WorkManager.getInstance(getApplication()).cancelUniqueWork("pinpoint-reports-$it") }
        backend?.auth?.currentUser?.id?.let { WorkManager.getInstance(getApplication()).cancelUniqueWork("pinpoint-originals-$it") }
        backend?.auth?.currentUser?.id?.let { WorkManager.getInstance(getApplication()).cancelUniqueWork("pinpoint-device-$it") }
        backend?.auth?.signOut()
        draftId = null
    }

    fun startReport() {
        draftId = null
        mutableState.update { it.copy(submittedReportId = null, error = null, message = null) }
    }

    fun acknowledgeSubmission() { mutableState.update { it.copy(submittedReportId = null) } }

    fun submit(draft: ReportDraft, image: Uri?, keepOriginal: Boolean) = action {
        draft.validate()
        val repo = requireNotNull(repository)
        val id = draftId ?: repo.newReportId().also { draftId = it }
        queue.enqueue(requireNotNull(backend),id,draft,image,keepOriginal)
        draftId = null
        lastSubmissionId = id
        mutableState.update { it.copy(submittedReportId=id,submissionPending=true) }
    }

    fun openReport(id: String) {
        detailJob?.cancel()
        mutableState.update { it.copy(selectedReport = null, photo = null, statusHistory=emptyList(),detailLoading = true, detailError = null) }
        val repo = repository ?: return
        queuedItems.firstOrNull { it.id==id && it.state!="Synced" }?.let { item ->
            mutableState.update { it.copy(selectedReport=item.toReport(),detailLoading=false,
                detailError=item.error ?: "Saved on this device. Waiting for synchronization.") }
            item.photoFile?.let { path ->
                BitmapFactory.decodeFile(path)?.asImageBitmap()?.let { bitmap -> mutableState.update { it.copy(photo=bitmap) } }
            }
            return
        }
        detailJob = viewModelScope.launch {
            try { mutableState.update { it.copy(hasVoted=repo.voted(id)) } } catch(error: Exception) { handle(error) }
            var lastPhotoPath: String? = null
            repo.report(id) { error ->
                if (error is CancellationException) throw error
                mutableState.update { it.copy(detailLoading = false, detailError = errorText(error)) }
            }.collect { report ->
                mutableState.update { it.copy(selectedReport = report?.copy(originalUploadStatus=queuedItems.firstOrNull { item -> item.id==id }?.originalState), detailLoading = false,
                    detailError = if (report == null) "Report not found." else null) }
                try { val history=repo.history(id);mutableState.update { it.copy(statusHistory=history) } }
                catch(error: Exception) { handle(error) }
                val path = report?.photoPath
                if (path != null && lastPhotoPath != path) {
                    try {
                        val bytes = repo.photo(path)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        mutableState.update { it.copy(photo = bitmap?.asImageBitmap()) }
                        lastPhotoPath = path
                    } catch (error: Exception) { handle(error) }
                }
            }
        }
    }

    fun updateStatus(id: String, status: String, priority: String) = action {
        requireNotNull(repository).updateStatus(id, status, priority)
        backend?.auth?.currentUser?.id?.let { PushDeliveryWorker.schedule(getApplication(),it,id) }
        mutableState.update { it.copy(message = "Report updated.") }
    }

    fun loadMoreNotifications() {
        val notices=notificationFeed ?: return
        if(notices.state.value.loadingMore) return
        notificationScope?.launch { notices.loadMore() }
    }
    fun markRead(id: String) {
        val notices=notificationFeed ?: return
        notificationScope?.launch { notices.markRead(id) }
    }
    fun vote(id: String) = action {
        val repo=requireNotNull(repository)
        repo.vote(id,!state.value.hasVoted)
        mutableState.update { it.copy(hasVoted=!it.hasVoted) }
        openReport(id)
    }
    fun retryUploads() {
        val configured=backend ?: return
        val uid=configured.auth.currentUser?.id ?: return
        queue.retry(uid,configured.url)
    }
    fun showOriginal(path: String) = action {
        val bytes=WifiOriginals(getApplication(),requireNotNull(backend)).download(path)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "Could not decode the original photo." }
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048) sample*=2
        val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
            ?: error("Could not display the original photo.")
        mutableState.update { it.copy(photo=decoded.asImageBitmap(),message="Showing the original photo (downloaded over Wi-Fi).") }
    }
    fun filterReports(mine: Boolean,filter: ReportFilter) {
        viewModelScope.launch { (if(mine) mineFeed else communityFeed)?.refresh(filter.copy(mine=mine)) }
    }
    fun loadMoreReports(mine: Boolean) {
        viewModelScope.launch { (if(mine) mineFeed else communityFeed)?.loadMore() }
    }
    private fun refreshMine() {
        val known=remoteMine.map { it.id }.toSet()
        val filter=state.value.mineFeed.filter
        val local=queuedItems.filter { it.id !in known && it.state!="Synced" }.map { it.toReport() }.filter {
            (filter.barangay.isNullOrBlank() || it.barangay==filter.barangay) &&
            (filter.category.isNullOrBlank() || it.category==filter.category) &&
            (filter.status.isNullOrBlank() || it.status==filter.status) &&
            (filter.after==null || it.createdAt>=filter.after) && (filter.before==null || it.createdAt<=filter.before)
        }
        val enriched=remoteMine.map { report -> report.copy(originalUploadStatus=queuedItems.firstOrNull { it.id==report.id }?.originalState) }
        mutableState.update { it.copy(reports=local+enriched,pendingUploads=queuedItems.count { item -> item.state!="Synced" || item.originalState !in listOf(null,"Uploaded") }) }
    }
    private fun QueuedReport.toReport() = Report(id=id,ownerId=ownerId,category=draft.category,description=draft.description,
        location=draft.location,barangay=draft.barangay,status=when(state) { "Synced"->"Pending";"Failed"->"Needs attention";"Sending"->"Sending";else->"Waiting to sync" },
        createdAt=java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",java.util.Locale.US).apply {
            timeZone=java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(createdAt)),latitude=draft.latitude,
        longitude=draft.longitude,pendingSync=state!="Synced")
    fun clearFeedback() { mutableState.update { it.copy(error = null, message = null) } }

    fun retry() {
        val uid = backend?.auth?.currentUser?.id ?: return
        listeners.forEach { it.cancel() }
        listeners.clear()
        clearFeedback()
        observeAccount(uid)
    }

    fun registerPushToken() {
        if (!BuildConfig.FCM_CONFIGURED) return
        val owner=backend?.auth?.currentUser?.id ?: return
        DeviceRegistrationWorker.schedule(getApplication(),owner)
    }

    private fun action(block: suspend () -> Unit) {
        if (actionJob?.isActive == true) return
        actionJob = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null, message = null) }
            try { block() } catch (error: Exception) { handle(error) }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private fun handle(error: Throwable) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(error = errorText(error)) }
    }

    private fun errorText(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message ?: "Request failed."}"

}
