package com.example.pinpoint.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.CATEGORIES
import com.example.pinpoint.data.Citizen
import com.example.pinpoint.data.Report
import com.example.pinpoint.ui.theme.*

@Composable
fun HomeScreen(
    onQuickReportClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMapClick: () -> Unit,
    onProfileClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    reports: List<Report>,
    community: List<Report>,
    loading: Boolean,
    onCommunityReportsClick: () -> Unit,
    user: Citizen? = null,
    unreadNotifications: Int = 0,
    onReportClick: (String) -> Unit = {},
    onQuickCategoryClick: (String) -> Unit = { onQuickReportClick() }
) {
    val active = reports.firstOrNull { it.status !in listOf("Resolved", "Rejected") }
    val recentList = community.take(3)
    val urgentList = community.filter { it.priority == "High" && it.status !in listOf("Resolved", "Rejected") }.take(5)
    val firstName = user?.displayName?.trim()?.substringBefore(' ').orEmpty().ifBlank { "Citizen" }
    val areaLabel = user?.barangay?.takeIf { it.isNotBlank() } ?: "Alangilan"
    val initials = firstName.take(1).uppercase()

    Scaffold(
        containerColor = PinPointSurface,
        bottomBar = {
            PinPointBottomBar(
                selectedRoute = "home",
                onHomeClick = {},
                onReportsClick = onReportsClick,
                onMapClick = onMapClick,
                onProfileClick = onProfileClick,
                onReportClick = onQuickReportClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Top bar: Notifications + Area pill + Profile avatar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box {
                    Surface(shape = CircleShape, color = White, border = BorderStroke(1.dp, SoftBorder)) {
                        IconButton(onClick = onNotificationsClick) {
                            Icon(Icons.Filled.Notifications, contentDescription = "Notifications", tint = Black)
                        }
                    }
                    if (unreadNotifications > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Amber)
                        )
                    }
                }
                Surface(shape = RoundedCornerShape(999.dp), color = White, border = BorderStroke(1.dp, SoftBorder)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Violet, modifier = Modifier.size(16.dp))
                        Text(areaLabel, style = MaterialTheme.typography.labelLarge, color = Black)
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = VioletTint,
                    modifier = Modifier.size(42.dp).clickable(onClick = onProfileClick)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(initials, style = MaterialTheme.typography.titleMedium, color = DeepViolet, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Greeting
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Hello $firstName!", style = MaterialTheme.typography.bodyLarge, color = MutedText)
                Text("Take care of your community", style = MaterialTheme.typography.headlineMedium, color = Black)
            }

            // Active Report Charcoal Card (PDF 2.1) or Empty State (PDF 2.3)
            if (active != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onReportClick(active.id) },
                    shape = RoundedCornerShape(22.dp),
                    color = InkCard
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = active.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            PriorityPill(active.priority)
                        }
                        Text(
                            text = "Issue number: ${shortIssueCode(active.id)} · ${active.barangay}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Amber
                        )
                        ReportProgressTimeline(status = active.status, darkCard = true)
                    }
                }
            } else {
                SectionSurfaceCard {
                    Text(
                        text = if (loading) "Loading reports..." else "No active reports",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Black
                    )
                    Text(
                        text = "Spot a problem in $areaLabel? Reporting takes less than a minute — even offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText
                    )
                }
            }

            // Primary Quick Report CTA (preserves "Quick Report" selector for all existing tests)
            Button(
                onClick = onQuickReportClick,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Quick Report", fontWeight = FontWeight.SemiBold)
            }

            // Quick Report Category Tiles (PDF 2.3)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Quick report by category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Black)
                CATEGORIES.chunked(3).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { category ->
                            val shortName = when (category) {
                                "Road Damage" -> "Road"
                                "Water Interruption" -> "Water"
                                else -> category
                            }
                            Surface(
                                modifier = Modifier.weight(1f).clickable { onQuickCategoryClick(category) },
                                shape = RoundedCornerShape(16.dp),
                                color = White,
                                border = BorderStroke(1.dp, SoftBorder)
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CategoryIconBadge(category, size = 38)
                                    Text(shortName, style = MaterialTheme.typography.labelMedium, color = Black, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            // High-Priority Issues Horizontal Carousel (PDF 2.2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("High-Priority Issues", style = MaterialTheme.typography.titleLarge, color = Black)
                    TextButton(onClick = onCommunityReportsClick) { Text("See all") }
                }
                if (urgentList.isEmpty()) {
                    SectionSurfaceCard {
                        Text("No recent high-priority issues", style = MaterialTheme.typography.bodyMedium, color = MutedText)
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(urgentList, key = { it.id }) { item ->
                            Surface(
                                modifier = Modifier.width(240.dp).clickable { onReportClick(item.id) },
                                shape = RoundedCornerShape(18.dp),
                                color = White,
                                border = BorderStroke(1.dp, SoftBorder)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        PriorityPill(item.priority)
                                        StatusPill(item.status)
                                    }
                                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Black)
                                    Text("${item.location}, ${item.barangay}", style = MaterialTheme.typography.bodySmall, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Filled.ThumbUp, contentDescription = null, tint = Violet, modifier = Modifier.size(14.dp))
                                        Text("${item.voteCount} supporters", style = MaterialTheme.typography.labelSmall, color = DeepViolet)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Recent Community Reports (PDF 2.1 & 2.2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recent Community Reports", style = MaterialTheme.typography.titleLarge, color = Black)
                }
                TextButton(onClick = onCommunityReportsClick) { Text("View community reports") }
                if (recentList.isEmpty()) {
                    SectionSurfaceCard {
                        Text("No community reports yet", style = MaterialTheme.typography.bodyMedium, color = MutedText)
                    }
                } else {
                    recentList.forEach { item ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onReportClick(item.id) },
                            shape = RoundedCornerShape(16.dp),
                            color = White,
                            border = BorderStroke(1.dp, SoftBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CategoryIconBadge(item.category)
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Black)
                                    Text("${item.location}, ${item.barangay}", style = MaterialTheme.typography.bodySmall, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    StatusPill(item.status)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("↑ ${item.voteCount}", style = MaterialTheme.typography.labelMedium, color = DeepViolet, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
