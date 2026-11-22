package com.example.pinpoint

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import com.example.pinpoint.data.*
import com.example.pinpoint.screens.NotificationsScreen
import com.example.pinpoint.screens.ReportScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView

/** Local fixtures only: no Supabase sign-in, report submission or notification writes. */
@RunWith(AndroidJUnit4::class)
class NotificationAndMapUiTest {
    @get:Rule val compose=createComposeRule()

    @Test fun olderNotificationsLoadAndOpenTheCorrectReport() {
        var rows=(123 downTo 1).map { index -> ReportNotification(index.toString(),"report-$index",
            "Notice $index",false,"2026-11-21T00:00:00Z") }
        var opened:String?=null
        val feed=NotificationFeed({ cursor ->
            val remaining=rows.filter { cursor==null || it.id.toInt()<cursor.id.toInt() }
            val items=remaining.take(50)
            NotificationPage(items,if(remaining.size>50) ReportCursor(items.last().createdAt,items.last().id) else null)
        },{ id -> rows=rows.map { if(it.id==id) it.copy(read=true) else it } })
        runBlocking { feed.refresh() }
        compose.setContent {
            val state by feed.state.collectAsState()
            val scope=rememberCoroutineScope()
            NotificationsScreen({},state.items,state.next!=null,state.loadingMore,
                {scope.launch {feed.loadMore()}},{notice -> opened=notice.reportId;scope.launch {feed.markRead(notice.id)} })
        }
        repeat(2) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Load older notifications"))
            compose.onNodeWithText("Load older notifications").performClick()
            val expected=if(it==0) 100 else 123
            compose.waitUntil(10000) { feed.state.value.items.size==expected }
        }
        assertNull(feed.state.value.next)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("New: Notice 2"))
        compose.onNodeWithText("New: Notice 2").performClick()
        compose.waitUntil(10000) { feed.state.value.items.single { it.id=="2" }.read }
        compose.onNodeWithText("Notice 2").assertIsDisplayed()
        compose.runOnIdle { assertEquals("report-2",opened) }
        compose.onNodeWithText("Load older notifications").assertDoesNotExist()
    }

    @Test fun longPressOnFormMapUpdatesBothCoordinateFields() {
        var submitted=false
        compose.setContent { ReportScreen({},false,{_,_,_ -> submitted=true}) }
        compose.onNodeWithText("Choose or adjust map pin").performScrollTo().performClick()
        val map=compose.onNodeWithContentDescription("Choose report location on map")
        map.performScrollTo()
        compose.waitUntil(30000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Map ready"))
                .fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
        }
        // MapLibre uses Android's real-time GestureDetector, not Compose's test clock.
        onView(isAssignableFrom(MapView::class.java)).perform(longClick())
        fun coordinate(label: String): Double?=compose.onNodeWithText(label).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text.toDoubleOrNull()
        compose.waitUntil(10000) { coordinate("Latitude (optional)")!=null && coordinate("Longitude (optional)")!=null }
        val latitude=requireNotNull(coordinate("Latitude (optional)"))
        val longitude=requireNotNull(coordinate("Longitude (optional)"))
        assertTrue(latitude.isFinite() && latitude in 13.5..14.0)
        assertTrue(longitude.isFinite() && longitude in 120.5..121.5)
        assertFalse(submitted)
    }
}
