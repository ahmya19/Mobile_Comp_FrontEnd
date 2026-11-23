package com.example.pinpoint.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.CATEGORIES
import com.example.pinpoint.data.Report
import com.example.pinpoint.data.ReportFeedState
import com.example.pinpoint.data.ReportFilter
import com.example.pinpoint.ui.theme.*

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
    onFilter: (ReportFilter) -> Unit,
    onLoadMore: () -> Unit,
    pendingUploads: Int = 0,
    onRetryUploads: () -> Unit = {},
    onReportCreateClick: (() -> Unit)? = null,
    onManageSavedClick: (() -> Unit)? = null
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortMostUpvoted by rememberSaveable { mutableStateOf(false) }

    val displayedReports = remember(reports, searchQuery, sortMostUpvoted, communityFeed) {
        val trimmed = searchQuery.trim()
        val searched = if (!communityFeed || trimmed.isEmpty()) reports else reports.filter {
            it.description.contains(trimmed, ignoreCase = true) ||
                it.location.contains(trimmed, ignoreCase = true) ||
                it.barangay.contains(trimmed, ignoreCase = true) ||
                it.category.contains(trimmed, ignoreCase = true)
        }
        if (communityFeed && sortMostUpvoted) searched.sortedWith(compareByDescending<Report> { it.voteCount }.thenByDescending { it.createdAt })
        else searched
    }

    Scaffold(
        containerColor = PinPointSurface,
        bottomBar = {
            PinPointBottomBar(
                selectedRoute = if (communityFeed) "home" else "reports",
                onHomeClick = onHomeClick,
                onReportsClick = {},
                onMapClick = onMapClick,
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
        ) {
            Text(
                text = if (communityFeed) "Community Reports" else "My Reports",
                style = MaterialTheme.typography.headlineMedium,
                color = Black
            )
            Text(
                text = if (communityFeed) "Recent issues reported by your community." else "Track the reports you have submitted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText,
                modifier = Modifier.padding(top = 4.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!communityFeed) {
                    // Status summary counters (PDF 6.1)
                    item {
                        val pendingCount = reports.count { it.status == "Pending" || it.pendingSync }
                        val reviewCount = reports.count { it.status == "Under Review" }
                        val activeCount = reports.count { it.status == "In Progress" }
                        val doneCount = reports.count { it.status == "Resolved" }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryCounterCard("Pending", pendingCount, AmberTint, Amber, Modifier.weight(1f)) {
                                onFilter(feed.filter.copy(status = if (feed.filter.status == "Pending") null else "Pending"))
                            }
                            SummaryCounterCard("Review", reviewCount, SkyTint, SkyLow, Modifier.weight(1f)) {
                                onFilter(feed.filter.copy(status = if (feed.filter.status == "Under Review") null else "Under Review"))
                            }
                            SummaryCounterCard("Active", activeCount, VioletTint, Violet, Modifier.weight(1f)) {
                                onFilter(feed.filter.copy(status = if (feed.filter.status == "In Progress") null else "In Progress"))
                            }
                            SummaryCounterCard("Done", doneCount, GreenTint, ResolvedGreen, Modifier.weight(1f)) {
                                onFilter(feed.filter.copy(status = if (feed.filter.status == "Resolved") null else "Resolved"))
                            }
                        }
                    }
                } else {
                    // Community quick search + sort + category strip (PDF 5.1, 5.5, 5.6)
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search loaded reports...") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !sortMostUpvoted,
                                    onClick = { sortMostUpvoted = false },
                                    label = { Text("Newest") }
                                )
                                FilterChip(
                                    selected = sortMostUpvoted,
                                    onClick = { sortMostUpvoted = true },
                                    label = { Text("Most upvoted") }
                                )
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    FilterChip(
                                        selected = feed.filter.category == null,
                                        onClick = { onFilter(feed.filter.copy(category = null)) },
                                        label = { Text("All") }
                                    )
                                }
                                items(CATEGORIES) { cat ->
                                    val short = when (cat) {
                                        "Road Damage" -> "Road"
                                        "Water Interruption" -> "Water"
                                        else -> cat
                                    }
                                    FilterChip(
                                        selected = feed.filter.category == cat,
                                        onClick = { onFilter(feed.filter.copy(category = if (feed.filter.category == cat) null else cat)) },
                                        label = { Text(short) }
                                    )
                                }
                            }
                        }
                    }
                }

                item { ReportFilters(feed.filter, onFilter) }

                if (pendingUploads > 0) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = AmberTint,
                            border = BorderStroke(1.dp, Amber.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Filled.CloudOff, contentDescription = null, tint = Black, modifier = Modifier.size(18.dp))
                                    Text(
                                        "$pendingUploads report(s) have uploads pending. Originals wait for unmetered Wi-Fi.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Black
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = onRetryUploads) { Text("Retry uploads") }
                                    if (onManageSavedClick != null) {
                                        TextButton(onClick = onManageSavedClick) { Text("Manage saved reports") }
                                    }
                                }
                            }
                        }
                    }
                }

                feed.error?.let { message ->
                    item {
                        Surface(shape = RoundedCornerShape(14.dp), color = RedTint) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(message, color = AlertRed, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onFilter(feed.filter) }, enabled = !feed.loading) {
                                    Text("Retry loading reports")
                                }
                            }
                        }
                    }
                }

                if (feed.loading) item { Text("Loading reports...", color = MutedText) }
                else if (displayedReports.isEmpty() && feed.error == null) item {
                    SectionSurfaceCard {
                        Text("No reports match these filters.", style = MaterialTheme.typography.bodyMedium, color = MutedText)
                    }
                }

                items(displayedReports, key = { it.id }) { report ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onReportClick(report.id) },
                        shape = RoundedCornerShape(18.dp),
                        color = White,
                        border = BorderStroke(1.dp, SoftBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CategoryIconBadge(report.category)
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = report.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Black,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    PriorityPill(report.priority)
                                }
                                Text(
                                    text = "${report.location}, ${report.barangay}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StatusPill(report.status)
                                    Text("↑ ${report.voteCount} · ${shortIssueCode(report.id)}", style = MaterialTheme.typography.labelSmall, color = MutedText)
                                }
                                report.originalUploadStatus?.let {
                                    Text("Original: $it", style = MaterialTheme.typography.labelSmall, color = DeepViolet)
                                }
                            }
                        }
                    }
                }

                if (feed.next != null) {
                    item {
                        OutlinedButton(
                            onClick = onLoadMore,
                            enabled = !feed.loading,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Load more reports")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCounterCard(
    label: String,
    count: Int,
    bg: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = bg
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(count.toString(), style = MaterialTheme.typography.titleLarge, color = accent, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Black, maxLines = 1)
        }
    }
}
