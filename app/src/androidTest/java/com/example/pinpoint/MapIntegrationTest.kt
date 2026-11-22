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
}
