package com.example.pinpoint.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.CATEGORIES
import com.example.pinpoint.data.Citizen
import com.example.pinpoint.data.Report
import com.example.pinpoint.data.UiText
import com.example.pinpoint.ui.theme.*

/** Home Dashboard matching PDF Board 2 (2.1 Active charcoal card, 2.2 High-priority & community, 2.3 Empty & Quick report grid). */
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
    val activeReports = remember(reports) { reports.filter { it.status !in listOf("Resolved", "Rejected") }.take(3) }
    var activeIndex by remember(activeReports.size) { mutableIntStateOf(0) }
    val active = activeReports.getOrNull(activeIndex.coerceIn(0, (activeReports.size - 1).coerceAtLeast(0)))
    val recentList = community.take(3)
    val urgentList = community.filter { it.priority == "High" && it.status !in listOf("Resolved", "Rejected") }.take(5)
    val firstName = user?.displayName?.trim()?.substringBefore(' ').orEmpty().ifBlank { "Citizen" }
    val areaLabel = user?.barangay?.takeIf { it.isNotBlank() } ?: "Alangilan"
    val initials = firstName.take(1).uppercase()

    Scaffold(
        containerColor = White,
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
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Top bar: soft-grey rounded-square bell + pill area badge + violet-tint initials circle (PDF 2.1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box {
                    Surface(
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onNotificationsClick),
                        shape = RoundedCornerShape(14.dp),
                        color = PinPointSurface
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Notifications, contentDescription = "Notifications", tint = Black, modifier = Modifier.size(21.dp))
                        }
                    }
                    if (unreadNotifications > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-3).dp, y = 3.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Amber)
                        )
                    }
                }
                Surface(shape = RoundedCornerShape(999.dp), color = PinPointSurface) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Violet, modifier = Modifier.size(15.dp))
                        Text(areaLabel, style = MaterialTheme.typography.labelLarge, color = Black)
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = VioletTint,
                    modifier = Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onProfileClick)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(initials, style = MaterialTheme.typography.titleMedium, color = DeepViolet, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Greeting (PDF 2.1)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Hello $firstName!", style = MaterialTheme.typography.bodyMedium, color = MutedText)
                Text("Take care of\nyour community", style = MaterialTheme.typography.headlineLarge, color = Black)
            }

            // Charcoal Active Report Card with pager dots (PDF 2.1) OR Dashed Empty Card (PDF 2.3)
            if (active != null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable { onReportClick(active.id) },
                        shape = RoundedCornerShape(22.dp),
                        color = InkCard
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = active.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Icon(
                                        imageVector = categoryStyle(active.category).first,
                                        contentDescription = null,
                                        tint = Amber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                PriorityPill(active.priority, darkCard = true)
                            }
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(color = DarkBarText)) { append("Issue number: ") }
                                    withStyle(SpanStyle(color = Amber, fontWeight = FontWeight.Bold)) { append(shortIssueCode(active.id)) }
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                            ReportProgressTimeline(status = active.status, darkCard = true)
                        }
                    }
                    // Carousel pager indicator dots when multiple active reports exist (PDF 2.1)
                    if (activeReports.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            activeReports.indices.forEach { idx ->
                                Box(
                                    modifier = Modifier
                                        .height(6.dp)
                                        .width(if (idx == activeIndex) 22.dp else 8.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (idx == activeIndex) Violet else SoftBorder)
                                        .clickable { activeIndex = idx }
                                )
                            }
                        }
                    }
                }
            } else {
                // Dashed empty state card (PDF 2.3)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawRoundRect(
                            color = SoftBorder,
                            cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f))
                        )
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(VioletTint),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Violet, modifier = Modifier.size(24.dp))
                        }
                        Text(
                            text = if (loading) "Loading reports..." else "No active reports",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Black
                        )
                        Text(
                            text = "Spot a problem? Reporting takes less than a minute.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Reports from others (PDF 2.1 — sits directly below active card!)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Reports from others", style = MaterialTheme.typography.titleLarge, color = Black)
                    Text(
                        text = "View community reports",
                        style = MaterialTheme.typography.labelMedium,
                        color = DeepViolet,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCommunityReportsClick).padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                if (recentList.isEmpty()) {
                    SectionSurfaceCard {
                        Text("No community reports yet", style = MaterialTheme.typography.bodyMedium, color = MutedText)
                    }
                } else {
                    recentList.forEach { item ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onReportClick(item.id) },
                            shape = RoundedCornerShape(18.dp),
                            color = PinPointSurface
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CategoryIconBadge(item.category, size = 44)
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = Black
                                    )
                                    Text(
                                        "${item.location}, ${item.barangay}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MutedText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("↑ ${item.voteCount}", style = MaterialTheme.typography.labelMedium, color = DeepViolet, fontWeight = FontWeight.Bold)
                                    StatusPill(item.status)
                                }
                            }
                        }
                    }
                }
            }

            // Quick report CTA + 4-column compact pastel tile grid (PDF 2.3 — preserves exact "Quick Report" button selector)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Report by category", style = MaterialTheme.typography.titleLarge, color = Black)
                    Button(
                        onClick = onQuickReportClick,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = White)
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Quick Report", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }

                // 4-column compact tile grid matching PDF 2.3 (6 categories + All reports + dark "+ More")
                val tiles: List<Triple<String, String, () -> Unit>> = listOf(
                    Triple("Road Damage", "Road", { onQuickCategoryClick("Road Damage") }),
                    Triple("Garbage", "Garbage", { onQuickCategoryClick("Garbage") }),
                    Triple("Streetlight", "Lights", { onQuickCategoryClick("Streetlight") }),
                    Triple("Flooding", "Flooding", { onQuickCategoryClick("Flooding") }),
                    Triple("Water Interruption", "Water", { onQuickCategoryClick("Water Interruption") }),
                    Triple("Other", "Other", { onQuickCategoryClick("Other") })
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    tiles.take(4).forEach { (cat, label, action) ->
                        QuickReportGridTile(cat, label, action, Modifier.weight(1f))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    tiles.drop(4).forEach { (cat, label, action) ->
                        QuickReportGridTile(cat, label, action, Modifier.weight(1f))
                    }
                    Surface(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onReportsClick),
                        shape = RoundedCornerShape(16.dp),
                        color = PinPointSurface
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(VioletTint),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${reports.size}", style = MaterialTheme.typography.labelMedium, color = DeepViolet, fontWeight = FontWeight.Bold)
                            }
                            Text("Mine", style = MaterialTheme.typography.labelSmall, color = Black, maxLines = 1)
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onQuickReportClick),
                        shape = RoundedCornerShape(16.dp),
                        color = InkCard
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Violet),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = White, modifier = Modifier.size(20.dp))
                            }
                            Text("More", style = MaterialTheme.typography.labelSmall, color = White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }

            // High-priority issues horizontal hero carousel (PDF 2.2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("High-priority issues", style = MaterialTheme.typography.titleLarge, color = Black)
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelMedium,
                        color = DeepViolet,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCommunityReportsClick).padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                if (urgentList.isEmpty()) {
                    SectionSurfaceCard {
                        Text("No recent high-priority issues", style = MaterialTheme.typography.bodyMedium, color = MutedText)
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(urgentList, key = { it.id }) { item ->
                            Surface(
                                modifier = Modifier.width(232.dp).clip(RoundedCornerShape(20.dp)).clickable { onReportClick(item.id) },
                                shape = RoundedCornerShape(20.dp),
                                color = PinPointSurface
                            ) {
                                Column {
                                    // Colored editorial hero banner with floating white High pill (PDF 2.2)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(104.dp)
                                            .background(categoryHeroColor(item.category))
                                    ) {
                                        Icon(
                                            imageVector = categoryStyle(item.category).first,
                                            contentDescription = null,
                                            tint = White.copy(alpha = 0.28f),
                                            modifier = Modifier.size(48.dp).align(Alignment.Center)
                                        )
                                        PriorityPill(
                                            priority = item.priority,
                                            floatingOnHero = true,
                                            modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            item.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = Black
                                        )
                                        Text(
                                            "${item.location} · ${UiText.formatTimestamp(item.createdAt)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MutedText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            StatusPill(item.status)
                                            Text("↑ ${item.voteCount}", style = MaterialTheme.typography.labelMedium, color = Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun QuickReportGridTile(category: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (icon, tint, bg) = categoryStyle(category)
    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = bg.copy(alpha = 0.72f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(White.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = Black, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
