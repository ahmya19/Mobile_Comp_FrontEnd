package com.example.pinpoint

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.example.pinpoint.data.*
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit host-driven field trial. Never toggles radios or grants permissions.
 * Evidence and generated photos stay in this app's private storage. */
@RunWith(AndroidJUnit4::class)
class PhoneOfflineTrialTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val backend get()=requireNotNull(SupabaseBackend.create(context))
    private val queue get()=ReportQueue.get(context)
    private val connectivity get()=context.getSystemService(ConnectivityManager::class.java)
    private val evidenceFile get()=File(context.filesDir,"cp25_active_trial.json")
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun online(): Boolean=connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
    private fun save(record: JsonObject) {
        val temporary=File(context.filesDir,"cp25_active_trial.tmp")
        temporary.writeText(backend.gson.toJson(record))
        check(temporary.renameTo(evidenceFile)) { "Could not persist trial evidence." }
        val archived=File(context.filesDir,"cp25_trial_${record["run"].asInt}.json")
        archived.writeText(backend.gson.toJson(record))
    }
    private fun read(): JsonObject=backend.gson.fromJson(evidenceFile.readText(),JsonObject::class.java)
    private fun launch(): android.app.Activity =
        instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    private fun workSnapshot(): Any=WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork("pinpoint-reports-${backend.auth.currentUser?.id}")
        .get(5,TimeUnit.SECONDS).filter { !it.state.isFinished }.map {
            mapOf("state" to it.state.name,"attempt" to it.runAttemptCount)
        }
    private fun current(record: JsonObject): QueuedReport {
        assertEquals(record["owner_id"].asString,backend.auth.currentUser?.id)
        assertEquals(record["project"].asString,backend.url)
        return queue.items(record["owner_id"].asString,backend.url).single { it.id==record["report_id"].asString }
    }
    private suspend fun verifyCloud(record: JsonObject) {
        record.addProperty("verification_stage","queue");save(record)
        assertEquals("Synced",current(record).state)
        record.addProperty("verification_stage","report_query");save(record)
        val description=URLEncoder.encode(record["description"].asString,"UTF-8")
        val rows=backend.json("rest/v1/reports?description=eq.$description&select=id,owner_id,created_at,photo_path").asJsonArray
        record.addProperty("cloud_copies",rows.size())
        assertEquals("Duplicate or missing report",1,rows.size())
        val row=rows.single().asJsonObject
        assertEquals(record["report_id"].asString,row["id"].asString)
        assertEquals(record["owner_id"].asString,row["owner_id"].asString)
        record.addProperty("verification_stage","photo_download");save(record)
        val photo=PinPointRepository(backend).photo(row["photo_path"].asString)
        assertEquals(record["photo_sha256"].asString,hash(photo))
        val decoded=BitmapFactory.decodeByteArray(photo,0,photo.size)
        assertNotNull("Cloud photo is not decodable",decoded);decoded?.recycle()
        record.addProperty("photo_integrity",true)
        record.addProperty("server_created_at",row["created_at"].asString)
        record.addProperty("verification_stage","verified")
    }

    @Test fun fieldTrial(): Unit=runBlocking {
        assumeTrue("Use the explicit CP-25 runner",args.containsKey("cp25_phase"))
        when(args.getString("cp25_phase")) {
            "setup" -> {
                check(online()) { "Keep Internet on for the initial demo sign-in." }
                backend.auth.signIn(requireNotNull(args.getString("email")),requireNotNull(args.getString("password")))
                assertEquals(args.getString("owner_id"),backend.auth.currentUser?.id)
                println("CP25_SETUP="+backend.gson.toJson(mapOf("android" to android.os.Build.VERSION.RELEASE,
                    "model" to android.os.Build.MODEL,"validated_internet" to online(),
                    "notification_permission_already_granted" to (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==0))))
            }
            "enqueue" -> {
                check(!online() && connectivity.activeNetwork==null) { "Turn airplane mode ON and Wi-Fi OFF before enqueueing." }
                val run=requireNotNull(args.getString("run")).toInt().also { require(it in 4..10) }
                val transport=requireNotNull(args.getString("transport")).also { require(it in listOf("WiFi","Cellular")) }
                if(evidenceFile.exists()) {
                    val previous=read()
                    check(previous["phase"].asString in listOf("complete","complete_with_transport_deviation","complete_with_observation_failure")) { "Finish or inspect the existing trial before creating another." }
                    check(previous["run"].asInt!=run) { "This run already has evidence. Do not overwrite it." }
                }
                check(!File(context.filesDir,"cp25_trial_$run.json").exists()) { "This run number already has archived evidence." }
                assertEquals(args.getString("owner_id"),backend.auth.currentUser?.id)
                val id=UUID.randomUUID().toString()
                val description="TEST CP25 run $run ${id.take(8)}: offline photo report; not a real community issue."
                val photo=File(context.cacheDir,"cp25-generated.jpg")
                val random=java.util.Random(run.toLong())
                val pixels=IntArray(512*512) { android.graphics.Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256)) }
                val bitmap=Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888)
                photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)) };bitmap.recycle()
                queue.enqueue(backend,id,ReportDraft("Other",description,"CP25 simulated test location","Alangilan"),Uri.fromFile(photo),false)
                val item=queue.items(requireNotNull(backend.auth.currentUser).id,backend.url).single { it.id==id }
                val bytes=File(requireNotNull(item.photoFile)).readBytes()
                assertTrue(bytes.isNotEmpty() && bytes.size<=256_000)
                assertTrue(item.state in listOf("Queued","Sending"))
                val record=JsonObject().apply {
                    addProperty("run",run);addProperty("phase","queued");addProperty("report_id",id)
                    addProperty("description",description);addProperty("owner_id",backend.auth.currentUser?.id)
                    addProperty("project",backend.url);addProperty("expected_transport",transport)
                    addProperty("queued_epoch_ms",item.createdAt);addProperty("compressed_bytes",bytes.size)
                    addProperty("photo_sha256",hash(bytes));addProperty("photo_fixture","generated 512x512 JPEG")
                    addProperty("original_requested",false);addProperty("location_source","none; no GPS")
                    add("work_before_restart",backend.gson.toJsonTree(workSnapshot()))
                }
                save(record)
                println("CP25_QUEUED="+backend.gson.toJson(mapOf("run" to run,"report_id" to id,"photo_bytes" to bytes.size)))
            }
            "inspect" -> {
                check(!online() && connectivity.activeNetwork==null) { "Keep airplane mode ON and Wi-Fi OFF during restart." }
                val record=read()
                check(record["phase"].asString=="queued") { "Expected a queued trial before restart inspection." }
                launch()
                val item=current(record)
                assertTrue(item.state in listOf("Queued","Sending"))
                assertEquals(record["photo_sha256"].asString,hash(File(requireNotNull(item.photoFile)).readBytes()))
                record.addProperty("survived_force_stop",true)
                record.addProperty("phase","restarted")
                record.add("work_after_restart",backend.gson.toJsonTree(workSnapshot()))
                save(record)
                println("CP25_RESTART_VERIFIED="+record["run"].asInt)
            }
            "observe" -> {
                check(!online() && connectivity.activeNetwork==null) { "Start monitoring before reconnecting." }
                val record=read()
                check(record["phase"].asString=="restarted") { "Verify force-stop restoration before reconnecting." }
                val keepVisible=args.getString("keep_visible")=="true"
                val visibleActivity=if(keepVisible) launch().also { activity ->
                    instrumentation.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                } else null
                record.addProperty("monitor_foreground",keepVisible)
                val available=ConcurrentHashMap<Network,Long>()
                val first=AtomicReference<ConnectionStamp?>(null)
                val callback=object: ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { available[network]=SystemClock.elapsedRealtime() }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            val transport=when {
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)->"VPN"
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)->"WiFi"
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)->"Cellular"
                                else->"Other"
                            }
                            first.compareAndSet(null,ConnectionStamp(available[network] ?: SystemClock.elapsedRealtime(),
                                SystemClock.elapsedRealtime(),System.currentTimeMillis(),transport))
                        }
                    }
                }
                connectivity.registerDefaultNetworkCallback(callback)
                try {
                    val waitStarted=SystemClock.elapsedRealtime()
                    record.addProperty("observer_started_elapsed_ms",waitStarted)
                    record.addProperty("phase","watching");save(record)
                    println("CP25_READY_TO_RECONNECT="+record["run"].asInt)
                    var acknowledged: Long?=null
                    while(SystemClock.elapsedRealtime()-waitStarted<300_000) {
                        val item=current(record)
                        if(item.state=="Synced") { acknowledged=SystemClock.elapsedRealtime();break }
                        if(item.state=="Failed") error(item.error ?: "Queue failed")
                        val connection=first.get()
                        if(connection!=null && SystemClock.elapsedRealtime()-connection.availableElapsed>180_000) break
                        delay(200)
                    }
                    val connection=requireNotNull(first.get()) { "No validated reconnect was observed." }
                    record.addProperty("observed_transport",connection.transport)
                    record.addProperty("validated_epoch_ms",connection.validatedEpoch)
                    record.addProperty("available_elapsed_ms",connection.availableElapsed)
                    record.addProperty("validated_elapsed_ms",connection.validatedElapsed)
                    record.add("work_after_reconnect",backend.gson.toJsonTree(workSnapshot()))
                    record.addProperty("queue_state",current(record).state)
                    if(acknowledged!=null) {
                        record.addProperty("available_to_ack_ms",acknowledged-connection.availableElapsed)
                        record.addProperty("validated_to_ack_ms",acknowledged-connection.validatedElapsed)
                    }
                    save(record)
                    assertNotNull("No local sync acknowledgement within 180 seconds after reconnect",acknowledged)
                    assertEquals("Reconnect used a different transport than planned",record["expected_transport"].asString,connection.transport)
                    withTimeout(60_000) { verifyCloud(record) }
                    record.addProperty("phase","complete");save(record)
                    println("CP25_RESULT="+backend.gson.toJson(record))
                } catch(error: Throwable) {
                    record.addProperty("phase","needs_review")
                    record.addProperty("error","${error.javaClass.simpleName}: ${error.message}")
                    save(record)
                    throw error
                } finally {
                    connectivity.unregisterNetworkCallback(callback)
                    visibleActivity?.let { activity -> instrumentation.runOnMainSync {
                        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } }
                }
            }
            "verify-deviation" -> {
                val record=read()
                check(record["phase"].asString=="needs_review") { "Only review an existing failed observation." }
                check(record.has("observed_transport") && record["expected_transport"].asString!=record["observed_transport"].asString) {
                    "This is not a transport deviation. Investigate its original failure."
                }
                withTimeout(60_000) { verifyCloud(record) }
                record.addProperty("transport_match",false)
                record.addProperty("phase","complete_with_transport_deviation")
                save(record)
            }
            "verify-recovery" -> {
                val record=read()
                val incomplete=record["phase"].asString=="watching" && record.has("observer_started_elapsed_ms") &&
                    SystemClock.elapsedRealtime()-record["observer_started_elapsed_ms"].asLong>=330_000
                check(record["phase"].asString=="needs_review" || incomplete) { "Only review a failed or externally timed-out observation." }
                check(!record.has("available_to_ack_ms")) { "Review an existing timed result separately." }
                withTimeout(60_000) { verifyCloud(record) }
                record.addProperty("timing_valid",false)
                record.addProperty("observation_incomplete_at_recovery",incomplete)
                record.addProperty("recovery_condition","PinPoint reopened after stalled background observer")
                record.addProperty("phase","complete_with_observation_failure")
                save(record)
            }
            else -> error("Unknown CP-25 phase")
        }
    }
    private data class ConnectionStamp(val availableElapsed: Long,val validatedElapsed: Long,
        val validatedEpoch: Long,val transport: String)
}
