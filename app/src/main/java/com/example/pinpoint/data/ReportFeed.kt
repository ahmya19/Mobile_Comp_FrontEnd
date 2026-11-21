package com.example.pinpoint.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ReportFeedState(val filter: ReportFilter, val reports: List<Report> = emptyList(),
    val next: ReportCursor? = null, val loading: Boolean = false, val error: String? = null)

/** Serializes refresh/filter/page requests so an old response cannot replace a new filter. */
class ReportFeed(private val repository: PinPointRepository, mine: Boolean) {
    private val mutex = Mutex()
    private var pages = 1
    private val mutableState = MutableStateFlow(ReportFeedState(ReportFilter(mine=mine)))
    val state = mutableState.asStateFlow()

    suspend fun refresh(filter: ReportFilter? = null) = mutex.withLock {
        val changed = filter != null && filter != state.value.filter
        if (changed) { pages=1; mutableState.value=ReportFeedState(requireNotNull(filter)) }
        mutableState.value=state.value.copy(loading=true,error=null)
        try {
            val reports=mutableListOf<Report>()
            var cursor: ReportCursor?=null
            for (page in 0 until pages) {
                val result=repository.pageReports(state.value.filter,cursor)
                reports+=result.reports
                cursor=result.next
                if(cursor==null) break
            }
            mutableState.value=state.value.copy(reports=reports.distinctBy { it.id },next=cursor,loading=false)
        } catch(error: Exception) { failed(error) }
    }

    suspend fun loadMore() = mutex.withLock {
        val cursor=state.value.next ?: return@withLock
        mutableState.value=state.value.copy(loading=true,error=null)
        try {
            val result=repository.pageReports(state.value.filter,cursor)
            pages++
            mutableState.value=state.value.copy(reports=(state.value.reports+result.reports).distinctBy { it.id },
                next=result.next,loading=false)
        } catch(error: Exception) { failed(error) }
    }

    private fun failed(error: Exception) {
        if(error is CancellationException) throw error
        mutableState.value=state.value.copy(loading=false,error="${error.javaClass.simpleName}: ${error.message}")
    }
}
