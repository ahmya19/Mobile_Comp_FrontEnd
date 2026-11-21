package com.example.pinpoint.data

import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.Query
import com.google.firebase.storage.StorageMetadata
import java.text.DateFormat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class PinPointRepository(private val backend: FirebaseBackend) {
    val auth get() = backend.auth

    suspend fun citizen(): Citizen? {
        val user = auth.currentUser ?: return null
        val claims = user.getIdToken(false).await().claims
        return Citizen(user.uid, user.email.orEmpty(), user.displayName.orEmpty(), claims["official"] == true)
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun register(name: String, email: String, password: String) {
        require(name.trim().length in 2..80) { "Name must contain 2 to 80 characters." }
        require(password.length >= 8) { "Use a password with at least 8 characters." }
        val user = auth.createUserWithEmailAndPassword(email.trim(), password).await().user
            ?: error("Registration did not return a user.")
        // Auth is the source of display name; profile fields never contain roles.
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await()
        saveProfile(name)
    }

    suspend fun saveProfile(name: String) {
        require(name.trim().length in 2..80) { "Name must contain 2 to 80 characters." }
        val user = auth.currentUser ?: error("Sign in first.")
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await()
        backend.database.collection("users").document(user.uid).set(mapOf(
            "displayName" to name.trim(), "updatedAt" to FieldValue.serverTimestamp()
        )).await()
    }

    fun reports(mine: Boolean) = callbackFlow {
        val user = auth.currentUser ?: run { close(IllegalStateException("Sign in first.")); return@callbackFlow }
        var query: Query = backend.database.collection("reports")
        if (mine) query = query.whereEqualTo("ownerId", user.uid)
        val registration = query.orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else trySend(snapshot?.documents.orEmpty().map { it.toReport() })
            }
        awaitClose { registration.remove() }
    }

    fun report(id: String) = callbackFlow {
        val registration = backend.database.collection("reports").document(id).addSnapshotListener { doc, error ->
            if (error != null) close(error)
            else trySend(doc?.takeIf { it.exists() }?.toReport())
        }
        awaitClose { registration.remove() }
    }

    fun notifications() = callbackFlow {
        val uid = auth.currentUser?.uid ?: run { close(IllegalStateException("Sign in first.")); return@callbackFlow }
        val registration = backend.database.collection("users").document(uid).collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else trySend(snapshot?.documents.orEmpty().map { doc ->
                    ReportNotification(doc.id, doc.getString("reportId").orEmpty(),
                        doc.getString("message").orEmpty(), doc.getBoolean("read") == true)
                })
            }
        awaitClose { registration.remove() }
    }

    // The caller keeps this ID across retries, so an uncertain network result
    // cannot create a second report. Only a server acknowledgement means success.
    fun newReportId(): String = backend.database.collection("reports").document().id

    suspend fun submit(id: String, draft: ReportDraft, photo: ByteArray?): String {
        draft.validate()
        val uid = auth.currentUser?.uid ?: error("Sign in first.")
        val ref = backend.database.collection("reports").document(id)
        val existing = ref.get(com.google.firebase.firestore.Source.SERVER).await()
        if (existing.exists()) {
            check(existing.getString("ownerId") == uid) { "Report does not belong to this account." }
            return id
        }
        val photoPath = photo?.let { "report-images/$uid/$id/photo.jpg" }
        if (photo != null && photoPath != null) {
            require(photo.size <= 256000) { "Photo must be at most 250 KB." }
            backend.storage.reference.child(photoPath).putBytes(photo,
                StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
        }
        // Do not delete the photo if acknowledgement fails: the write may already
        // have committed. Retrying this ID checks the server before uploading.
        ref.set(mapOf(
            "ownerId" to uid, "category" to draft.category,
            "description" to draft.description.trim(), "location" to draft.location.trim(),
            "barangay" to draft.barangay.trim(), "status" to "Pending", "priority" to "Low",
            "source" to "app", "photoPath" to photoPath,
            "coordinates" to draft.latitude?.let { GeoPoint(it, requireNotNull(draft.longitude)) },
            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()
        )).await()
        return id
    }

    suspend fun photo(path: String): ByteArray = backend.storage.reference.child(path).getBytes(256000).await()

    suspend fun updateStatus(id: String, status: String, priority: String) {
        backend.functions.getHttpsCallable("updateReportStatus").call(mapOf(
            "reportId" to id, "status" to status, "priority" to priority
        )).await()
    }

    suspend fun markRead(id: String) {
        val uid = auth.currentUser?.uid ?: return
        backend.database.document("users/$uid/notifications/$id").update("read", true).await()
    }

    suspend fun registerDevice(deviceId: String, token: String) {
        val uid = auth.currentUser?.uid ?: return
        backend.database.document("users/$uid/devices/$deviceId").set(mapOf(
            "token" to token, "updatedAt" to FieldValue.serverTimestamp()
        )).await()
    }

    suspend fun removeDevice(deviceId: String) {
        val uid = auth.currentUser?.uid ?: return
        backend.database.document("users/$uid/devices/$deviceId").delete().await()
    }

    private fun DocumentSnapshot.toReport(): Report {
        val point = getGeoPoint("coordinates")
        return Report(id, getString("ownerId").orEmpty(), getString("category").orEmpty(),
            getString("description").orEmpty(), getString("location").orEmpty(), getString("barangay").orEmpty(),
            getString("status") ?: "Pending", getString("priority") ?: "Low",
            getTimestamp("createdAt")?.toDate()?.let { DateFormat.getDateTimeInstance().format(it) } ?: "Waiting for sync",
            getString("photoPath"), point?.latitude, point?.longitude, metadata.hasPendingWrites())
    }
}
