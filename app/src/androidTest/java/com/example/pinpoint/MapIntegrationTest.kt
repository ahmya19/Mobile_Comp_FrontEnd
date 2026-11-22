package com.example.pinpoint

import android.graphics.RectF
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pinpoint.data.*
import com.example.pinpoint.screens.MapScreen
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Local screen fixtures with native MapLibre rendering/hit-testing; no hosted writes or GPS. */
@RunWith(AndroidJUnit4::class)
class MapIntegrationTest {
    @get:Rule val compose=createComposeRule()

    @Test fun nativeMarkersFollowPagesAndCategoryFilterAndOpenTheCorrectReport() {
        val road=Report(id="map-road",category="Road Damage",description="Synthetic road marker",
            latitude=13.7565,longitude=121.0583)
        val garbage=Report(id="map-garbage",category="Garbage",description="Synthetic garbage marker",
            latitude=13.7565,longitude=121.0613)
        val excluded=listOf(Report(id="no-location"),Report(id="unpaired",latitude=13.7565),
            Report(id="nonfinite",latitude=Double.NaN,longitude=121.0),
            Report(id="out-of-range",latitude=91.0,longitude=121.0))
        val all=listOf(road,garbage)+excluded
        var state by mutableStateOf(ReportFeedState(ReportFilter(),listOf(road)+excluded,
            ReportCursor("2026-11-22T00:00:00Z","older")))
        var opened:String?=null
        var filterApplied=false
        compose.setContent {
            MapScreen({},{},{},state.reports,{opened=it},state,
                { filter -> filterApplied=true;state=ReportFeedState(filter,all.filter { it.category==filter.category }) },
                { state=state.copy(reports=all,next=null) })
        }
        val mapNode=compose.onNodeWithContentDescription("Community report map")
        mapNode.performScrollTo()
        var native:MapLibreMap?=null
        var nativeView:MapView?=null
        onView(isAssignableFrom(MapView::class.java)).perform(object:ViewAction {
            override fun getConstraints():Matcher<View> = isAssignableFrom(MapView::class.java)
            override fun getDescription()="Capture native map for renderer assertions"
            override fun perform(ui:UiController,view:View) {
                nativeView=view as MapView
                view.getMapAsync { native=it }
            }
        })
        fun rendered(expected:Set<String>) {
            compose.waitUntil(30000) {
                var actual:Set<String> = emptySet()
                compose.runOnIdle {
                    val view=nativeView
                    if(native?.style?.getLayer("reports-layer")!=null && view!=null) {
                        actual=native!!.queryRenderedFeatures(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),
                            "reports-layer").map { it.getStringProperty("id") }.toSet()
                    }
                }
                actual==expected
            }
        }
        rendered(setOf(road.id))
        compose.onNodeWithText("Load more reports on map").performScrollTo().performClick()
        mapNode.performScrollTo()
        rendered(setOf(road.id,garbage.id))
        compose.onNodeWithText("Load more reports on map").assertDoesNotExist()
        compose.onNodeWithText("Filter reports").performScrollTo().performClick()
        compose.onNodeWithText("All Category").performScrollTo().performClick()
        compose.onNodeWithText("Garbage").performClick()
        compose.onNodeWithText("Apply filters").performScrollTo().performClick()
        mapNode.performScrollTo()
        rendered(setOf(garbage.id))
        compose.runOnIdle { assertTrue(filterApplied);assertEquals("Garbage",state.filter.category) }
        var x=0f;var y=0f
        compose.runOnIdle {
            val point=native!!.projection.toScreenLocation(LatLng(garbage.latitude!!,garbage.longitude!!))
            x=point.x;y=point.y
        }
        onView(isAssignableFrom(MapView::class.java)).perform(GeneralClickAction(Tap.SINGLE,{view ->
            val location=IntArray(2);view.getLocationOnScreen(location)
            floatArrayOf(location[0]+x,location[1]+y)
        },Press.FINGER))
        compose.waitUntil(10000) { opened==garbage.id }
        compose.runOnIdle { assertEquals(garbage.id,opened) }
    }

    /** Reports at identical coordinates (e.g. SMS reports at a barangay centroid) must each stay reachable. */
    @Test fun stackedMarkersOfferAChooserSoEveryReportIsReachable() {
        val a=Report(id="stack-a",category="Road Damage",description="Stack A report",latitude=13.7565,longitude=121.0583)
        val b=Report(id="stack-b",category="Garbage",description="Stack B report",latitude=13.7565,longitude=121.0583)
        val state=ReportFeedState(ReportFilter(),listOf(a,b))
        var opened:String?=null
        compose.setContent { MapScreen({},{},{},state.reports,{opened=it},state,{},{}) }
        val mapNode=compose.onNodeWithContentDescription("Community report map")
        mapNode.performScrollTo()
        var native:MapLibreMap?=null
        var nativeView:MapView?=null
        onView(isAssignableFrom(MapView::class.java)).perform(object:ViewAction {
            override fun getConstraints():Matcher<View> = isAssignableFrom(MapView::class.java)
            override fun getDescription()="Capture native map"
            override fun perform(ui:UiController,view:View) { nativeView=view as MapView;view.getMapAsync { native=it } }
        })
        compose.waitUntil(30000) {
            var actual:Set<String> = emptySet()
            compose.runOnIdle {
                val view=nativeView
                if(native?.style?.getLayer("reports-layer")!=null && view!=null) {
                    native!!.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(a.latitude!!,a.longitude!!),15.0))
                    actual=native!!.queryRenderedFeatures(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),
                        "reports-layer").map { it.getStringProperty("id") }.toSet()
                }
            }
            actual==setOf(a.id,b.id)
        }
        var x=0f;var y=0f
        compose.runOnIdle {
            val point=native!!.projection.toScreenLocation(LatLng(a.latitude!!,a.longitude!!));x=point.x;y=point.y
        }
        onView(isAssignableFrom(MapView::class.java)).perform(GeneralClickAction(Tap.SINGLE,{ view ->
            val location=IntArray(2);view.getLocationOnScreen(location);floatArrayOf(location[0]+x,location[1]+y)
        },Press.FINGER))
        try {
            compose.waitUntil(10000) { compose.onAllNodes(hasText("2 reports at this location")).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        } catch(timeout:androidx.compose.ui.test.ComposeTimeoutException) {
            val roots=compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired=false).size
            val dump=(0 until roots).joinToString("\n----\n") { runCatching { compose.onAllNodes(isRoot())[it].printToString(4000) }.getOrDefault("?") }
            throw AssertionError("No chooser. opened=$opened roots=$roots tree=$dump",timeout)
        }
        assertNull("The chooser must appear before any report opens",opened)
        compose.onNode(hasText("Garbage: Stack B report",substring=true)).performClick()
        compose.waitUntil(10000) { opened=="stack-b" }
    }
}
