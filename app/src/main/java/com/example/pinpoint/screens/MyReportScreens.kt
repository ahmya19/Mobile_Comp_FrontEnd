package com.example.pinpoint.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.ReportFeedState
import com.example.pinpoint.data.ReportFilter
import com.example.pinpoint.ui.theme.VioletTint
import com.example.pinpoint.data.Report

@Composable
fun MyReportsScreen(
    reports: List<Report>,
    loading: Boolean,
    onReportClick: (String) -> Unit,
    onHomeClick: () -> Unit,
    onMapClick: () -> Unit,
    onProfileClick: () -> Unit,
    communityFeed: Boolean = false,
    feed: ReportFeedState,
    onFilter: (ReportFilter)->Unit,
    onLoadMore: ()->Unit,
    pendingUploads: Int=0,
    onRetryUploads: ()->Unit={}
) {

    Scaffold(
        bottomBar = {
            PinPointBottomBar(
                selectedRoute = if (communityFeed) "home" else "reports",
                onHomeClick = onHomeClick,
                onReportsClick = {},
                onMapClick = onMapClick,
                onProfileClick = onProfileClick
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
        ) {

            Text(
                text = if (communityFeed) "Community Reports" else "My Reports",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = if (communityFeed) "Recent issues reported by your community." else "Track the reports you have submitted.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                item { ReportFilters(feed.filter,onFilter) }
                if(pendingUploads>0) item {
                    Text("$pendingUploads report(s) have uploads pending. Originals wait for unmetered Wi-Fi.")
                    TextButton(onClick=onRetryUploads) { Text("Retry uploads") }
                }
                feed.error?.let { message -> item {
                    Text(message,color=MaterialTheme.colorScheme.error)
                    TextButton(onClick={ onFilter(feed.filter) },enabled=!feed.loading) { Text("Retry loading reports") }
                } }
                if (feed.loading) item { Text("Loading reports...") }
                else if (reports.isEmpty() && feed.error==null) item { Text("No reports match these filters.") }
                items(reports, key = { it.id }) { report ->

                    Card(
                        onClick = { onReportClick(report.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = VioletTint
                        )
                    ) {

                        Column(
                            modifier = Modifier.padding(18.dp)
                        ) {

                            Text(
                                text = report.title,
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text(
                                text = report.location,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 6.dp)
                            )

                            Text(
                                text = report.status,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            report.originalUploadStatus?.let { Text("Original: $it",style=MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                if(feed.next!=null) item {
                    OutlinedButton(onClick=onLoadMore,enabled=!feed.loading,modifier=Modifier.fillMaxWidth()) { Text("Load more reports") }
                }
            }
        }
    }
}
