package com.example.pinpoint.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.CancellationException

/** All screen callers use this module; SQL policies remain the authority. */
class PinPointRepository(private val backend: SupabaseBackend) {
    val auth get() = backend.auth
    suspend fun citizen(): Citizen? {
        val user = auth.currentUser ?: return null
        val profile = rpc("my_profile").takeUnless { it.isJsonNull }?.asJsonObject
        return Citizen(user.id,user.email,profile?.string("display_name") ?: user.displayName,profile?.get("official")?.asBoolean == true,profile?.string("barangay"))
    }
    suspend fun signIn(email: String, password: String) = auth.signIn(email,password)
    suspend fun register(name: String, email: String, password: String): Boolean {
        val signedIn = auth.register(name,email,password)
        if (signedIn) saveProfile(name)
        return signedIn
    }
    suspend fun saveProfile(name: String) {
        require(name.trim().length in 2..80) { "Name must contain 2 to 80 characters." }
        rpc("save_profile",mapOf("p_display_name" to name.trim()))
    }
    // Short polling avoids a persistent socket while preserving the existing
    // screen contract. The durable queue is separate from these remote reads.
    fun reports(mine: Boolean) = flow {
        while (true) { emit(pageReports(ReportFilter(mine=mine),null,100).reports); delay(15000) }
    }
    suspend fun pageReports(filter: ReportFilter, cursor: ReportCursor?, limit: Int = 25): ReportPage {
        require(limit in 1..100)
        val params = mutableListOf("select=*","order=created_at.desc,id.desc","limit=${limit+1}")
        if (filter.mine) params += "owner_id=eq.${requireNotNull(auth.currentUser).id}"
        listOf("barangay" to filter.barangay,"category" to filter.category,"status" to filter.status).forEach { (key,value) ->
            if (!value.isNullOrBlank()) params += "$key=eq.${encode(value)}"
        }
        filter.after?.let { params += "created_at=gte.${encode(it)}" }
        filter.before?.let { params += "created_at=lte.${encode(it)}" }
        cursor?.let {
            require(it.id.matches(Regex("[0-9a-fA-F-]{36}")) && it.createdAt.matches(Regex("[0-9T:.+Z-]+"))) { "Invalid report cursor." }
            params += "or=" + encode("(created_at.lt.${it.createdAt},and(created_at.eq.${it.createdAt},id.lt.${it.id}))")
        }
        val rows = backend.json("rest/v1/reports?${params.joinToString("&")}").asJsonArray
        val selected = rows.take(limit).map { it.asJsonObject }
        return ReportPage(selected.map { it.toReport() }, if (rows.size()>limit) selected.lastOrNull()?.let {
            ReportCursor(it.string("created_at").orEmpty(),it.string("id").orEmpty())
        } else null)
    }
    fun report(id: String, onError: (Throwable)->Unit = {}) = flow {
        require(UUID.fromString(id).toString()==id)
        while (true) {
            emit(backend.json("rest/v1/reports?id=eq.$id&select=*&limit=1").asJsonArray.firstOrNull()?.asJsonObject?.toReport())
            delay(10000)
        }
    }.retryWhen { cause,_ ->
        if(cause is CancellationException) throw cause
        onError(cause);delay(10000);true
    }
    fun notifications(onError: (Throwable)->Unit = {}) = flow {
        while (true) {
            emit(backend.json("rest/v1/notifications?select=*&order=created_at.desc,id.desc&limit=100").asJsonArray.map {
                val row=it.asJsonObject
                ReportNotification(row.string("id").orEmpty(),row.string("report_id").orEmpty(),row.string("message").orEmpty(),row["read"].asBoolean)
            }); delay(10000)
        }
    }.retryWhen { cause,_ ->
        if(cause is CancellationException) throw cause
        onError(cause);delay(10000);true
    }
    fun newReportId(): String = UUID.randomUUID().toString()
    suspend fun submit(id: String,draft: ReportDraft,photo: ByteArray?,expectedOwner: String? = null): String {
        draft.validate()
        val uid=auth.currentUser?.id ?: error("Sign in first.")
        check(expectedOwner==null || uid==expectedOwner) { "The queued report belongs to another account." }
        val photoPath=photo?.let { "$uid/$id/photo.jpg" }
        if (photo!=null && photoPath!=null) {
            require(photo.size<=256000) { "Photo must be at most 250 KB." }
            try { backend.request("storage/v1/object/report-images/$photoPath","POST",photo,"image/jpeg",expectedUserId=uid) }
            catch (error: BackendException) {
                if (error.status !in listOf(400,409) || !backend.request("storage/v1/object/authenticated/report-images/$photoPath","GET",expectedUserId=uid).contentEquals(photo)) throw error
            }
        }
        rpc("submit_report",mapOf("p_id" to id,"p_category" to draft.category,"p_description" to draft.description.trim(),
            "p_location" to draft.location.trim(),"p_barangay" to draft.barangay.trim(),
            "p_latitude" to draft.latitude,"p_longitude" to draft.longitude,"p_photo_path" to photoPath),uid)
        return id
    }
    suspend fun photo(path: String): ByteArray = backend.request("storage/v1/object/authenticated/report-images/${path.split('/').joinToString("/"){encode(it)}}","GET")
    suspend fun history(id: String): List<StatusChange> = backend.json("rest/v1/report_history?report_id=eq.${UUID.fromString(id)}&select=*&order=created_at.asc,id.asc").asJsonArray.map {
        val row=it.asJsonObject
        StatusChange(row.string("status").orEmpty(),row.string("previous_status").orEmpty(),row.string("created_at").orEmpty())
    }
    suspend fun updateStatus(id: String,status: String,priority: String) {
        rpc("update_report_status",mapOf("p_report_id" to id,"p_status" to status,"p_priority" to priority))
    }
    suspend fun deliverPush(id: String,owner: String) { backend.json("functions/v1/deliver-push","POST",mapOf("report_id" to id),expectedUserId=owner) }
    suspend fun pendingPushReports(): List<String> = rpc("pending_push_reports").asJsonArray.map { it.asString }
    suspend fun vote(id: String,voted: Boolean) { rpc("vote_report",mapOf("p_report_id" to id,"p_voted" to voted)) }
    suspend fun voted(id: String): Boolean = backend.json("rest/v1/report_votes?report_id=eq.$id&select=report_id&limit=1").asJsonArray.size()>0
    suspend fun markRead(id: String) { rpc("mark_notification_read",mapOf("p_id" to id)) }
    suspend fun registerDevice(deviceId: String,token: String,expectedOwner: String?=null) { rpc("register_device",mapOf("p_installation_id" to deviceId,"p_token" to token),expectedOwner) }
    suspend fun removeDevice(deviceId: String) { rpc("remove_device",mapOf("p_installation_id" to deviceId)) }
    private suspend fun rpc(name: String,body: Any = emptyMap<String,String>(),expectedUserId: String?=null): JsonElement = backend.json("rest/v1/rpc/$name","POST",body,expectedUserId=expectedUserId)
    private fun JsonObject.toReport(): Report {
        val date=string("created_at").orEmpty()
        return Report(id=string("id").orEmpty(),ownerId=string("owner_id").orEmpty(),category=string("category").orEmpty(),
            description=string("description").orEmpty(),location=string("location").orEmpty(),barangay=string("barangay").orEmpty(),
            status=string("status") ?: "Pending",priority=string("priority") ?: "Low",createdAt=date,
            photoPath=string("photo_path"),latitude=get("latitude")?.takeUnless{it.isJsonNull}?.asDouble,
            longitude=get("longitude")?.takeUnless{it.isJsonNull}?.asDouble,voteCount=get("vote_count")?.asInt ?: 0,
            originalPath=string("original_path"))
    }
    private fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
    private fun encode(value: String): String = URLEncoder.encode(value,"UTF-8").replace("+","%20")
}

data class ReportFilter(val mine: Boolean=false,val barangay: String?=null,val category: String?=null,
    val status: String?=null,val after: String?=null,val before: String?=null)
data class ReportCursor(val createdAt: String,val id: String)
data class ReportPage(val reports: List<Report>,val next: ReportCursor?)
