package com.example.pinpoint

import com.example.pinpoint.data.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class NotificationFeedTest {
    private fun notice(number: Int)=ReportNotification(
        "00000000-0000-0000-0000-${number.toString().padStart(12,'0')}","report-$number",
        "Notice $number",false,"2026-11-21T00:00:00Z")

    private class Source(var rows: List<ReportNotification>) {
        var calls=0
        var fail=false
        suspend fun page(cursor: ReportCursor?): NotificationPage {
            calls++
            if(fail) throw IOException("Connection interrupted")
            val remaining=rows.sortedByDescending { it.id }.filter {
                cursor==null || it.createdAt<cursor.createdAt || (it.createdAt==cursor.createdAt && it.id<cursor.id)
            }
            val selected=remaining.take(50)
            return NotificationPage(selected,if(remaining.size>50) selected.last().let { ReportCursor(it.createdAt,it.id) } else null)
        }
        suspend fun read(id: String) {
            if(fail) throw IOException("Read acknowledgement failed")
            rows=rows.map { if(it.id==id) it.copy(read=true) else it }
        }
    }

    @Test fun equalTimestampPagesReachEveryNoticeExactlyOnce(): Unit=runBlocking {
        val source=Source((1..123).map(::notice))
        val feed=NotificationFeed(source::page,source::read)
        feed.refresh();feed.loadMore();feed.loadMore()
        assertEquals((123 downTo 1).map { notice(it).id },feed.state.value.items.map { it.id })
        assertNull(feed.state.value.next)
        feed.loadMore()
        assertEquals(3,source.calls)
    }

    @Test fun newHeadCannotDropNoticesBetweenLoadedPages(): Unit=runBlocking {
        val source=Source((1..120).map(::notice))
        val feed=NotificationFeed(source::page,source::read)
        feed.refresh();feed.loadMore()
        source.rows+=(121..130).map(::notice)
        feed.refresh()
        assertEquals((130 downTo 31).map { notice(it).id },feed.state.value.items.map { it.id })
        feed.loadMore()
        assertEquals((130 downTo 1).map { notice(it).id },feed.state.value.items.map { it.id })
        assertNull(feed.state.value.next)
    }

    @Test fun burstLargerThanOnePageStillHasNoGap(): Unit=runBlocking {
        val source=Source((1..120).map(::notice))
        val feed=NotificationFeed(source::page,source::read)
        feed.refresh();feed.loadMore()
        source.rows+=(121..180).map(::notice)
        feed.refresh();feed.loadMore();feed.loadMore()
        assertEquals((180 downTo 1).map { notice(it).id },feed.state.value.items.map { it.id })
    }

    @Test fun failedRefreshOrPageKeepsLoadedItemsAndRetryCursor(): Unit=runBlocking {
        val source=Source((1..120).map(::notice))
        val feed=NotificationFeed(source::page,source::read)
        feed.refresh()
        val before=feed.state.value
        source.fail=true
        feed.loadMore();feed.refresh()
        assertEquals(before.items,feed.state.value.items)
        assertEquals(before.next,feed.state.value.next)
        assertFalse(feed.state.value.loadingMore)
        assertEquals("IOException: Connection interrupted",feed.state.value.error)
        source.fail=false
        feed.loadMore()
        assertEquals(100,feed.state.value.items.size)
        assertNull(feed.state.value.error)
    }

    @Test fun failedReadRollsBackAndSuccessfulReadSurvivesRefresh(): Unit=runBlocking {
        val source=Source(listOf(notice(1)))
        val feed=NotificationFeed(source::page,source::read)
        feed.refresh()
        source.fail=true
        feed.markRead(notice(1).id)
        assertFalse(feed.state.value.items.single().read)
        assertEquals("IOException: Read acknowledgement failed",feed.state.value.error)
        source.fail=false
        feed.markRead(notice(1).id);feed.refresh()
        assertTrue(feed.state.value.items.single().read)
    }

    @Test fun refreshWaitsForOlderPageAndPublishesCoherentWindow(): Unit=runBlocking {
        val source=Source((1..120).map(::notice))
        val started=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        var block=false
        val feed=NotificationFeed({ cursor ->
            if(block && cursor!=null) { started.complete(Unit);release.await() }
            source.page(cursor)
        },source::read)
        feed.refresh()
        block=true
        val older=async { feed.loadMore() }
        started.await()
        source.rows+=(121..130).map(::notice)
        val refresh=async { feed.refresh() }
        yield()
        assertEquals(1,source.calls)
        release.complete(Unit)
        older.await();refresh.await()
        assertEquals((130 downTo 31).map { notice(it).id },feed.state.value.items.map { it.id })
        assertFalse(feed.state.value.loadingMore)
    }
}
