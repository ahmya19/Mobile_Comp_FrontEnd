package com.example.pinpoint.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.QueuedReport
import com.example.pinpoint.data.ReportDraft
import com.example.pinpoint.ui.theme.*

/** Offline / Saved Reports screen (PDF Board 7.1–7.4).
 *  Policy reconciliation: ONLY unsent local queue items (Queued/Failed) may be edited or deleted locally.
 *  Synced or actively Sending items remain immutable and protected. */
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

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete saved report?") },
            text = {
                Text("This removes only the unsent copy saved on this device and its local photo. Submitted reports on the server are never affected.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    confirmDelete = null
                    onDeleteItem(id)
                }) {
                    Text("Delete saved report", color = AlertRed)
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
            title = { Text("Edit saved report") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Category: ${target.draft.category}", style = MaterialTheme.typography.labelLarge, color = DeepViolet)
                    OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, minLines = 3)
                    OutlinedTextField(loc, { loc = it }, label = { Text("Location") }, singleLine = true)
                    OutlinedTextField(brgy, { brgy = it }, label = { Text("Barangay") }, singleLine = true)
                    err?.let { Text(it, color = AlertRed, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    try {
                        val updated = target.draft.copy(description = desc, location = loc, barangay = brgy)
                        updated.validate()
                        editing = null
                        onEditItem(target.id, updated)
                    } catch (e: IllegalArgumentException) {
                        err = e.message
                    }
                }) { Text("Save changes") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PinPointSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text("Saved reports", style = MaterialTheme.typography.headlineMedium, color = Black)

        Surface(shape = RoundedCornerShape(16.dp), color = AmberTint, border = BorderStroke(1.dp, Amber.copy(alpha = 0.4f))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.CloudOff, contentDescription = null, tint = Black)
                Text(
                    "Reports saved offline upload automatically when your connection returns. Full originals wait for unmetered Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Black
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRetryAll,
                enabled = !busy && items.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retry synchronization")
            }
            OutlinedButton(
                onClick = onSmsFallbackClick,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("SMS guide")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (items.isEmpty()) {
                item {
                    SectionSurfaceCard {
                        Text("All saved reports have finished uploading.", style = MaterialTheme.typography.bodyMedium, color = ResolvedGreen)
                    }
                }
            }
            items(items, key = { it.id }) { item ->
                val canModifyLocally = item.state in listOf("Queued", "Failed")
                SectionSurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.draft.category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Black)
                        StatusPill(
                            when (item.state) {
                                "Synced" -> "Pending"
                                "Failed" -> "Needs attention"
                                "Sending" -> "Sending"
                                else -> "Waiting to sync"
                            }
                        )
                    }
                    Text(item.draft.description, style = MaterialTheme.typography.bodyMedium, color = Black)
                    Text("${item.draft.location}, ${item.draft.barangay}", style = MaterialTheme.typography.bodySmall, color = MutedText)
                    item.originalState?.let {
                        Text("Original photo: $it", style = MaterialTheme.typography.labelSmall, color = DeepViolet)
                    }
                    item.error?.let { reason ->
                        Surface(shape = RoundedCornerShape(10.dp), color = RedTint) {
                            Text(com.example.pinpoint.data.UiText.humanize(reason), color = AlertRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                        }
                    }
                    if (canModifyLocally) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { editing = item },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Edit")
                            }
                            OutlinedButton(
                                onClick = { confirmDelete = item },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = AlertRed, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Delete", color = AlertRed)
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
    }
}
