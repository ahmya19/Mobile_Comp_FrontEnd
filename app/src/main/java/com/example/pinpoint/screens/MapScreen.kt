package com.example.pinpoint.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.VioletTint
import com.example.pinpoint.data.Report
import com.example.pinpoint.data.ReportFeedState
import com.example.pinpoint.data.ReportFilter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton

@Composable
fun MapScreen(
    onHomeClick: () -> Unit,
    onReportsClick: () -> Unit,
    onProfileClick: () -> Unit,
    reports: List<Report>,
    onReportClick: (String)->Unit,
    feed: ReportFeedState,
    onFilter: (ReportFilter)->Unit,
    onLoadMore: ()->Unit
) {

    Scaffold(
        bottomBar = {
            PinPointBottomBar(
                selectedRoute = "map",
                onHomeClick = onHomeClick,
                onReportsClick = onReportsClick,
                onMapClick = {},
                onProfileClick = onProfileClick
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {

            Text(
                text = "Explore Map",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = "View reported issues in your community.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            ReportFilters(feed.filter,onFilter)
            feed.error?.let {
                Text(it,color=MaterialTheme.colorScheme.error)
                // Loaded reports stay on the map; retry reloads the same filter and pages.
                OutlinedButton(onClick={ onFilter(feed.filter) },enabled=!feed.loading) { Text("Retry loading reports") }
            }
            if(feed.next!=null) OutlinedButton(onClick=onLoadMore,enabled=!feed.loading) { Text("Load more reports on map") }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .height(450.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = VioletTint
                )
            ) {

                ReportMap(reports=reports,modifier=Modifier.fillMaxSize(),onReportClick=onReportClick)
            }
            Text("© OpenStreetMap contributors")
            Text("${reports.count { it.latitude!=null }} loaded reports have map locations. Load more to include older reports. Reports without coordinates stay in the feed.")
        }
    }
}
