package com.example.pinpoint

import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pinpoint.data.NotificationFeed
import com.example.pinpoint.data.PinPointRepository
import com.example.pinpoint.data.SupabaseBackend
import com.example.pinpoint.screens.NotificationsScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in hosted reads/mark-read on an isolated emulator. Fixture creation stays on the host. */
@RunWith(AndroidJUnit4::class)
class HostedNotificationPagingTest {
    @get:Rule val compose=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val args=InstrumentationRegistry.getArguments()
    private var backend: SupabaseBackend?=null
    private val stage=File(context.filesDir,"cp14-notification-stage.json")
    private val oracleFile=File(context.filesDir,"cp14-notification-oracle.json")

    @After fun signOutTestSession() {
        runBlocking { backend?.takeIf { it.auth.currentUser!=null }?.auth?.signOut() }
    }

    @Test fun hostedPagesRemainContiguousAfterEqualTimestampInsertBurst()=runBlocking {
        assumeTrue("Hosted checks require explicit private runner arguments",
            args.getString("allowHostedNotificationChecks")=="true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) {
            "This hosted test is emulator-only; use the ordinary app for phone validation"
        }
        check(BuildConfig.SUPABASE_URL=="https://jaysxjjdvaitgrbqjlhe.supabase.co")
        val prefix=requireNotNull(args.getString("fixture_prefix"))
        require(prefix.matches(Regex("CP14-[0-9]{8}-[A-Z][0-9]{2}")))
        val report=UUID.fromString(requireNotNull(args.getString("fixture_report"))).toString()
        val owner=UUID.fromString(requireNotNull(args.getString("citizen_uid"))).toString()
        stage.delete();oracleFile.delete()
        val configured=requireNotNull(SupabaseBackend.create(context)).also { backend=it }
        configured.auth.signIn(requireNotNull(args.getString("citizen_email")),
            requireNotNull(args.getString("citizen_password")))
        assertEquals("Expected demo citizen",owner,configured.auth.currentUser?.id)
        val repository=PinPointRepository(configured)
        val feed=NotificationFeed({ repository.pageNotifications(it) },repository::markRead)
        feed.refresh()
        assertNull(feed.state.value.error)
        assertEquals(50,feed.state.value.items.size)
        assertNotNull(feed.state.value.next)
        feed.loadMore()
        assertNull(feed.state.value.error)
        assertEquals(100,feed.state.value.items.size)
        assertEquals(100,feed.state.value.items.count { it.message.startsWith(prefix) })
        val before=feed.state.value.items.map { it.id }
        stage.writeText(JSONObject().put("phase","ready-for-burst").put("prefix",prefix)
            .put("report",report).put("loaded_ids",JSONArray(before)).toString())

        // The host inserts the burst and atomically supplies an independent SQL ordering snapshot.
        withTimeout(180_000) { while(!oracleFile.isFile) delay(250) }
        val oracle=JSONObject(oracleFile.readText())
        assertEquals(prefix,oracle.getString("prefix"))
        assertEquals(report,oracle.getString("report"))
        fun ids(key:String)=oracle.getJSONArray(key).let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        assertEquals("Initial two pages must match pre-burst SQL order",ids("before_first_100"),before)
        feed.refresh()
        assertNull(feed.state.value.error)
        assertEquals("Refresh must retain a contiguous loaded window",ids("after_first_100"),
            feed.state.value.items.map { it.id })
        var morePages=0
        while(feed.state.value.next!=null) {
            check(++morePages<=8) { "Unexpected hosted data volume; stop bounded test" }
            feed.loadMore()
            assertNull(feed.state.value.error)
        }
        val complete=feed.state.value.items
        assertEquals("All pages must match hosted SQL ordering",ids("all_ids"),complete.map { it.id })
        assertEquals(complete.size,complete.map { it.id }.distinct().size)
        assertEquals(134,complete.count { it.message.startsWith(prefix) })
        val target=complete.single { it.message=="$prefix notice 002 (synthetic paging fixture)" }
        assertEquals(report,target.reportId)
        assertFalse(target.read)
        var opened:String?=null
        var readJob:Job?=null
        compose.setContent {
            val state by feed.state.collectAsState()
            val scope=rememberCoroutineScope()
            NotificationsScreen({},state.items,state.next!=null,state.loadingMore,
                {scope.launch { feed.loadMore() }},{ notice ->
                    opened=notice.reportId
                    readJob=scope.launch { feed.markRead(notice.id) }
                })
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("New: ${target.message}"))
        compose.onNodeWithText("New: ${target.message}").performClick()
        compose.waitUntil(10_000) { readJob!=null }
        requireNotNull(readJob).join()
        assertNull(feed.state.value.error)
        assertEquals(report,opened)
        val persisted=configured.json("rest/v1/notifications?id=eq.${target.id}&select=id,read")
            .asJsonArray.single().asJsonObject
        assertTrue("Read RPC must be acknowledged by hosted data",persisted["read"].asBoolean)
        feed.refresh()
        assertNull(feed.state.value.error)
        assertTrue(feed.state.value.items.single { it.id==target.id }.read)
        compose.onNodeWithText(target.message).assertIsDisplayed()
        stage.writeText(JSONObject().put("phase","completed").put("prefix",prefix)
            .put("total_loaded",complete.size).put("fixture_count",134)
            .put("no_gaps_or_duplicates",true).put("hosted_read_persisted",true)
            .put("component_opened_expected_report",true).toString())
    }
}
