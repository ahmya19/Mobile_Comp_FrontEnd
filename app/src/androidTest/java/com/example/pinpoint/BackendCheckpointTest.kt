package com.example.pinpoint

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.pinpoint.data.ReportDraft
import com.example.pinpoint.data.ReportQueue
import com.example.pinpoint.data.SupabaseBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Host runner invokes phases around a real emulator process/network restart. */
@RunWith(AndroidJUnit4::class)
class BackendCheckpointTest {
    @get:Rule(order=0) val notifications=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order=1) val compose=createEmptyComposeRule()

    @After fun closeActivities() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            listOf(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.PAUSED,
                androidx.test.runner.lifecycle.Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it).toList() }
                .distinct().forEach { it.finish() }
        }
    }

    @Test fun checkpointFlow(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val args=InstrumentationRegistry.getArguments()
        if(args.getString("phase")!="background-push") InstrumentationRegistry.getInstrumentation()
            .startActivitySync(android.content.Intent(context,MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val backend=requireNotNull(SupabaseBackend.create(context))
        val queue=ReportQueue.get(context)
        val saved=context.getSharedPreferences("checkpoint_test",0)
        when(args.getString("phase")) {
            "signin"->{
                backend.auth.signIn(requireNotNull(args.getString("email")),requireNotNull(args.getString("password")))
                saved.edit().putString("owner",backend.auth.currentUser?.id).commit()
                compose.waitUntil(30000){compose.onAllNodesWithText("Quick Report").fetchSemanticsNodes().isNotEmpty()}
            }
            "enqueue"->{
                val owner=requireNotNull(saved.getString("owner",null))
                assertEquals(owner,backend.auth.currentUser?.id)
                compose.waitUntil(20000){compose.onAllNodesWithText("Quick Report").fetchSemanticsNodes().isNotEmpty()}
                val id=UUID.randomUUID().toString()
                val fixture=File(context.cacheDir,"checkpoint-photo.jpg")
                val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.rgb(124,77,204))
                fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,85,it) };bitmap.recycle()
                val description="Offline checkpoint ${id.take(8)}: pothole near school."
                queue.enqueue(backend,id,ReportDraft("Road Damage",description,"Test main road","Alangilan",13.78,121.07),Uri.fromFile(fixture),true)
                saved.edit().putString("report_id",id).putString("description",description).commit()
                delay(1000)
                val item=queue.items(owner,backend.url).first{it.id==id}
                assertNotEquals("Synced",item.state)
                assertTrue(File(requireNotNull(item.photoFile)).isFile)
                assertTrue(File(requireNotNull(item.originalFile)).isFile)
                println("PINPOINT_REPORT_ID=$id")
            }
            "inspect"->{
                val owner=requireNotNull(saved.getString("owner",null))
                assertEquals(owner,backend.auth.currentUser?.id)
                val id=requireNotNull(saved.getString("report_id",null))
                val item=queue.items(owner,backend.url).first{it.id==id}
                assertTrue(item.state in listOf("Queued","Sending"))
                assertTrue(File(requireNotNull(item.photoFile)).isFile)
                assertTrue(File(requireNotNull(item.originalFile)).isFile)
                compose.waitUntil(20000){compose.onAllNodesWithText("Quick Report").fetchSemanticsNodes().isNotEmpty()}
            }
            "sync","sync-cellular","sync-metered"->{
                val owner=requireNotNull(saved.getString("owner",null))
                val id=requireNotNull(saved.getString("report_id",null))
                queue.schedule(owner)
                for(attempt in 0 until 120){
                    if(queue.items(owner,backend.url).first{it.id==id}.state=="Synced") break
                    delay(500)
                }
                val item=queue.items(owner,backend.url).first{it.id==id}
                assertEquals(item.error,"Synced",item.state)
                assertFalse(File(requireNotNull(item.photoFile)).exists())
                if(args.getString("phase") in listOf("sync-cellular","sync-metered")) {
                    assertNotEquals("Original must wait for Wi-Fi","Uploaded",item.originalState)
                    assertTrue(File(requireNotNull(item.originalFile)).isFile)
                    val connectivity=context.getSystemService(android.net.ConnectivityManager::class.java)
                    assertFalse(requireNotNull(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
                        .hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                }
                compose.waitUntil(30000){compose.onAllNodesWithText(requireNotNull(saved.getString("description",null))).fetchSemanticsNodes().isNotEmpty()}
            }
            "original-queue"->{
                val owner=requireNotNull(saved.getString("owner",null))
                val id=requireNotNull(saved.getString("report_id",null))
                queue.scheduleOriginals(owner)
                for(attempt in 0 until 120) {
                    if(queue.items(owner,backend.url).first{it.id==id}.originalState=="Uploaded") break
                    delay(500)
                }
                val item=queue.items(owner,backend.url).first{it.id==id}
                assertEquals("Uploaded",item.originalState)
                assertFalse(File(requireNotNull(item.originalFile)).exists())
            }
            "push","background-push"->{
                val foreground=args.getString("phase")=="push"
                saved.edit().putBoolean("ready_for_push",false).commit()
                val manager=context.getSystemService(android.app.NotificationManager::class.java)
                manager.cancelAll()
                val id=args.getString("report_id") ?: requireNotNull(saved.getString("report_id",null))
                val description=backend.json("rest/v1/reports?id=eq.$id&select=description").asJsonArray.first().asJsonObject["description"].asString
                val token=com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
                val installation=requireNotNull(context.getSharedPreferences("pinpoint_device",0).getString("id",null))
                com.example.pinpoint.data.PinPointRepository(backend).registerDevice(installation,token)
                if(foreground) compose.waitUntil(30000){compose.onAllNodesWithText("Quick Report").fetchSemanticsNodes().isNotEmpty()}
                println("PINPOINT_READY_FOR_PUSH=$id")
                saved.edit().putBoolean("ready_for_push",true).commit()
                saved.edit().putString("push_run_nonce",args.getString("run_nonce")).commit()
                var received=false
                for(attempt in 0 until 120) {
                    received=manager.activeNotifications.any { notice ->
                        notice.notification.extras.getString("android.title")=="Report updated"
                    }
                    if(received) break
                    delay(500)
                }
                assertTrue("No foreground FCM notification arrived within 60 seconds",received)
                saved.edit().putBoolean("ready_for_push",false).commit()
                if(foreground) {
                    requireNotNull(manager.activeNotifications.first { it.notification.extras.getString("android.title")=="Report updated" }
                        .notification.contentIntent).send()
                    compose.waitUntil(30000){compose.onAllNodesWithText("Report Details").fetchSemanticsNodes().isNotEmpty()}
                    compose.waitUntil(15000){compose.onAllNodesWithText("Description: $description").fetchSemanticsNodes().isNotEmpty()}
                }
            }
            "original-start"->{
                val id=UUID.randomUUID().toString()
                val file=File(context.filesDir,"checkpoint-original.jpg")
                val size=2304
                val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
                val random=java.util.Random(42)
                val pixels=IntArray(size*size) { android.graphics.Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256)) }
                bitmap.setPixels(pixels,0,size,0,0,size,size)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,100,it) };bitmap.recycle()
                assertTrue("Fixture must span two TUS chunks",file.length()>6L*1024*1024 && file.length()<=10485760)
                val draft=ReportDraft("Road Damage","Resumable upload checkpoint fixture.","Test main road","Alangilan")
                com.example.pinpoint.data.PinPointRepository(backend).submit(id,draft,null)
                saved.edit().putString("original_report",id).commit()
                val item=com.example.pinpoint.data.QueuedReport(id,requireNotNull(backend.auth.currentUser).id,backend.url,draft,null,"Synced",null,0,
                    file.absolutePath,"image/jpeg","Uploading on Wi-Fi")
                try {
                    com.example.pinpoint.data.WifiOriginals(context,backend).upload(item,onProgress={ offset ->
                        saved.edit().putLong("original_offset",offset).commit()
                        throw kotlinx.coroutines.CancellationException("Checkpoint interruption after acknowledged chunk")
                    }) { session -> saved.edit().putString("original_session",session).commit() }
                    fail("The first chunk must interrupt the transfer")
                } catch(expected: kotlinx.coroutines.CancellationException) {
                    assertEquals(6L*1024*1024,saved.getLong("original_offset",0))
                    assertNotNull(saved.getString("original_session",null))
                }
            }
            "original-resume"->{
                val id=requireNotNull(saved.getString("original_report",null))
                val file=File(context.filesDir,"checkpoint-original.jpg")
                val draft=ReportDraft("Road Damage","Resumable upload checkpoint fixture.","Test main road","Alangilan")
                val item=com.example.pinpoint.data.QueuedReport(id,requireNotNull(backend.auth.currentUser).id,backend.url,draft,null,"Synced",null,0,
                    file.absolutePath,"image/jpeg","Waiting for Wi-Fi",requireNotNull(saved.getString("original_session",null)))
                com.example.pinpoint.data.WifiOriginals(context,backend).upload(item) { session -> saved.edit().putString("original_session",session).commit() }
                backend.json("rest/v1/rpc/attach_original","POST",mapOf("p_report_id" to id))
                val actual=com.example.pinpoint.data.WifiOriginals(context,backend).download("${item.ownerId}/$id/original")
                assertArrayEquals(file.readBytes(),actual)
                saved.edit().remove("original_session").commit()
                file.delete()
            }
            "paging"->{
                val repo=com.example.pinpoint.data.PinPointRepository(backend)
                val filter=com.example.pinpoint.data.ReportFilter(mine=true)
                var cursor: com.example.pinpoint.data.ReportCursor?=null
                val ids=mutableListOf<String>()
                do {
                    val page=repo.pageReports(filter,cursor,1)
                    ids+=page.reports.map { it.id };cursor=page.next
                } while(cursor!=null && ids.size<200)
                assertEquals(ids.size,ids.distinct().size)
                assertEquals(repo.pageReports(filter,null,100).reports.map { it.id }.toSet(),ids.toSet())
                assertTrue(repo.pageReports(filter.copy(after="2100-01-01T00:00:00Z"),null).reports.isEmpty())
                compose.onNodeWithText("View community reports").performScrollTo().performClick()
                compose.onNodeWithText("Filter reports").performClick()
                compose.onNodeWithText("Barangay (exact name)").performTextInput("NoSuchBarangay")
                compose.onNodeWithText("Apply filters").performScrollTo().performClick()
                compose.waitUntil(15000){compose.onAllNodesWithText("No reports match these filters.").fetchSemanticsNodes().isNotEmpty()}
            }
            "map"->{
                compose.waitUntil(30000){compose.onAllNodesWithText("Quick Report").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Map").performClick()
                compose.onNodeWithText("Explore Map").assertIsDisplayed()
                compose.onNodeWithText("© OpenStreetMap contributors").assertExists()
                delay(5000)
                compose.onNodeWithText("Home").performClick()
                compose.onNodeWithText("Quick Report").assertExists()
            }
            "account-switch"->{
                val repo=com.example.pinpoint.data.PinPointRepository(backend)
                val citizen=requireNotNull(backend.auth.currentUser).id
                val installation=requireNotNull(context.getSharedPreferences("pinpoint_device",0).getString("id",null))
                repo.removeDevice(installation)
                backend.auth.signOut()
                compose.waitUntil(15000){compose.onAllNodesWithText("Sign in to your community").fetchSemanticsNodes().isNotEmpty()}
                backend.auth.signIn(requireNotNull(args.getString("official_email")),requireNotNull(args.getString("official_password")))
                assertTrue(requireNotNull(repo.citizen()).official)
                val official=requireNotNull(backend.auth.currentUser).id
                assertNotEquals(citizen,official)
                assertTrue(queue.items(official,backend.url).isEmpty())
                assertTrue(repo.pageReports(com.example.pinpoint.data.ReportFilter(mine=true),null).reports.all { it.ownerId==official })
                try {
                    repo.submit(UUID.randomUUID().toString(),ReportDraft("Road Damage","Wrong account checkpoint fixture.","Main road","Alangilan"),null,citizen)
                    fail("Another account submitted the citizen's queued report")
                } catch(expected: IllegalStateException) {
                    assertTrue(expected.message.orEmpty().contains("another account"))
                }
                val token=com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
                repo.registerDevice(installation,token)
                repo.removeDevice(installation)
                backend.auth.signOut()
                backend.auth.signIn(requireNotNull(args.getString("email")),requireNotNull(args.getString("password")))
                repo.registerDevice(installation,token)
                assertFalse(requireNotNull(repo.citizen()).official)
                compose.waitUntil(15000){compose.onAllNodesWithText("Quick Report").fetchSemanticsNodes().isNotEmpty()}
            }
            else->error("Run with an explicit checkpoint phase.")
        }
    }
}
