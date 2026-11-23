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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.CATEGORIES
import com.example.pinpoint.data.Report
import com.example.pinpoint.data.ReportFeedState
import com.example.pinpoint.data.ReportFilter
import com.example.pinpoint.data.UiText
import com.example.pinpoint.ui.theme.*

/** My Reports (PDF Board 6.1–6.5) and Community Issue Feed (PDF Board 5.1 & 5.6). */
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

    val pendingCount = reports.count { it.status == "Pending" || it.pendingSync }
    val reviewCount = reports.count { it.status == "Under Review" }
    val activeCount = reports.count { it.status == "In Progress" }
    val doneCount = reports.count { it.status == "Resolved" }

    Scaffold(
        containerColor = White,
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
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = if (communityFeed) "Community" else "My reports",
                style = MaterialTheme.typography.headlineMedium,
                color = Black
            )
            Text(
                text = if (communityFeed) "Recent issues reported by your community." else "Track the reports you have submitted.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText,
                modifier = Modifier.padding(top = 2.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!communityFeed) {
                    // Horizontal solid-black status pill tabs (PDF 6.1–6.5)
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                SolidFilterChip(
                                    label = "All · ${reports.size}",
                                    selected = feed.filter.status == null,
                                    onClick = { onFilter(feed.filter.copy(status = null)) }
                                )
                            }
                            item {
                                SolidFilterChip(
                                    label = "Pending · $pendingCount",
                                    selected = feed.filter.status == "Pending",
                                    onClick = { onFilter(feed.filter.copy(status = "Pending")) }
                                )
                            }
                            item {
                                SolidFilterChip(
                                    label = "Under review · $reviewCount",
                                    selected = feed.filter.status == "Under Review",
                                    onClick = { onFilter(feed.filter.copy(status = "Under Review")) }
                                )
                            }
                            item {
                                SolidFilterChip(
                                    label = "In progress · $activeCount",
                                    selected = feed.filter.status == "In Progress",
                                    onClick = { onFilter(feed.filter.copy(status = "In Progress")) }
                                )
                            }
                            item {
                                SolidFilterChip(
                                    label = "Resolved · $doneCount",
                                    selected = feed.filter.status == "Resolved",
                                    onClick = { onFilter(feed.filter.copy(status = "Resolved")) }
                                )
                            }
                        }
                    }

                    // 4 pastel status summary counters (PDF 6.1)
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryCounterCard("Pending", pendingCount, AmberTint, AmberText, Modifier.weight(1f)) {
                                onFilter(feed.filter.copy(status = if (feed.filter.status == "Pending") null else "Pending"))
                            }
                            SummaryCounterCard("Review", reviewCount, SkyTint, SkyText, Modifier.weight(1f)) {
                                onFilter(feed.filter.copy(status = if (feed.filter.status == "Under Review") null else "Under Review"))
                            }
                            SummaryCounterCard("Active", activeCount, VioletTint, DeepViolet, Modifier.weight(1f)) {
                                onFilter(feed.filter.copy(status = if (feed.filter.status == "In Progress") null else "In Progress"))
                            }
                            SummaryCounterCard("Done", doneCount, GreenTint, ResolvedGreen, Modifier.weight(1f)) {
                                onFilter(feed.filter.copy(status = if (feed.filter.status == "Resolved") null else "Resolved"))
                            }
                        }
                    }
                } else {
                    // Community search + solid-black category chips + segmented sort bar (PDF 5.1 & 5.6)
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search loaded reports...") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MutedText) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = pinPointFieldColors()
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    SolidFilterChip(
                                        label = "All",
                                        selected = feed.filter.category == null,
                                        onClick = { onFilter(feed.filter.copy(category = null)) }
                                    )
                                }
                                items(CATEGORIES) { cat ->
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
                                        onClick = { onFilter(feed.filter.copy(category = if (feed.filter.category == cat) null else cat)) }
                                    )
                                }
                            }
                            // Segmented sort tabs (PDF 5.1)
                            Surface(shape = RoundedCornerShape(14.dp), color = PinPointSurface) {
                                Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SortSegment("Newest", !sortMostUpvoted, Modifier.weight(1f)) { sortMostUpvoted = false }
                                    SortSegment("Most upvoted", sortMostUpvoted, Modifier.weight(1f)) { sortMostUpvoted = true }
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
                            shape = RoundedCornerShape(18.dp),
                            color = AmberTint
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Filled.CloudOff, contentDescription = null, tint = AmberText, modifier = Modifier.size(18.dp))
                                    Text(
                                        "$pendingUploads report(s) have uploads pending. Originals wait for unmetered Wi-Fi.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Black
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = onRetryUploads) { Text("Retry uploads", color = DeepViolet, fontWeight = FontWeight.Bold) }
                                    if (onManageSavedClick != null) {
                                        TextButton(onClick = onManageSavedClick) { Text("Manage saved reports", color = DeepViolet, fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                    }
                }

                feed.error?.let { message ->
                    item {
                        Surface(shape = RoundedCornerShape(16.dp), color = RedTint) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(UiText.humanize(message), color = AlertRed, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onFilter(feed.filter) }, enabled = !feed.loading) {
                                    Text("Retry loading reports", color = AlertRed, fontWeight = FontWeight.Bold)
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
                    if (communityFeed) {
                        CommunityHeroFeedCard(report = report, onClick = { onReportClick(report.id) })
                    } else {
                        MyReportRowCard(report = report, onClick = { onReportClick(report.id) })
                    }
                }

                if (feed.next != null) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !feed.loading, onClick = onLoadMore),
                            shape = RoundedCornerShape(14.dp),
                            color = PinPointSurface
                        ) {
                            Box(modifier = Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                                Text("Load more reports", style = MaterialTheme.typography.labelLarge, color = DeepViolet, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(6.dp)) }
            }
        }
    }
}

