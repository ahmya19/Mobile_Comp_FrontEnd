package com.example.pinpoint

import android.content.Intent
import android.graphics.RectF
import android.os.Build
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.example.pinpoint.data.PinPointRepository
import com.example.pinpoint.data.SupabaseBackend
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File
import java.util.concurrent.TimeUnit

/** Ordinary official session, existing hosted reports and independent host SQL oracle. No report mutations. */
@RunWith(AndroidJUnit4::class)
class HostedOfficialMapTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private var backend:SupabaseBackend?=null
    private val result=File(context.filesDir,"official-map-result.json")
    private var native:MapLibreMap?=null
    private var nativeView:MapView?=null

    @After fun cleanUp() {
        instrumentation.runOnMainSync {
            val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            listOf(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.PAUSED,
                androidx.test.runner.lifecycle.Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it).toList() }
                .distinct().forEach { it.finish() }
        }
        runBlocking {
            backend?.takeIf { it.auth.currentUser!=null }?.let { configured ->
                val uid=requireNotNull(configured.auth.currentUser).id
                WorkManager.getInstance(context).cancelUniqueWork("pinpoint-device-$uid").result.get(15,TimeUnit.SECONDS)
                try {
                    context.getSharedPreferences("pinpoint_device",0).getString("id",null)?.let {
                        PinPointRepository(configured).removeDevice(it)
                    }
                } finally { configured.auth.signOut() } // local scope preserves other account/device sessions
                if(result.isFile) result.writeText(JSONObject(result.readText())
                    .put("session_cleanup_complete",true).toString())
            }
        }
    }

    private fun ids(array:JSONArray)=(0 until array.length()).map { array.getString(it) }.toSet()
    private fun note(message:String)=instrumentation.sendStatus(0,android.os.Bundle().apply { putString("stream",message+"\n") })
    private fun exists(text:String)=compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
    private fun captureMap() {
        compose.onNodeWithContentDescription("Community report map").performScrollTo()
        onView(isAssignableFrom(MapView::class.java)).perform(object:ViewAction {
            override fun getConstraints():Matcher<View> = isAssignableFrom(MapView::class.java)
            override fun getDescription()="Capture official map"
            override fun perform(ui:UiController,view:View) { nativeView=view as MapView;view.getMapAsync { native=it } }
        })
        compose.waitUntil(30000) {
            var ready=false
            compose.runOnIdle { ready=native?.style?.getLayer("reports-layer")!=null }
            ready
        }
    }

    @Test fun officialHostedMapAndDetailsRespectTheAssignedBarangay()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Official hosted checks require opt-in",args.getString("allowOfficialMapChecks")=="true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) { "Use an isolated emulator" }
        check(BuildConfig.SUPABASE_URL=="https://jaysxjjdvaitgrbqjlhe.supabase.co")
        val oracle=JSONObject(File(context.filesDir,"official-map-oracle.json").readText())
        require(oracle.getInt("schema")==1 && oracle.getString("project_url")==BuildConfig.SUPABASE_URL)
        require(oracle.getInt("total_reports") in 26..50) { "This existing-data scenario expects two report pages" }
        result.delete()
        val configured=requireNotNull(SupabaseBackend.create(context)).also { backend=it }
        configured.auth.signIn(requireNotNull(args.getString("official_email")),requireNotNull(args.getString("official_password")))
        assertEquals(oracle.getString("official_uid"),configured.auth.currentUser?.id)
        val official=requireNotNull(PinPointRepository(configured).citizen())
        assertTrue("The server profile must identify an official",official.official)
        assertEquals(oracle.getString("official_barangay"),official.barangay)
        note("OFFICIAL_ROLE_VERIFIED")
        instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        compose.waitUntil(30000) { exists("Welcome back to PinPoint.") }
        compose.onNodeWithText("Map").performClick()
        compose.waitUntil(15000) { exists("Explore Map") }
        captureMap()
        val coords=oracle.getJSONObject("coords")
        fun point(id:String)=coords.getJSONArray(id).let { LatLng(it.getDouble(0),it.getDouble(1)) }
        fun rendered(label:String,expected:Set<String>) {
            require(expected.isNotEmpty())
            var actual:Set<String> = emptySet()
            try {
                compose.waitUntil(30000) {
                    compose.runOnIdle {
                        val map=native!!;map.cancelTransitions()
                        val positions=expected.map(::point)
                        if(positions.size==1) map.moveCamera(CameraUpdateFactory.newLatLngZoom(positions.single(),16.0))
                        else map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(positions).build(),60))
                        val view=nativeView!!
                        actual=map.queryRenderedFeatures(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),"reports-layer")
                            .map { it.getStringProperty("id") }.toSet()
                    }
                    actual==expected
                }
            } catch(timeout:ComposeTimeoutException) { fail("$label marker IDs differ: missing=${expected-actual}, extra=${actual-expected}") }
            note("OFFICIAL_MAP_${label.uppercase()}_COUNT=${expected.size}")
        }
        fun tap(target:JSONObject,expectedHits:Set<String>) {
            captureMap()
            compose.runOnIdle { native!!.cancelTransitions();native!!.moveCamera(CameraUpdateFactory.newLatLngZoom(point(target.getString("id")),16.0)) }
            var x=0f;var y=0f
            compose.waitUntil(20000) {
                var matches=false
                compose.runOnIdle {
                    val p=native!!.projection.toScreenLocation(point(target.getString("id")));x=p.x;y=p.y
                    matches=native!!.queryRenderedFeatures(p,"reports-layer").map { it.getStringProperty("id") }.toSet()==expectedHits
                }
                matches
            }
            onView(isAssignableFrom(MapView::class.java)).perform(GeneralClickAction(Tap.SINGLE,{view ->
                val location=IntArray(2);view.getLocationOnScreen(location);floatArrayOf(location[0]+x,location[1]+y)
            },Press.FINGER))
        }
        fun details(target:JSONObject,assigned:Boolean) {
            compose.waitUntil(20000) { exists("Report Details") && exists("Description: ${target.getString("description")}") }
            assertTrue("Category must match the independent oracle",exists("Category: ${target.getString("category")}"))
            assertTrue("Coordinates must match the independent oracle",exists("Coordinates: ${target.getDouble("latitude")}, ${target.getDouble("longitude")}"))
            if(assigned) {
                compose.waitUntil(15000) { exists("Official controls") }
                compose.onNodeWithText("Save status").performScrollTo().assertIsDisplayed()
            } else {
                compose.onNodeWithText("Official controls").assertDoesNotExist()
                compose.onNodeWithText("Save status").assertDoesNotExist()
            }
            note("OFFICIAL_DETAILS_OK id=${target.getString("id")} assigned=$assigned")
            pressBack()
            compose.waitUntil(15000) { exists("Explore Map") }
        }
        val page1=ids(oracle.getJSONArray("page1"));val all=ids(oracle.getJSONArray("all"))
        require(all.size>page1.size)
        rendered("page1",page1)
        compose.onNodeWithText("Load more reports on map").performScrollTo().performClick()
        compose.waitUntil(30000) { !exists("Load more reports on map") }
        captureMap();rendered("all",all)
        val stack=oracle.getJSONObject("stack");val pick=stack.getJSONObject("pick")
        assertEquals(official.barangay,pick.getString("barangay"))
        val stackIds=ids(stack.getJSONArray("ids"))
        require(stackIds.size>1)
        tap(pick,stackIds)
        compose.waitUntil(15000) { exists("${stackIds.size} reports at this location") }
        val choice=hasText("${pick.getString("category")}: ${pick.getString("description").take(60)} (${pick.getString("status")})")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(choice)
        compose.onNode(choice).performClick()
        details(pick,true)
        val filter=oracle.getJSONObject("filter");val category=filter.getString("category")
        compose.onNodeWithText("Filter reports").performScrollTo().performClick()
        compose.onNodeWithText("All Category").performScrollTo().performClick()
        compose.onNodeWithText(category).performClick()
        compose.onNodeWithText("Apply filters").performScrollTo().performClick()
        captureMap();rendered("filtered",ids(filter.getJSONArray("ids")))
        val other=filter.getJSONObject("target")
        assertNotEquals(official.barangay,other.getString("barangay"))
        tap(other,setOf(other.getString("id")))
        details(other,false)
        assertEquals(oracle.getString("official_uid"),configured.auth.currentUser?.id)
        result.writeText(JSONObject().put("phase","passed").put("official_role",true)
            .put("page1_count",page1.size).put("all_count",all.size).put("stack_count",stackIds.size)
            .put("filtered_count",filter.getJSONArray("ids").length()).put("assigned_controls_visible",true)
            .put("other_barangay_controls_hidden",true).put("no_business_mutations_requested",true).toString())
    }
}
