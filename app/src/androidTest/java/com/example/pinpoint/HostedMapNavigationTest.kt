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
import com.example.pinpoint.data.SupabaseBackend
import com.example.pinpoint.data.PinPointRepository
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
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

/**
 * Opt-in whole-app check against the hosted project: real authenticated feed data, native map
 * markers, "load more", category filter and a marker tap that must open the right report through
 * AppNavigation. Expected IDs come from an independent privileged SQL oracle written by the host
 * driver (backend/scripts/map_nav_hosted.py); this test only performs ordinary authenticated reads.
 */
@RunWith(AndroidJUnit4::class)
class HostedMapNavigationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val args=InstrumentationRegistry.getArguments()
    private var backend:SupabaseBackend?=null
    private var native:MapLibreMap?=null
    private var nativeView:MapView?=null

    @After fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            listOf(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.PAUSED,
                androidx.test.runner.lifecycle.Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it).toList() }
                .distinct().forEach { it.finish() }
        }
        runBlocking { backend?.takeIf { it.auth.currentUser!=null }?.let { configured ->
            val uid=requireNotNull(configured.auth.currentUser).id
            try {
                WorkManager.getInstance(context).cancelUniqueWork("pinpoint-device-$uid").result.get(15,TimeUnit.SECONDS)
                context.getSharedPreferences("pinpoint_device",0).getString("id",null)?.let {
                    PinPointRepository(configured).removeDevice(it)
                }
            } finally { configured.auth.signOut() }
        } }
    }

    /** println does not reach the instrumentation output; sendStatus does, so IDs land in the evidence log. */
    private fun note(message:String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        android.os.Bundle().apply { putString("stream",message+"\n") })

    private fun strings(array:org.json.JSONArray)=(0 until array.length()).map { array.getString(it) }.toSet()

    @Test fun hostedFeedFiltersMarkersAndTapOpenTheCorrectReport()=runBlocking {
        assumeTrue("Hosted map checks require explicit opt-in",args.getString("allowHostedMapChecks")=="true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) { "Use an isolated emulator" }
        check(BuildConfig.SUPABASE_URL=="https://jaysxjjdvaitgrbqjlhe.supabase.co")
        val oracle=JSONObject(File(context.filesDir,"map-nav-oracle.json").readText())
        val coords=oracle.getJSONObject("coords")
        fun point(id:String)=coords.getJSONArray(id).let { LatLng(it.getDouble(0),it.getDouble(1)) }
        val page1=strings(oracle.getJSONArray("page1"));val all=strings(oracle.getJSONArray("all"))
        val filtered=strings(oracle.getJSONObject("filter").getJSONArray("ids"))
        val filterName=oracle.getJSONObject("filter").getString("category")
        val road=oracle.getJSONObject("road");val garbage=oracle.getJSONObject("garbage")
        assertTrue(page1.size in 1..all.size)

        InstrumentationRegistry.getInstrumentation().startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val configured=requireNotNull(SupabaseBackend.create(context)).also { backend=it }
        configured.auth.signIn(requireNotNull(args.getString("citizen_email")),requireNotNull(args.getString("citizen_password")))
        assertEquals(oracle.getString("citizen_uid"),configured.auth.currentUser?.id)
        compose.waitUntil(30000) { compose.onAllNodesWithText("Quick Report").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        compose.onNodeWithText("Map").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Explore Map").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        val mapNode=compose.onNodeWithContentDescription("Community report map")

        fun captureMap() {
            mapNode.performScrollTo()
            onView(isAssignableFrom(MapView::class.java)).perform(object:ViewAction {
                override fun getConstraints():Matcher<View> = isAssignableFrom(MapView::class.java)
                override fun getDescription()="Capture native map"
                override fun perform(ui:UiController,view:View) { nativeView=view as MapView;view.getMapAsync { native=it } }
            })
        }
        captureMap()
        compose.waitUntil(30000) { native?.style?.getLayer("reports-layer")!=null }

        /** Bring every expected marker into the viewport, then compare rendered feature IDs exactly. */
        fun assertRendered(label:String,expected:Set<String>) {
            var last:Set<String> = emptySet()
            try {
                compose.waitUntil(30000) {
                    compose.runOnUiThread {
                        val map=native!!
                        map.cancelTransitions()
                        val pts=expected.map { point(it) }
                        if(pts.size==1) map.moveCamera(CameraUpdateFactory.newLatLngZoom(pts.first(),14.0))
                        else if(pts.isNotEmpty()) map.moveCamera(CameraUpdateFactory.newLatLngBounds(
                            LatLngBounds.Builder().includes(pts).build(),60))
                        val view=nativeView!!
                        last=map.queryRenderedFeatures(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),"reports-layer")
                            .map { it.getStringProperty("id") }.toSet()
                    }
                    last==expected
                }
            } catch(timeout:ComposeTimeoutException) {
                fail("$label: rendered ${last.size} IDs, expected ${expected.size}. Missing=${expected-last} Unexpected=${last-expected}")
            }
        }
        fun tapAndExpectDetails(label:String,target:JSONObject) {
            val id=target.getString("id")
            captureMap()
            compose.runOnUiThread {
                native!!.cancelTransitions()
                native!!.moveCamera(CameraUpdateFactory.newLatLngZoom(point(id),16.0))
            }
            var x=0f;var y=0f
            compose.waitUntil(20000) {
                var hit=false
                compose.runOnUiThread {
                    val p=native!!.projection.toScreenLocation(point(id));x=p.x;y=p.y
                    hit=native!!.queryRenderedFeatures(p,"reports-layer").map { it.getStringProperty("id") }==listOf(id)
                }
                hit
            }
            onView(isAssignableFrom(MapView::class.java)).perform(GeneralClickAction(Tap.SINGLE,{ view ->
                val location=IntArray(2);view.getLocationOnScreen(location);floatArrayOf(location[0]+x,location[1]+y)
            },Press.FINGER))
            compose.waitUntil(20000) { compose.onAllNodesWithText("Report Details").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            // The details screen does not show the ID, so match the unique fixture fields that the oracle ties to that ID.
            compose.waitUntil(20000) {
                compose.onAllNodesWithText("Description: ${target.getString("description")}").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
            }
            compose.onNodeWithText("Category: ${target.getString("category")}").assertExists()
            compose.onNodeWithText("Coordinates: ${target.getDouble("latitude")}, ${target.getDouble("longitude")}").assertExists()
            note("CP_MAP_DETAILS_OK=$label id=${target.getString("id")}")
            pressBack()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Explore Map").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        }

        assertRendered("first page",page1)
        note("CP_MAP_PAGE1_COUNT=${page1.size} IDS=${page1.sorted()}")
        tapAndExpectDetails("road marker on first page",road)

        if(all!=page1) {
            captureMap()
            compose.onNodeWithText("Load more reports on map").performScrollTo().performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("Load more reports on map").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty() }
            captureMap()
            assertRendered("after load more",all)
            note("CP_MAP_ALL_COUNT=${all.size} IDS=${all.sorted()}")
        }

        if(oracle.has("stack")) {
            // Several real reports share one coordinate: the tap must offer a chooser, and each report must be reachable.
            val stack=oracle.getJSONObject("stack");val pick=stack.getJSONObject("pick")
            val stackIds=strings(stack.getJSONArray("ids"));val at=LatLng(stack.getDouble("latitude"),stack.getDouble("longitude"))
            captureMap()
            compose.runOnUiThread { native!!.cancelTransitions();native!!.moveCamera(CameraUpdateFactory.newLatLngZoom(at,16.0)) }
            var sx=0f;var sy=0f
            compose.waitUntil(20000) {
                var same=false
                compose.runOnUiThread {
                    val p=native!!.projection.toScreenLocation(at);sx=p.x;sy=p.y
                    same=native!!.queryRenderedFeatures(p,"reports-layer").map { it.getStringProperty("id") }.toSet()==stackIds
                }
                same
            }
            onView(isAssignableFrom(MapView::class.java)).perform(GeneralClickAction(Tap.SINGLE,{ view ->
                val location=IntArray(2);view.getLocationOnScreen(location);floatArrayOf(location[0]+sx,location[1]+sy)
            },Press.FINGER))
            compose.waitUntil(20000) { compose.onAllNodesWithText("${stackIds.size} reports at this location").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            note("CP_MAP_STACK_CHOOSER count=${stackIds.size} ids=${stackIds.sorted()}")
            compose.onNode(hasText(pick.getString("description").take(40),substring=true)).performClick()
            compose.waitUntil(20000) { compose.onAllNodesWithText("Description: ${pick.getString("description")}").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            compose.onNodeWithText("Category: ${pick.getString("category")}").assertExists()
            compose.onNodeWithText("Coordinates: ${pick.getDouble("latitude")}, ${pick.getDouble("longitude")}").assertExists()
            note("CP_MAP_STACK_PICK_OK id=${pick.getString("id")}")
            pressBack()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Explore Map").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        }

        compose.onNodeWithText("Filter reports").performScrollTo().performClick()
        compose.onNodeWithText("All Category").performScrollTo().performClick()
        compose.onNodeWithText(filterName).performClick()
        compose.onNodeWithText("Apply filters").performScrollTo().performClick()
        captureMap()
        compose.waitUntil(30000) { native?.style?.getLayer("reports-layer")!=null }
        assertRendered("category filter $filterName",filtered)
        note("CP_MAP_FILTER_$filterName COUNT=${filtered.size} IDS=${filtered.sorted()}")
        tapAndExpectDetails("garbage marker after filter",garbage)
        assertEquals(oracle.getString("citizen_uid"),configured.auth.currentUser?.id)
    }
}
