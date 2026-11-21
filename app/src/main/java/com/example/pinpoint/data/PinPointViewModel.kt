package com.example.pinpoint.data

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.example.pinpoint.BuildConfig
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class PinPointState(
    val configured: Boolean = false,
    val user: Citizen? = null,
    val busy: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val reports: List<Report> = emptyList(),
    val community: List<Report> = emptyList(),
    val notifications: List<ReportNotification> = emptyList(),
    val selectedReport: Report? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val photo: ImageBitmap? = null,
    val submittedReportId: String? = null
)

class PinPointViewModel(application: Application) : AndroidViewModel(application) {
    private val backend = FirebaseBackend.create(application)
    private val repository = backend?.let(::PinPointRepository)
    private val mutableState = MutableStateFlow(PinPointState(configured = backend != null))
    val state = mutableState.asStateFlow()
    private val listeners = mutableListOf<Job>()
    private var detailJob: Job? = null
    private var actionJob: Job? = null
    private var observedUid: String? = null
    private var draftId: String? = null
    private val deviceId: String
    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        val uid = auth.currentUser?.uid
        if (uid != observedUid) {
            observedUid = uid
            listeners.forEach { it.cancel() }
            listeners.clear()
            detailJob?.cancel()
            mutableState.update { PinPointState(configured = true, busy = it.busy) }
            if (uid != null) observeAccount(uid)
        }
    }

    init {
        val preferences = application.getSharedPreferences("pinpoint_device", 0)
        deviceId = preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("id", it).apply()
        }
        backend?.auth?.addAuthStateListener(authListener)
    }

    private fun observeAccount(uid: String) {
        val repo = repository ?: return
        mutableState.update { it.copy(loading = true) }
        listeners += viewModelScope.launch {
            try {
                val citizen = repo.citizen()
                if (observedUid == uid) mutableState.update { it.copy(user = citizen) }
            } catch (error: Exception) { handle(error) }
        }
        listeners += viewModelScope.launch {
            repo.reports(true).catch { handle(it); mutableState.update { s -> s.copy(loading = false) } }
                .collect { reports -> mutableState.update { it.copy(reports = reports, loading = false) } }
        }
        listeners += viewModelScope.launch {
            repo.reports(false).catch { handle(it) }
                .collect { reports -> mutableState.update { it.copy(community = reports) } }
        }
        listeners += viewModelScope.launch {
            repo.notifications().catch { handle(it) }
                .collect { notices -> mutableState.update { it.copy(notifications = notices) } }
        }
        registerPushToken()
    }

    fun authenticate(register: Boolean, name: String, email: String, password: String) = action {
        val repo = requireNotNull(repository)
        if (register) repo.register(name, email, password) else repo.signIn(email, password)
        mutableState.update { it.copy(user = repo.citizen()) }
    }

    fun editProfile(name: String) = action {
        val repo = requireNotNull(repository)
        repo.saveProfile(name)
        mutableState.update { it.copy(user = repo.citizen(), message = "Profile saved.") }
    }

    fun signOut() = action {
        // If device removal fails, keep the account signed in so the user can retry.
        requireNotNull(repository).removeDevice(deviceId)
        backend?.auth?.signOut()
        draftId = null
    }

    fun startReport() {
        draftId = null
        mutableState.update { it.copy(submittedReportId = null, error = null, message = null) }
    }

    fun acknowledgeSubmission() { mutableState.update { it.copy(submittedReportId = null) } }

    fun submit(draft: ReportDraft, image: Uri?) = action {
        draft.validate()
        val repo = requireNotNull(repository)
        val id = draftId ?: repo.newReportId().also { draftId = it }
        val photo = image?.let { PhotoCompressor.jpeg(getApplication(), it) }
        repo.submit(id, draft, photo)
        draftId = null
        mutableState.update { it.copy(submittedReportId = id) }
    }

    fun openReport(id: String) {
        detailJob?.cancel()
        mutableState.update { it.copy(selectedReport = null, photo = null, detailLoading = true, detailError = null) }
        val repo = repository ?: return
        detailJob = viewModelScope.launch {
            var lastPhotoPath: String? = null
            repo.report(id).catch { error ->
                if (error is CancellationException) throw error
                mutableState.update { it.copy(detailLoading = false, detailError = errorText(error)) }
            }.collect { report ->
                mutableState.update { it.copy(selectedReport = report, detailLoading = false,
                    detailError = if (report == null) "Report not found." else null) }
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
        mutableState.update { it.copy(message = "Report updated.") }
    }

    fun markRead(id: String) = action { requireNotNull(repository).markRead(id) }
    fun clearFeedback() { mutableState.update { it.copy(error = null, message = null) } }

    fun retry() {
        val uid = backend?.auth?.currentUser?.uid ?: return
        listeners.forEach { it.cancel() }
        listeners.clear()
        clearFeedback()
        observeAccount(uid)
    }

    fun registerPushToken() {
        if (BuildConfig.FIREBASE_EMULATORS) return
        if (backend?.auth?.currentUser == null) return
        viewModelScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                repository?.registerDevice(deviceId, token)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(message = "Push registration failed: ${errorText(error)}. Notification history is still available.") }
            }
        }
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

    override fun onCleared() {
        backend?.auth?.removeAuthStateListener(authListener)
        super.onCleared()
    }
}
