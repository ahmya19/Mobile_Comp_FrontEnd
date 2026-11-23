package com.example.pinpoint.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.Report
import com.example.pinpoint.data.ReportFeedState
import com.example.pinpoint.data.ReportFilter
import com.example.pinpoint.ui.theme.*

@Composable
fun MapScreen(
    onHomeClick: () -> Unit,
    onReportsClick: () -> Unit,
    onProfileClick: () -> Unit,
    reports: List<Report>,
    onReportClick: (String) -> Unit,
    feed: ReportFeedState,
    onFilter: (ReportFilter) -> Unit,
    onLoadMore: () -> Unit,
    onReportCreateClick: (() -> Unit)? = null
) {
    Scaffold(
        containerColor = PinPointSurface,
        bottomBar = {
            PinPointBottomBar(
                selectedRoute = "map",
                onHomeClick = onHomeClick,
                onReportsClick = onReportsClick,
                onMapClick = {},
                onProfileClick = onProfileClick,
                onReportClick = onReportCreateClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Explore Map", style = MaterialTheme.typography.headlineMedium, color = Black)
            Text(text = "View reported issues in your community.", style = MaterialTheme.typography.bodyMedium, color = MutedText)

            ReportFilters(feed.filter, onFilter)

            feed.error?.let {
                Surface(shape = RoundedCornerShape(14.dp), color = RedTint) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(com.example.pinpoint.data.UiText.humanize(it), color = AlertRed, style = MaterialTheme.typography.bodySmall)
                        // Loaded reports stay on the map; retry reloads the same filter and pages.
                        OutlinedButton(onClick = { onFilter(feed.filter) }, enabled = !feed.loading) {
                            Text("Retry loading reports")
                        }
                    }
                }
            }

            if (feed.next != null) {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !feed.loading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Load more reports on map")
                }
            }

            // Priority & Status color legend (PDF 4.1)
            Surface(shape = RoundedCornerShape(12.dp), color = White, border = BorderStroke(1.dp, SoftBorder)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendDot("High", AlertRed)
                    LegendDot("Med", Amber)
                    LegendDot("Low", SkyLow)
                    LegendDot("Done", ResolvedGreen)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().height(450.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = VioletTint)
            ) {
                ReportMap(reports = reports, modifier = Modifier.fillMaxSize(), onReportClick = onReportClick)
            }

            Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = MutedText)
            Text(
                "${reports.count { it.latitude != null }} loaded reports have map locations. Load more to include older reports. Reports without coordinates stay in the feed.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText
            )
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Black)
    }
}
