package com.example.pinpoint

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pinpoint.FakeSupabaseServer.Row
import com.example.pinpoint.FakeSupabaseServer.Rpc
import com.example.pinpoint.data.PinPointRepository
import com.example.pinpoint.data.ReportFeed
import com.example.pinpoint.data.ReportFilter
import com.example.pinpoint.data.SupabaseBackend
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Read-path recovery of the report feed (what the map and lists render) against a loopback fake server. Local fault
 *  injection only: no hosted request, no public tile/API stress. Expected IDs come from the seeded rows, not the app. */
@RunWith(AndroidJUnit4::class)
class FeedRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: FakeSupabaseServer
    private lateinit var backend: SupabaseBackend
    private lateinit var feed: ReportFeed
    private val owner get() = server.userId("reader@feed.test")

    /** 30 rows, newest first by created_at; every third one is Resolved. */
    private fun seed() {
        for (i in 0 until 30) server.reportRows += Row("00000000-0000-4000-8000-%012d".format(i), owner, "Road Damage",
            if (i % 3 == 0) "Resolved" else "Pending", "2026-11-%02dT10:00:00.000Z".format(i + 1), "report $i", 13.7 + i / 1000.0, 121.0)
    }
    private fun expected(status: String? = null) = server.reportRows.filter { status == null || it.status == status }
        .sortedWith(compareByDescending<Row> { it.createdAt }.thenByDescending { it.id }).map { it.id }

    @Before fun setup() {
        server = FakeSupabaseServer(); backend = SupabaseBackend.installForTesting(context, server.url, "sb_publishable_feed_test")
        runBlocking { backend.auth.signIn("reader@feed.test", "not-a-real-password") }
        seed(); feed = ReportFeed(PinPointRepository(backend), false)
    }
    @After fun cleanup() { runCatching { runBlocking { backend.auth.signOut() } }; SupabaseBackend.resetForTesting(); server.close() }

    @Test fun failedRefreshKeepsLoadedReportsAndRetryRecoversTheSameIdsWithoutDuplicates() = runBlocking {
        feed.refresh(); assertEquals(expected().take(25), feed.state.value.reports.map { it.id }); assertNotNull(feed.state.value.next)
        for (fault in listOf(Rpc.SERVER_500, Rpc.HTML_200, Rpc.DROP_BEFORE_COMMIT)) {
            server.readBehaviors.add(fault)
            feed.refresh()
            val failed = feed.state.value
            assertNotNull("$fault must surface an error", failed.error); assertFalse(failed.loading)
            assertEquals("$fault must keep the loaded reports", expected().take(25), failed.reports.map { it.id })
        }
        feed.refresh()
        val ok = feed.state.value
        assertNull(ok.error); assertEquals(expected().take(25), ok.reports.map { it.id }); assertEquals(ok.reports.size, ok.reports.map { it.id }.toSet().size)
    }

    @Test fun failedFirstLoadShowsAnErrorAndAnEmptyListThenRecovers() = runBlocking {
        server.readBehaviors.add(Rpc.SERVER_500)
        feed.refresh(); assertTrue(feed.state.value.reports.isEmpty()); assertNotNull(feed.state.value.error)
        feed.refresh(); assertNull(feed.state.value.error); assertEquals(expected().take(25), feed.state.value.reports.map { it.id })
    }

    @Test fun failedLoadMoreKeepsThePageAndCursorAndRetryAddsOlderRowsOnce() = runBlocking {
        feed.refresh(); val firstCursor = feed.state.value.next
        server.readBehaviors.add(Rpc.SERVER_500)
        feed.loadMore()
        assertNotNull(feed.state.value.error); assertEquals(expected().take(25), feed.state.value.reports.map { it.id }); assertEquals(firstCursor, feed.state.value.next)
        feed.loadMore()
        assertNull(feed.state.value.error); assertEquals(expected(), feed.state.value.reports.map { it.id }); assertNull(feed.state.value.next)
    }

    @Test fun aSlowOlderResponseCannotOverwriteANewerFilter() = runBlocking {
        feed.refresh()
        server.readBehaviors.add(Rpc.HOLD_THEN_OK)
        val slow = async(Dispatchers.IO) { feed.refresh() }
        withContext(Dispatchers.IO) { assertTrue(server.readEntered.await(15, java.util.concurrent.TimeUnit.SECONDS)) }
        val filtered = async(Dispatchers.IO) { feed.refresh(ReportFilter(status = "Resolved")) }
        server.releaseRead.countDown()
        slow.await(); filtered.await()
        val state = feed.state.value
        assertEquals("Resolved", state.filter.status); assertEquals(expected("Resolved"), state.reports.map { it.id })
        assertTrue(state.reports.all { it.status == "Resolved" }); assertNull(state.error); assertFalse(state.loading)
    }

    @Test fun cancellingARefreshLeavesTheFeedUsable() = runBlocking {
        feed.refresh()
        server.readBehaviors.add(Rpc.HOLD_THEN_OK)
        val job = launch(Dispatchers.IO) { feed.refresh() }
        withContext(Dispatchers.IO) { assertTrue(server.readEntered.await(15, java.util.concurrent.TimeUnit.SECONDS)) }
        job.cancel(); server.releaseRead.countDown(); job.join()
        feed.refresh()
        val state = feed.state.value
        assertFalse("loading must not stay stuck after a cancelled refresh", state.loading)
        assertEquals(expected().take(25), state.reports.map { it.id })
    }
}
