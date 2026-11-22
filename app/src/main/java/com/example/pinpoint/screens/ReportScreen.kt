package com.example.pinpoint.screens

import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.height
import com.example.pinpoint.data.Report
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.ReportDraft

@Composable
fun ReportScreen(
    onBackClick: () -> Unit,
    busy: Boolean,
    onSubmitClick: (ReportDraft, Uri?, Boolean) -> Unit
) {

    var category by rememberSaveable {
        mutableStateOf("")
    }

    var categoryExpanded by remember {
        mutableStateOf(false)
    }

    var description by rememberSaveable {
        mutableStateOf("")
    }

    var location by rememberSaveable {
        mutableStateOf("")
    }

    var selectedImage by rememberSaveable {
        mutableStateOf<Uri?>(null)
    }

    var barangay by rememberSaveable { mutableStateOf("") }
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    var mapExpanded by rememberSaveable { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var keepOriginal by rememberSaveable { mutableStateOf(true) }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    fun locate() {
        scope.launch {
            locating=true
            val cancellation=CancellationTokenSource()
            try {
                val found=withTimeout(20000) { LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,cancellation.token).await() }
                requireNotNull(found) { "No location fix. You can choose the pin on the map instead." }
                latitude=found.latitude.toString();longitude=found.longitude.toString()
                validationError="GPS accuracy: about ${found.accuracy.toInt()} m. Review the pin before submitting."
                mapExpanded=true
            } catch(error: Exception) { validationError=error.message ?: "Location request failed. Choose a map pin instead." }
            finally { locating=false;cancellation.cancel() }
        }
    }
    val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if(granted[Manifest.permission.ACCESS_FINE_LOCATION]==true || granted[Manifest.permission.ACCESS_COARSE_LOCATION]==true) locate()
        else validationError="Location permission was declined. You can still choose a map pin."
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { imageUri ->
        selectedImage = imageUri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // Back button
        IconButton(
            onClick = onBackClick,
            enabled = !busy
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back"
            )
        }

        // Title
        Text(
            text = "Create a Report",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Tell us about an issue in your community.",
            style = MaterialTheme.typography.bodyLarge
        )

        // Category
        Column {

            OutlinedButton(
                onClick = {
                    categoryExpanded = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = category.ifEmpty {
                        "Select a Category"
                    }
                )
            }

            DropdownMenu(
                expanded = categoryExpanded,
                onDismissRequest = {
                    categoryExpanded = false
                }
            ) {

                com.example.pinpoint.data.CATEGORIES.forEach { option ->
                    DropdownMenuItem(text={Text(option)},onClick={category=option;categoryExpanded=false})
                }
            }
        }

        // Description
        OutlinedTextField(
            value = description,
            onValueChange = {
                description = it
            },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Description")
            },
            placeholder = {
                Text("Describe the issue...")
            },
            minLines = 5
        )

        // Location
        OutlinedTextField(
            value = location,
            onValueChange = {
                location = it
            },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Location")
            },
            placeholder = {
                Text("Enter the location of the issue")
            }
        )

        // Add Photo
        OutlinedTextField(value = barangay, onValueChange = { barangay = it },
            label = { Text("Barangay") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(value = latitude, onValueChange = { latitude = it },
            label = { Text("Latitude (optional)") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(value = longitude, onValueChange = { longitude = it },
            label = { Text("Longitude (optional)") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedButton(onClick={
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) locate()
            else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
        },enabled=!busy && !locating,modifier=Modifier.fillMaxWidth()) { Text(if(locating) "Finding location..." else "Use my current location") }
        OutlinedButton(onClick={mapExpanded=!mapExpanded},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Choose or adjust map pin") }
        if(mapExpanded) {
            Text("Press and hold the map to set the issue location.")
            val lat=latitude.toDoubleOrNull();val lon=longitude.toDoubleOrNull()
            ReportMap(reports=if(lat!=null && lon!=null && lat in -90.0..90.0 && lon in -180.0..180.0)
                listOf(Report(id="draft",description="Issue location",latitude=lat,longitude=lon)) else emptyList(),
                modifier=Modifier.fillMaxWidth().height(260.dp),onPick={newLat,newLon->latitude=newLat.toString();longitude=newLon.toString()})
            Text("© OpenStreetMap contributors")
        }
        OutlinedButton(
            onClick = {
                imagePickerLauncher.launch("image/*")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (selectedImage == null) {
                    "Add Photo"
                } else {
                    "Change Photo"
                }
            )
        }

        // Photo selected message
        if (selectedImage != null) {
            Text(
                text = "Photo selected successfully",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=keepOriginal,onCheckedChange={keepOriginal=it})
                Text("Also upload original on unmetered Wi-Fi (up to 10 MB)")
            }
            Text("The compressed photo syncs on any connection. Originals are private to you and assigned officials.")
        }

        // Submit Report
        validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                try {
                    require(latitude.isBlank() || latitude.toDoubleOrNull() != null) { "Latitude must be a number." }
                    require(longitude.isBlank() || longitude.toDoubleOrNull() != null) { "Longitude must be a number." }
                    val draft = ReportDraft(category, description, location, barangay,
                        latitude.toDoubleOrNull(), longitude.toDoubleOrNull())
                    draft.validate()
                    validationError = null
                    onSubmitClick(draft, selectedImage, keepOriginal)
                } catch (error: IllegalArgumentException) { validationError = error.message }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Saving report..." else "Submit Report")
        }
    }
}
