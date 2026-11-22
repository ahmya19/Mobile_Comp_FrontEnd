package com.example.pinpoint.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NotificationFeedState(val items: List<ReportNotification> = emptyList(),
    val next: ReportCursor? = null, val loadingMore: Boolean = false, val error: String? = null)

/** One account's loaded history. Refresh walks its loaded pages again so newly
 * inserted notices cannot leave a gap between a refreshed head and cached tail. */
class NotificationFeed(private val page: suspend (ReportCursor?) -> NotificationPage,
    private val setRead: suspend (String) -> Unit) {
    private val mutex = Mutex()
    private var pages = 1
    private val mutableState = MutableStateFlow(NotificationFeedState())
    val state = mutableState.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        try {
            val items=mutableListOf<ReportNotification>()
            var cursor: ReportCursor?=null
            repeat(pages) {
                val result=page(cursor)
                items+=result.items
                cursor=result.next
                if(cursor==null) {
                    mutableState.value=NotificationFeedState(items.distinctBy { it.id })
                    return@withLock
                }
            }
            mutableState.value=NotificationFeedState(items.distinctBy { it.id },cursor)
        } catch(error: Exception) { failed(error) }
    }

    suspend fun loadMore() = mutex.withLock {
        val cursor=state.value.next ?: return@withLock
        mutableState.value=state.value.copy(loadingMore=true,error=null)
        try {
            val result=page(cursor)
            pages++
            mutableState.value=NotificationFeedState((state.value.items+result.items).distinctBy { it.id },result.next)
        } catch(error: Exception) { failed(error) }
    }

    suspend fun markRead(id: String) = mutex.withLock {
        val before=state.value.items
        mutableState.value=state.value.copy(items=before.map { if(it.id==id) it.copy(read=true) else it },error=null)
        try { setRead(id) }
        catch(error: Exception) {
            mutableState.value=state.value.copy(items=before)
            failed(error)
        }
    }

    private fun failed(error: Exception) {
        if(error is CancellationException) throw error
        mutableState.value=state.value.copy(loadingMore=false,error="${error.javaClass.simpleName}: ${error.message}")
    }
}
