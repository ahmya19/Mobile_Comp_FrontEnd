package com.example.pinpoint.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.VioletTint
import com.example.pinpoint.data.Report

@Composable
fun ReportDetailsScreen(
    onBackClick: () -> Unit,
    report: Report?,
    loading: Boolean,
    error: String?,
    photo: ImageBitmap?,
    official: Boolean,
    busy: Boolean,
    onUpdateStatus: (String, String) -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        IconButton(
            onClick = onBackClick
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = "Back"
            )
        }

        Text(
            text = "Report Details",
            style = MaterialTheme.typography.headlineMedium
        )

        if (loading) Text("Loading report...")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (report != null) Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = VioletTint
            )
        ) {

            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                Text(
                    text = report.title,
                    style = MaterialTheme.typography.titleLarge
                )

                Text(
                    text = "Category: ${report.category}"
                )

                Text(
                    text = "Location: ${report.location}, ${report.barangay}"
                )

                Text(
                    text = "Status: ${report.status} | Priority: ${report.priority}"
                )

                Text(
                    text = "Submitted: ${report.createdAt}"
                )

                Text(
                    text = "Description: ${report.description}"
                )
                report.latitude?.let { Text("Coordinates: $it, ${report.longitude}") }
                if (photo != null) Image(photo, "Report photo", modifier = Modifier.fillMaxWidth())
                if (official) {
                    var status by remember(report.id, report.status) { mutableStateOf(report.status) }
                    var priority by remember(report.id, report.priority) { mutableStateOf(report.priority) }
                    Text("Official controls", style = MaterialTheme.typography.titleMedium)
                    ChoiceMenu("Status", status, listOf("Pending", "Under Review", "In Progress", "Resolved", "Rejected")) { status = it }
                    ChoiceMenu("Priority", priority, listOf("Low", "Medium", "High")) { priority = it }
                    Button(onClick = { onUpdateStatus(status, priority) }, enabled = !busy) { Text("Save status") }
                }
            }
        }

        Button(
            onClick = onBackClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to My Reports")
        }
    }
}

@Composable
private fun ChoiceMenu(label: String, selected: String, values: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: $selected") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(text = { Text(value) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}
