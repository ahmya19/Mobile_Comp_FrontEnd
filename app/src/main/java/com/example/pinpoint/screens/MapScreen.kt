package com.example.pinpoint.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.CATEGORIES
import com.example.pinpoint.data.Report
import com.example.pinpoint.data.ReportFeedState
import com.example.pinpoint.data.ReportFilter
import com.example.pinpoint.data.UiText
import com.example.pinpoint.ui.theme.*

/** Interactive Community Map styled to PDF Board 4.1 (edge-to-edge visual map canvas with floating category chips & legend pill). */
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
    val pinnedCount = reports.count { it.latitude != null }

    Scaffold(
        containerColor = White,
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
        // Keep verticalScroll parent so existing Compose UI tests using performScrollTo() on map/filter/load-more stay 100% compatible.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Compact top bar: Explore Map + Alangilan pill (PDF 4.1)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Explore Map", style = MaterialTheme.typography.headlineSmall, color = Black)
                Surface(shape = RoundedCornerShape(999.dp), color = PinPointSurface) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Violet, modifier = Modifier.size(15.dp))
                        Text(feed.filter.barangay ?: "Alangilan", style = MaterialTheme.typography.labelMedium, color = Black)
                    }
                }
            }

            ReportFilters(feed.filter, onFilter)

            feed.error?.let {
                Surface(shape = RoundedCornerShape(14.dp), color = RedTint) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(UiText.humanize(it), color = AlertRed, style = MaterialTheme.typography.bodySmall)
                        // Loaded reports stay on the map; retry reloads the same filter and pages.
                        OutlinedButton(onClick = { onFilter(feed.filter) }, enabled = !feed.loading, shape = RoundedCornerShape(12.dp)) {
                            Text("Retry loading reports")
                        }
                    }
                }
            }

            // Tall immersive map card with floating category filter chips & floating bottom-left legend pill (PDF 4.1)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(VioletTint)
            ) {
                ReportMap(reports = reports, modifier = Modifier.fillMaxSize(), onReportClick = onReportClick)

                // Floating top category pill strip over map (PDF 4.1). Uses Row+horizontalScroll instead of LazyRow so
                // hasScrollToNodeAction() in MapIntegrationTest uniquely targets the stacked-marker LazyColumn chooser.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SolidFilterChip(
                        label = "All · $pinnedCount",
                        selected = feed.filter.category == null,
                        onClick = { onFilter(feed.filter.copy(category = null)) },
                        floatingWhite = true
                    )
                    CATEGORIES.forEach { cat ->
                        val short = when (cat) {
                            "Road Damage" -> "Road"
                            "Garbage" -> "Trash"
                            "Streetlight" -> "Lights"
                            "Flooding" -> "Flood"
                            "Water Interruption" -> "Water"
                            else -> "Other issue"
                        }
                        SolidFilterChip(
                            label = short,
                            selected = feed.filter.category == cat,
                            onClick = { onFilter(feed.filter.copy(category = if (feed.filter.category == cat) null else cat)) },
                            floatingWhite = true
                        )
                    }
                }

                // Floating bottom-left translucent white legend pill (PDF 4.1)
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = White.copy(alpha = 0.94f),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LegendDot("High", AlertRed)
                        LegendDot("Med", Amber)
                        LegendDot("Low", SkyLow)
                        LegendDot("Done", ResolvedGreen)
                    }
                }
            }

            if (feed.next != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = !feed.loading, onClick = onLoadMore),
                    shape = RoundedCornerShape(14.dp),
                    color = PinPointSurface
                ) {
                    Box(modifier = Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text("Load more reports on map", style = MaterialTheme.typography.labelLarge, color = DeepViolet, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$pinnedCount loaded pins on map", style = MaterialTheme.typography.labelSmall, color = MutedText)
                Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = MutedText)
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Black, fontWeight = FontWeight.Medium)
    }
}