/** Editorial visual feed card matching PDF Board 5.1 (colored hero banner + floating white priority pill + violet upvote pill). */
@Composable
private fun CommunityHeroFeedCard(report: Report, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = PinPointSurface
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .background(categoryHeroColor(report.category))
            ) {
                Icon(
                    imageVector = categoryStyle(report.category).first,
                    contentDescription = null,
                    tint = White.copy(alpha = 0.28f),
                    modifier = Modifier.size(50.dp).align(Alignment.Center)
                )
                PriorityPill(
                    priority = report.priority,
                    floatingOnHero = true,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                )
            }
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = report.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(report.status)
                }
                Text(
                    text = "${report.location}, ${report.barangay} · ${UiText.formatTimestamp(report.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = White,
                        border = BorderStroke(1.dp, Violet.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "↑ ${report.voteCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = DeepViolet,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                    Text(shortIssueCode(report.id), style = MaterialTheme.typography.labelSmall, color = MutedText)
                }
            }
        }
    }
}

/** Compact borderless card with muted square thumbnail matching PDF Board 6.1–6.5. */
@Composable
private fun MyReportRowCard(report: Report, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = PinPointSurface
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CategoryThumbnailTile(report.category, size = 56)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = report.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        PriorityPill(report.priority)
                    }
                    Text(
                        text = "${shortIssueCode(report.id)} · ${UiText.formatTimestamp(report.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
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
                        Text("↑ ${report.voteCount}", style = MaterialTheme.typography.labelSmall, color = MutedText, fontWeight = FontWeight.SemiBold)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp))
            }
            if (report.status == "In Progress") {
                Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp)).background(SoftBorder)) {
                    Box(modifier = Modifier.fillMaxWidth(0.65f).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(Violet))
                }
            }
            report.originalUploadStatus?.let {
                Text("Original: $it", style = MaterialTheme.typography.labelSmall, color = DeepViolet)
            }
        }
    }
}

@Composable
private fun SortSegment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) White else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp
    ) {
        Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Black else MutedText,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SummaryCounterCard(
    label: String,
    count: Int,
    bg: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = bg
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(count.toString(), style = MaterialTheme.typography.titleLarge, color = accent, fontWeight = FontWeight.ExtraBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Black, maxLines = 1)
        }
    }
}
