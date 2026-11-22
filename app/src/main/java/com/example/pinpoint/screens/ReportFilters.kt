package com.example.pinpoint.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.ReportFilter
import com.example.pinpoint.data.CATEGORIES
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@Composable
fun ReportFilters(filter: ReportFilter,onApply: (ReportFilter)->Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var barangay by rememberSaveable { mutableStateOf(filter.barangay.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(filter.category.orEmpty()) }
    var status by rememberSaveable { mutableStateOf(filter.status.orEmpty()) }
    var from by rememberSaveable { mutableStateOf("") }
    var until by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "Hide filters" else "Filter reports") }
    if(expanded) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value=barangay,onValueChange={barangay=it},label={Text("Barangay (exact name)")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChoice("Category",category,CATEGORIES) { category=it }
            FilterChoice("Status",status,listOf("Pending","Under Review","In Progress","Resolved","Rejected")) { status=it }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=from,onValueChange={from=it},label={Text("From YYYY-MM-DD")},singleLine=true,modifier=Modifier.weight(1f))
            OutlinedTextField(value=until,onValueChange={until=it},label={Text("To YYYY-MM-DD")},singleLine=true,modifier=Modifier.weight(1f))
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Row {
            Button(onClick={
                try {
                    val after=dateBoundary(from,false)
                    val before=dateBoundary(until,true)
                    require(after==null || before==null || after<=before) { "The end date must follow the start date." }
                    onApply(filter.copy(barangay=barangay.trim().ifBlank{null},category=category.ifBlank{null},
                        status=status.ifBlank{null},after=after,before=before))
                    error=null;expanded=false
                } catch(failure: IllegalArgumentException) { error=failure.message }
            }) { Text("Apply filters") }
            TextButton(onClick={barangay="";category="";status="";from="";until="";error=null;onApply(ReportFilter(mine=filter.mine))}) { Text("Clear") }
        }
    }
}

@Composable
private fun FilterChoice(label: String,value: String,values: List<String>,onSelect: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={expanded=true}) { Text(value.ifBlank { "All $label" }) }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            (listOf("")+values).forEach { option ->
                DropdownMenuItem(text={Text(option.ifBlank { "All $label" })},onClick={onSelect(option);expanded=false})
            }
        }
    }
}

/** Calendar dates use the phone's zone, then become UTC query boundaries. */
private fun dateBoundary(value: String,end: Boolean): String? {
    if(value.isBlank()) return null
    require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Use YYYY-MM-DD for dates." }
    val parser=SimpleDateFormat("yyyy-MM-dd",Locale.US).apply { isLenient=false }
    val parsed=try { parser.parse(value) } catch(_: Exception) { null }
    requireNotNull(parsed) { "Enter a valid calendar date." }
    val calendar=Calendar.getInstance().apply { time=parsed }
    if(end) { calendar.add(Calendar.DAY_OF_MONTH,1);calendar.add(Calendar.MILLISECOND,-1) }
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply { timeZone=TimeZone.getTimeZone("UTC") }.format(calendar.time)
}
