package com.example.pinpoint.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.CATEGORIES
import com.example.pinpoint.data.ReportFilter
import com.example.pinpoint.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Filter bar styled to PDF Board 4.5 & 5.5 while preserving exact test selectors ("Filter reports", "All Category", "Barangay (exact name)", "Apply filters"). */
@Composable
fun ReportFilters(filter: ReportFilter, onApply: (ReportFilter) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var barangay by rememberSaveable { mutableStateOf(filter.barangay.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(filter.category.orEmpty()) }
    var status by rememberSaveable { mutableStateOf(filter.status.orEmpty()) }
    var from by rememberSaveable { mutableStateOf("") }
    var until by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val activeFilters = listOfNotNull(
        filter.barangay?.takeIf { it.isNotBlank() },
        filter.category?.takeIf { it.isNotBlank() },
        filter.status?.takeIf { it.isNotBlank() },
        filter.after,
        filter.before
    ).size

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { expanded = !expanded },
            shape = RoundedCornerShape(999.dp),
            color = if (expanded || activeFilters > 0) InkChip else PinPointSurface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = null,
                    tint = if (expanded || activeFilters > 0) White else Black,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (expanded) "Hide filters" else "Filter reports",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (expanded || activeFilters > 0) White else Black,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (activeFilters > 0 && !expanded) {
            Text(
                text = "Clear",
                style = MaterialTheme.typography.labelMedium,
                color = DeepViolet,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    barangay = ""; category = ""; status = ""; from = ""; until = ""; error = null
                    onApply(ReportFilter(mine = filter.mine))
                }.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    if (expanded) {
        SectionSurfaceCard {
            OutlinedTextField(
                value = barangay,
                onValueChange = { barangay = it },
                label = { Text("Barangay (exact name)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = White,
                    unfocusedContainerColor = White,
                    focusedBorderColor = Violet,
                    unfocusedBorderColor = SoftBorder
                )
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChoice("Category", category, CATEGORIES, Modifier.weight(1f)) { category = it }
                FilterChoice("Status", status, listOf("Pending", "Under Review", "In Progress", "Resolved", "Rejected"), Modifier.weight(1f)) { status = it }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it },
                    label = { Text("From YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = White,
                        unfocusedContainerColor = White,
                        focusedBorderColor = Violet,
                        unfocusedBorderColor = SoftBorder
                    )
                )
                OutlinedTextField(
                    value = until,
                    onValueChange = { until = it },
                    label = { Text("To YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = White,
                        unfocusedContainerColor = White,
                        focusedBorderColor = Violet,
                        unfocusedBorderColor = SoftBorder
                    )
                )
            }
            error?.let { Text(it, color = AlertRed, style = MaterialTheme.typography.bodySmall) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        try {
                            val after = dateBoundary(from, false)
                            val before = dateBoundary(until, true)
                            require(after == null || before == null || after <= before) { "The end date must follow the start date." }
                            onApply(
                                filter.copy(
                                    barangay = barangay.trim().ifBlank { null },
                                    category = category.ifBlank { null },
                                    status = status.ifBlank { null },
                                    after = after,
                                    before = before
                                )
                            )
                            error = null; expanded = false
                        } catch (failure: IllegalArgumentException) {
                            error = failure.message
                        }
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Apply filters", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        barangay = ""; category = ""; status = ""; from = ""; until = ""; error = null
                        onApply(ReportFilter(mine = filter.mine))
                    },
                    modifier = Modifier.height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Clear")
                }
            }
        }
    }
}

@Composable
private fun FilterChoice(label: String, value: String, values: List<String>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (value.isNotBlank()) InkChip else White,
                contentColor = if (value.isNotBlank()) White else Black
            )
        ) {
            Text(value.ifBlank { "All $label" }, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf("") + values).forEach { option ->
                DropdownMenuItem(text = { Text(option.ifBlank { "All $label" }) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/** Calendar dates use the phone's zone, then become UTC query boundaries. */
private fun dateBoundary(value: String, end: Boolean): String? {
    if (value.isBlank()) return null
    require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Use YYYY-MM-DD for dates." }
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
    val parsed = try { parser.parse(value) } catch (_: Exception) { null }
    requireNotNull(parsed) { "Enter a valid calendar date." }
    val calendar = Calendar.getInstance().apply { time = parsed }
    if (end) { calendar.add(Calendar.DAY_OF_MONTH, 1); calendar.add(Calendar.MILLISECOND, -1) }
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(calendar.time)
}
