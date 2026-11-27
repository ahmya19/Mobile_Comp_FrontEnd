package com.example.pinpoint.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.Report
import com.example.pinpoint.data.StatusChange
import com.example.pinpoint.data.UiText
import com.example.pinpoint.ui.theme.*

/** Report Details styled to PDF Boards 4.6, 5.2, 6.6 & 6.7 (edge-to-edge top hero, rounded overlapping sheet, supporter card, vertical timeline). */
@Composable
fun ReportDetailsScreen(
    onBackClick: () -> Unit,
    report: Report?,
    loading: Boolean,
    error: String?,
    photo: ImageBitmap?,
    official: Boolean,
    busy: Boolean,
    onUpdateStatus: (String, String) -> Unit,
    hasVoted: Boolean,
    onVote: () -> Unit,
    canReadOriginal: Boolean,
    onShowOriginal: (String) -> Unit,
    history: List<StatusChange>
) {
    var showFullPhoto by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showFullPhoto && photo != null) FullPhotoViewer(photo) { showFullPhoto = false }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .verticalScroll(rememberScrollState())
    ) {
        // Full-bleed edge-to-edge top hero with floating dark circular back button (PDF 4.6 & 5.2)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (photo != null) 250.dp else 170.dp)
                .background(if (report != null) categoryHeroColor(report.category) else InkCard)
                .then(if (photo != null) Modifier.clickable { showFullPhoto = true } else Modifier)
        ) {
            if (photo != null) {
                Image(
                    bitmap = photo,
                    contentDescription = "Report photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (report != null) {
                Icon(
                    imageVector = categoryStyle(report.category).first,
                    contentDescription = null,
                    tint = White.copy(alpha = 0.28f),
                    modifier = Modifier.size(64.dp).align(Alignment.Center)
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBackClick),
                shape = CircleShape,
                color = InkCard.copy(alpha = 0.72f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = White, modifier = Modifier.size(20.dp))
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                shape = RoundedCornerShape(999.dp),
                color = InkCard.copy(alpha = 0.72f)
            ) {
                Text(
                    text = "Report Details",
                    style = MaterialTheme.typography.labelMedium,
                    color = White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Overlapping rounded white body sheet (PDF 4.6 & 5.2)
        Surface(
            modifier = Modifier.fillMaxWidth().offset(y = (-20).dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = White
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (loading) Text("Loading report...", color = MutedText)
                error?.let {
                    Surface(shape = RoundedCornerShape(14.dp), color = RedTint) {
                        Text(it, color = AlertRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                }

                if (report != null) {
                    // Top inline pills + title + issue code (PDF 5.2)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            PriorityPill(report.priority)
                            StatusPill(report.status)
                        }
                        Text(shortIssueCode(report.id), style = MaterialTheme.typography.labelMedium, color = DeepViolet, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        report.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Black,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Violet, modifier = Modifier.size(16.dp))
                        Text(
                            "${report.location}, ${report.barangay} · ${UiText.formatTimestamp(report.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText
                        )
                    }

                    // Board 6.6 Charcoal Progress Card
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = InkCard
                    ) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(shortIssueCode(report.id), style = MaterialTheme.typography.labelLarge, color = Amber, fontWeight = FontWeight.Bold)
                                PriorityPill(report.priority, darkCard = true)
                            }
                            ReportProgressTimeline(status = report.status, darkCard = true)
                        }
                    }

                    // Structured Details Card (preserves exact test prefixes: Category:, Location:, Description:, Coordinates:)
                    SectionSurfaceCard {
                        Text(text = "Category: ${report.category}", style = MaterialTheme.typography.bodyMedium, color = Black, fontWeight = FontWeight.SemiBold)
                        Text(text = "Location: ${report.location}, ${report.barangay}", style = MaterialTheme.typography.bodyMedium, color = Black)
                        Text(text = "Status: ${report.status} | Priority: ${report.priority}", style = MaterialTheme.typography.labelLarge, color = DeepViolet)
                        Text(text = "Submitted: ${UiText.formatTimestamp(report.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MutedText)
                        HorizontalDivider(color = SoftBorder)
                        Text(text = "Description: ${report.description}", style = MaterialTheme.typography.bodyLarge, color = Black)
                        report.latitude?.let {
                            Text("Coordinates: $it, ${report.longitude}", style = MaterialTheme.typography.bodySmall, color = MutedText)
                        }
                        report.originalUploadStatus?.let {
                            Text("Original: $it", style = MaterialTheme.typography.labelMedium, color = DeepViolet)
                        }
                        report.originalPath?.takeIf { canReadOriginal }?.let { path ->
                            OutlinedButton(
                                onClick = { onShowOriginal(path) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Violet.copy(alpha = 0.5f))
                            ) {
                                Text("View original over Wi-Fi", color = DeepViolet, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // Board 5.2 Supporter / Upvote Banner Card
                    SectionSurfaceCard(containerColor = PinPointSurface) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Overlapping pastel community avatar badges (PDF 5.2)
                            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                                listOf(VioletTint to DeepViolet, SkyTint to SkyText, GreenTint to ResolvedGreen).forEachIndexed { idx, (bg, fg) ->
                                    Box(
                                        modifier = Modifier.size(30.dp).clip(CircleShape).background(bg),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(listOf("C", "B", "A")[idx], style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Community votes: ${report.voteCount}", style = MaterialTheme.typography.titleSmall, color = Black, fontWeight = FontWeight.Bold)
                                Text(
                                    "3 votes raise urgency to Medium; 10 votes raise it to High.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedText
                                )
                            }
                        }
                        if (!report.pendingSync && report.status !in listOf("Resolved", "Rejected")) {
                            Button(
                                onClick = onVote,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (hasVoted) InkCard else Violet,
                                    contentColor = White
                                )
                            ) {
                                Text(if (hasVoted) "Remove my urgency vote" else "Vote for urgency", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Board 6.6 / 6.7 Vertical Connected Status History Timeline
                    if (history.isNotEmpty()) {
                        SectionSurfaceCard {
                            Text("Status history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Black)
                            history.forEachIndexed { index, change ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier.size(22.dp).clip(CircleShape).background(Violet),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = White, modifier = Modifier.size(13.dp))
                                        }
                                        if (index < history.lastIndex) {
                                            Box(modifier = Modifier.width(2.dp).height(28.dp).background(VioletTint))
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "${change.previousStatus} → ${change.status}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = Black,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "${UiText.formatTimestamp(change.createdAt)}: ${change.previousStatus} → ${change.status}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MutedText
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Assigned Barangay Official Controls
                    if (official && !report.pendingSync) {
                        var status by remember(report.id, report.status) { mutableStateOf(report.status) }
                        var priority by remember(report.id, report.priority) { mutableStateOf(report.priority) }
                        SectionSurfaceCard(containerColor = VioletTint.copy(alpha = 0.55f)) {
                            Text("Official controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Black)
                            Text(
                                "Only actual status changes notify the reporter. Priority-only updates respect community vote floors.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedText
                            )
                            ChoiceMenu("Status", status, listOf("Pending", "Under Review", "In Progress", "Resolved", "Rejected")) { status = it }
                            ChoiceMenu("Priority", priority, listOf("Low", "Medium", "High")) { priority = it }
                            Button(
                                onClick = { onUpdateStatus(status, priority) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Save status", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onBackClick),
                    shape = RoundedCornerShape(14.dp),
                    color = PinPointSurface
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Back to My Reports", style = MaterialTheme.typography.titleSmall, color = Black, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceMenu(label: String, selected: String, values: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = White)
        ) {
            Text("$label: $selected", color = Black)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(text = { Text(value) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}
