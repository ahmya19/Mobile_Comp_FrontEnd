package com.example.pinpoint.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ThumbUp
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
import com.example.pinpoint.ui.theme.*

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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PinPointSurface)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconButton(onClick = onBackClick) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        Text(text = "Report Details", style = MaterialTheme.typography.headlineMedium, color = Black)

        if (loading) Text("Loading report...", color = MutedText)
        error?.let {
            Surface(shape = RoundedCornerShape(14.dp), color = RedTint) {
                Text(it, color = AlertRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
        }

        if (report != null) {
            // Hero photo card if available
            if (photo != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = White,
                    border = BorderStroke(1.dp, SoftBorder)
                ) {
                    Image(
                        bitmap = photo,
                        contentDescription = "Report photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp)
                    )
                }
            }

            // Progress Timeline Charcoal Card (PDF 6.6)
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
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PriorityPill(report.priority)
                            StatusPill(report.status)
                        }
                    }
                    Text(report.title, style = MaterialTheme.typography.titleLarge, color = White)
                    ReportProgressTimeline(status = report.status, darkCard = true)
                }
            }

            // Main Report Details Card (preserves exact test assertion prefixes!)
            SectionSurfaceCard {
                Text(text = "Category: ${report.category}", style = MaterialTheme.typography.bodyMedium, color = Black)
                Text(text = "Location: ${report.location}, ${report.barangay}", style = MaterialTheme.typography.bodyMedium, color = Black)
                Text(text = "Status: ${report.status} | Priority: ${report.priority}", style = MaterialTheme.typography.bodyMedium, color = DeepViolet, fontWeight = FontWeight.SemiBold)
                Text(text = "Submitted: ${com.example.pinpoint.data.UiText.formatTimestamp(report.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MutedText)
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
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("View original over Wi-Fi")
                    }
                }
            }

            // Community Urgency Support Card (PDF 5.2 / 5.4)
            SectionSurfaceCard(containerColor = VioletTint) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Community votes: ${report.voteCount}", style = MaterialTheme.typography.titleMedium, color = DeepViolet, fontWeight = FontWeight.Bold)
                        Text(
                            "3 votes raise urgency to Medium; 10 votes raise it to High.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText
                        )
                    }
                    Icon(Icons.Filled.ThumbUp, contentDescription = null, tint = Violet)
                }
                if (!report.pendingSync && report.status !in listOf("Resolved", "Rejected")) {
                    Button(
                        onClick = onVote,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasVoted) InkCard else Violet
                        )
                    ) {
                        Text(if (hasVoted) "Remove my urgency vote" else "Vote for urgency")
                    }
                }
            }

            // Auditable Status History Card (PDF 6.7)
            if (history.isNotEmpty()) {
                SectionSurfaceCard {
                    Text("Status history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Black)
                    history.forEach { change ->
                        Surface(shape = RoundedCornerShape(12.dp), color = PinPointSurface) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${change.previousStatus} → ${change.status}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = DeepViolet,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${com.example.pinpoint.data.UiText.formatTimestamp(change.createdAt)}: ${change.previousStatus} → ${change.status}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedText
                                )
                            }
                        }
                    }
                }
            }

            // Official Controls (assigned barangay officials only)
            if (official && !report.pendingSync) {
                var status by remember(report.id, report.status) { mutableStateOf(report.status) }
                var priority by remember(report.id, report.priority) { mutableStateOf(report.priority) }
                SectionSurfaceCard {
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
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Save status")
                    }
                }
            }
        }

        Button(
            onClick = onBackClick,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Back to My Reports")
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
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("$label: $selected")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(text = { Text(value) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}
