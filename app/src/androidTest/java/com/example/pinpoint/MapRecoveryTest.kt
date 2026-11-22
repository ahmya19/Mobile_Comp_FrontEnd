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
import com.example.pinpoint.screens.MapTiles
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Map error/recovery behavior with native MapLibre. Local fixtures and a loopback tile server only: public tile and
 *  API services are never stressed. Does not claim offline tiles (only an HTTP cache of visited tiles exists). */
@RunWith(AndroidJUnit4::class)
class MapRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private var server: FakeSupabaseServer? = null
    @After fun cleanup() { MapTiles.overrideForTesting(null); server?.close() }

    private val road = Report(id = "rec-road", category = "Road Damage", description = "Recovery road", latitude = 13.7565, longitude = 121.0583)
    private val garbage = Report(id = "rec-garbage", category = "Garbage", description = "Recovery garbage", latitude = 13.7565, longitude = 121.0613)
    private val water = Report(id = "rec-water", category = "Water Interruption", description = "Recovery water", latitude = 13.7565, longitude = 121.0598)

    private var native: MapLibreMap? = null
    private var nativeView: MapView? = null
    private fun captureMap() {
        compose.onNodeWithContentDescription("Community report map").performScrollTo()
        onView(isAssignableFrom(MapView::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(MapView::class.java)
            override fun getDescription() = "Capture native map"
            override fun perform(ui: UiController, view: View) { nativeView = view as MapView; view.getMapAsync { native = it } }
        })
    }
    private fun rendered(expected: Set<String>) = compose.waitUntil(30000) {
        var actual: Set<String> = emptySet()
        compose.runOnIdle {
            val view = nativeView
            if (native?.style?.getLayer("reports-layer") != null && view != null)
                actual = native!!.queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), "reports-layer").map { it.getStringProperty("id") }.toSet()
        }
        actual == expected
    }

    /** The first framing eases the camera for ~600 ms; tap coordinates are only meaningful once it stops moving. */
    private fun settle() {
        var last: String? = null; var stable = 0
        compose.waitUntil(15000) {
            val now = compose.runOnIdle { native!!.cameraPosition.toString() }
            if (now == last) stable++ else { stable = 0; last = now }
            Thread.sleep(250); stable >= 4
        }
    }

    @Test fun feedErrorKeepsLoadedMarkersAndRetryRecoversToExactlyTheServerReports() {
        var state by mutableStateOf(ReportFeedState(ReportFilter(), listOf(road, garbage), error = "BackendException: HTTP 503"))
        var retried = 0
        compose.setContent {
            MapScreen({}, {}, {}, state.reports, {}, state, { filter ->
                retried++; assertEquals("retry must reuse the current filter", state.filter, filter)
                state = ReportFeedState(filter, listOf(garbage, water))
            }, {})
        }
        captureMap()
        rendered(setOf(road.id, garbage.id))                        // loaded reports stay on the map during the error
        compose.onNodeWithText("BackendException: HTTP 503").assertExists()
        compose.onNodeWithText("Retry loading reports").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retried) }
        compose.onNodeWithText("Retry loading reports").assertDoesNotExist()
        compose.onNodeWithText("BackendException: HTTP 503").assertDoesNotExist()
        captureMap(); rendered(setOf(garbage.id, water.id))         // road is gone: no stale marker after recovery
    }

    @Test fun retryIsDisabledWhileALoadIsInFlight() {
        val state = ReportFeedState(ReportFilter(), listOf(road), loading = true, error = "BackendException: HTTP 503")
        compose.setContent { MapScreen({}, {}, {}, state.reports, {}, state, {}, {}) }
        compose.onNodeWithText("Retry loading reports").performScrollTo().assertIsNotEnabled()
    }

    @Test fun markersStayRenderedAndSelectableWhenTilesAreUnavailable() {
        server = FakeSupabaseServer()                                   // answers 404 for every tile request
        MapTiles.overrideForTesting("${server!!.url}/{z}/{x}/{y}.png")
        val state = ReportFeedState(ReportFilter(), listOf(road, garbage))
        var opened: String? = null
        compose.setContent { MapScreen({}, {}, {}, state.reports, { opened = it }, state, {}, {}) }
        captureMap()
        rendered(setOf(road.id, garbage.id))
        compose.waitUntil(20000) { server!!.requests.any { it.method == "GET" && it.path.endsWith(".png") } }   // the basemap really tried the dead source
        settle()
        var x = 0f; var y = 0f
        compose.runOnIdle { val p = native!!.projection.toScreenLocation(LatLng(garbage.latitude!!, garbage.longitude!!)); x = p.x; y = p.y }
        onView(isAssignableFrom(MapView::class.java)).perform(GeneralClickAction(Tap.SINGLE, { view ->
            val l = IntArray(2); view.getLocationOnScreen(l); floatArrayOf(l[0] + x, l[1] + y) }, Press.FINGER))
        compose.waitUntil(10000) { opened == garbage.id }
        assertEquals(garbage.id, opened)
    }
}
