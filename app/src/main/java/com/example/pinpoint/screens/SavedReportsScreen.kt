package com.example.pinpoint.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.QueuedReport
import com.example.pinpoint.data.ReportDraft
import com.example.pinpoint.data.UiText
import com.example.pinpoint.ui.theme.*

/** Offline / Saved Reports screen styled to PDF Board 7.1–7.4.
 *  Policy reconciliation: ONLY unsent local queue items (Queued/Failed) may be edited or deleted locally. */
@Composable
fun SavedReportsScreen(
    items: List<QueuedReport>,
    busy: Boolean,
    onBackClick: () -> Unit,
    onRetryAll: () -> Unit,
    onEditItem: (String, ReportDraft) -> Unit,
    onDeleteItem: (String) -> Unit,
    onSmsFallbackClick: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf<QueuedReport?>(null) }
    var editing by remember { mutableStateOf<QueuedReport?>(null) }

    val failedCount = items.count { it.state == "Failed" }
    val sendingCount = items.count { it.state == "Sending" }
    val syncedCount = items.count { it.state == "Synced" }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = White,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Delete saved report?", fontWeight = FontWeight.Bold) },
            text = {
                Text("This removes only the unsent copy saved on this device and its local photo. Submitted reports on the server are never affected.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = target.id
                        confirmDelete = null
                        onDeleteItem(id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed, contentColor = White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete saved report")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }

    editing?.let { target ->
        var desc by remember(target.id) { mutableStateOf(target.draft.description) }
        var loc by remember(target.id) { mutableStateOf(target.draft.location) }
        var brgy by remember(target.id) { mutableStateOf(target.draft.barangay) }
        var err by remember(target.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = White,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Edit saved report", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Category: ${target.draft.category}", style = MaterialTheme.typography.labelLarge, color = DeepViolet)
                    OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, minLines = 3, shape = RoundedCornerShape(14.dp), colors = pinPointFieldColors())
                    OutlinedTextField(loc, { loc = it }, label = { Text("Location") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = pinPointFieldColors())
                    OutlinedTextField(brgy, { brgy = it }, label = { Text("Barangay") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = pinPointFieldColors())
                    err?.let { Text(it, color = AlertRed, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val updated = target.draft.copy(description = desc, location = loc, barangay = brgy)
                            updated.validate()
                            editing = null
                            onEditItem(target.id, updated)
                        } catch (e: IllegalArgumentException) {
                            err = e.message
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Save changes") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PinPointTopBar(
            title = "Saved reports",
            onBackClick = onBackClick,
            trailing = {
                StatusPill(
                    when {
                        failedCount > 0 -> "$failedCount failed"
                        sendingCount > 0 -> "Sending"
                        items.isEmpty() -> "Uploaded"
                        else -> "Waiting to sync"
                    }
                )
            }
        )

        // Banner matching PDF 7.1 / 7.2
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (sendingCount > 0) GreenTint else AmberTint
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = if (sendingCount > 0) ResolvedGreen else AmberText,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = if (sendingCount > 0)
                        "Back online. Uploading your saved reports now."
                    else "Reports saved offline upload automatically when your connection returns. Full originals wait for unmetered Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Black
                )
            }
        }

        // Charcoal Sync Progress Hero Card when actively uploading (PDF 7.2)
        if (sendingCount > 0 && items.isNotEmpty()) {
            val pct = (syncedCount.toFloat() / items.size.coerceAtLeast(1)).coerceIn(0.15f, 1f)
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = InkCard) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            modifier = Modifier.size(52.dp).clip(CircleShape).background(Violet.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$syncedCount/${items.size}", style = MaterialTheme.typography.labelLarge, color = White, fontWeight = FontWeight.Bold)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Uploading reports", style = MaterialTheme.typography.titleMedium, color = White, fontWeight = FontWeight.Bold)
                            Text("Keep PinPoint open for the fastest sync.", style = MaterialTheme.typography.bodySmall, color = DarkBarText)
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(999.dp)).background(DarkTrack)) {
                        Box(modifier = Modifier.fillMaxWidth(pct).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(Violet))
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (items.isEmpty()) {
                item {
                    SectionSurfaceCard {
                        Text("All saved reports have finished uploading.", style = MaterialTheme.typography.bodyMedium, color = ResolvedGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Pending upload · ${items.size}", style = MaterialTheme.typography.titleSmall, color = Black, fontWeight = FontWeight.Bold)
                        Text("Auto-sync on", style = MaterialTheme.typography.labelSmall, color = MutedText)
                    }
                }
            }

            items(items, key = { it.id }) { item ->
                val canModifyLocally = item.state in listOf("Queued", "Failed")
                val isFailed = item.state == "Failed"
                SectionSurfaceCard(
                    containerColor = PinPointSurface,
                    border = if (isFailed) BorderStroke(1.dp, AlertRed.copy(alpha = 0.45f)) else null
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        CategoryThumbnailTile(item.draft.category, size = 50)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    item.draft.description,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Black,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                StatusPill(
                                    when (item.state) {
                                        "Synced" -> "Uploaded"
                                        "Failed" -> "Needs attention"
                                        "Sending" -> "Sending"
                                        else -> "Waiting to sync"
                                    }
                                )
                            }
                            Text("${item.draft.category} · ${item.draft.location}, ${item.draft.barangay}", style = MaterialTheme.typography.bodySmall, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.originalState?.let {
                                Text("Original photo: $it", style = MaterialTheme.typography.labelSmall, color = DeepViolet)
                            }
                        }
                    }

                    item.error?.let { reason ->
                        Surface(shape = RoundedCornerShape(12.dp), color = RedTint) {
                            Text(UiText.humanize(reason), color = AlertRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(10.dp))
                        }
                    }

                    if (canModifyLocally) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                modifier = Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy) { editing = item },
                                shape = RoundedCornerShape(12.dp),
                                color = White
                            ) {
                                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Edit, contentDescription = null, tint = Black, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Edit", style = MaterialTheme.typography.labelLarge, color = Black)
                                }
                            }
                            Surface(
                                modifier = Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy) { confirmDelete = item },
                                shape = RoundedCornerShape(12.dp),
                                color = White
                            ) {
                                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = AlertRed, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Delete", style = MaterialTheme.typography.labelLarge, color = AlertRed)
                                }
                            }
                        }
                    } else {
                        Text(
                            "Already committed or actively uploading — preserved as evidence.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedText
                        )
                    }
                }
            }
        }

        // Bottom primary retry + SMS fallback controls (PDF 7.1 / 7.3)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRetryAll,
                enabled = !busy && items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Retry synchronization", fontWeight = FontWeight.Bold)
            }
            Surface(
                modifier = Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onSmsFallbackClick),
                shape = RoundedCornerShape(14.dp),
                color = PinPointSurface
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("SMS guide (no data fallback)", style = MaterialTheme.typography.labelLarge, color = Black)
                }
            }
        }
    }
}
