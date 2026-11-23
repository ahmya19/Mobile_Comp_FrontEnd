package com.example.pinpoint.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.pinpoint.data.CATEGORIES
import com.example.pinpoint.data.CameraCaptureHelper
import com.example.pinpoint.data.Report
import com.example.pinpoint.data.ReportDraft
import com.example.pinpoint.data.SavedFormDraft
import com.example.pinpoint.ui.theme.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

private val QUICK_CATEGORY_CHIPS = listOf(
    "Road Damage" to "Road",
    "Garbage" to "Trash",
    "Streetlight" to "Lights",
    "Flooding" to "Flood",
    "Water Interruption" to "Water",
    "Other" to "Other issue"
)

@Composable
fun ReportScreen(
    onBackClick: () -> Unit,
    busy: Boolean,
    onSubmitClick: (ReportDraft, Uri?, Boolean) -> Unit,
    initialCategory: String = "",
    savedDraft: SavedFormDraft? = null,
    onSaveDraftClick: (String, String, String, String, String, String, Boolean, Uri?) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onDiscardDraftClick: () -> Unit = {}
) {
    var category by rememberSaveable { mutableStateOf(initialCategory) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var description by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var selectedImage by rememberSaveable { mutableStateOf<Uri?>(null) }
    var barangay by rememberSaveable { mutableStateOf("") }
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    var mapExpanded by rememberSaveable { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var keepOriginal by rememberSaveable { mutableStateOf(true) }
    var draftRestored by rememberSaveable { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var activeCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Restore an account-isolated saved draft once when opening an empty form.
    LaunchedEffect(savedDraft?.updatedAt) {
        if (!draftRestored && savedDraft != null &&
            category.isBlank() && description.isBlank() && location.isBlank() && barangay.isBlank() && selectedImage == null
        ) {
            category = savedDraft.category
            description = savedDraft.description
            location = savedDraft.location
            barangay = savedDraft.barangay
            latitude = savedDraft.latitude
            longitude = savedDraft.longitude
            keepOriginal = savedDraft.keepOriginal
            selectedImage = savedDraft.photoFile?.let { File(it).takeIf { f -> f.isFile }?.let(Uri::fromFile) }
            draftRestored = true
        }
    }

    // Decode a small bounded thumbnail whenever the selected photo changes.
    LaunchedEffect(selectedImage) {
        val uri = selectedImage
        previewBitmap = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }?.asImageBitmap()
            }.getOrNull()
        }
    }

    fun locate() {
        scope.launch {
            locating = true
            val cancellation = CancellationTokenSource()
            try {
                val found = withTimeout(20000) {
                    LocationServices.getFusedLocationProviderClient(context)
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token).await()
                }
                requireNotNull(found) { "No location fix. You can choose the pin on the map instead." }
                latitude = found.latitude.toString()
                longitude = found.longitude.toString()
                validationError = "GPS accuracy: about ${found.accuracy.toInt()} m. Review the pin before submitting."
                mapExpanded = true
            } catch (error: Exception) {
                validationError = error.message ?: "Location request failed. Choose a map pin instead."
            } finally {
                locating = false
                cancellation.cancel()
            }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locate()
        else validationError = "Location permission was declined. You can still choose a map pin."
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val capturedUri = pendingCameraUri
        val capturedPath = pendingCameraPath
        pendingCameraUri = null
        pendingCameraPath = null
        if (captured && capturedUri != null && capturedPath != null && File(capturedPath).let { it.isFile && it.length() > 0L }) {
            if (activeCameraPath != null && activeCameraPath != capturedPath) {
                CameraCaptureHelper.deleteCaptureFile(context, activeCameraPath)
            }
            activeCameraPath = capturedPath
            selectedImage = capturedUri
            validationError = null
        } else {
            // Cancelled or failed capture: remove unused temporary file immediately.
            CameraCaptureHelper.deleteCaptureFile(context, capturedPath)
            if (!captured) validationError = "Camera capture cancelled."
        }
    }

    fun launchSystemCamera() {
        try {
            val (file, uri) = CameraCaptureHelper.createCaptureTarget(context)
            pendingCameraPath = file.absolutePath
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } catch (error: Exception) {
            CameraCaptureHelper.deleteCaptureFile(context, pendingCameraPath)
            pendingCameraPath = null
            pendingCameraUri = null
            validationError = error.message ?: "Camera is unavailable on this device. Choose a gallery photo instead."
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchSystemCamera()
        else validationError = "Camera permission was declined. You can still choose a photo from your gallery."
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { imageUri ->
        if (imageUri != null) {
            CameraCaptureHelper.deleteCaptureFile(context, activeCameraPath)
            activeCameraPath = null
            selectedImage = imageUri
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PinPointSurface)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBackClick, enabled = !busy) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            TextButton(
                onClick = {
                    onSaveDraftClick(category, description, location, barangay, latitude, longitude, keepOriginal, selectedImage)
                },
                enabled = !busy && (category.isNotBlank() || description.isNotBlank() || location.isNotBlank() || selectedImage != null)
            ) {
                Text("Save as draft")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Create a Report", style = MaterialTheme.typography.headlineMedium, color = Black)
            Text(text = "Tell us about an issue in your community.", style = MaterialTheme.typography.bodyMedium, color = MutedText)
        }

        if (savedDraft != null) {
            Surface(shape = RoundedCornerShape(14.dp), color = VioletTint, border = BorderStroke(1.dp, SoftBorder)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (draftRestored) "Saved draft restored" else "A saved draft exists for this account",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeepViolet,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            CameraCaptureHelper.deleteCaptureFile(context, activeCameraPath)
                            activeCameraPath = null
                            category = ""; description = ""; location = ""; barangay = ""
                            latitude = ""; longitude = ""; selectedImage = null; draftRestored = false
                            onDiscardDraftClick()
                        },
                        enabled = !busy
                    ) {
                        Text("Discard draft", color = AlertRed)
                    }
                }
            }
        }

        // Step 1: Category section (preserves exact "Select a Category" dropdown + non-colliding quick tiles)
        SectionSurfaceCard {
            Text("1. Category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Black)
            QUICK_CATEGORY_CHIPS.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (fullName, shortLabel) ->
                        val selected = category == fullName
                        Surface(
                            modifier = Modifier.weight(1f).clickable(enabled = !busy) { category = fullName },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) VioletTint else PinPointSurface,
                            border = BorderStroke(1.5.dp, if (selected) Violet else SoftBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CategoryIconBadge(fullName, size = 30)
                                Text(
                                    shortLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) DeepViolet else Black,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
            Column {
                OutlinedButton(
                    onClick = { categoryExpanded = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(text = category.ifEmpty { "Select a Category" })
                }
                DropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false }
                ) {
                    CATEGORIES.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { category = option; categoryExpanded = false }
                        )
                    }
                }
            }
        }

        // Step 2: Photo Evidence (Real System Camera + System Gallery Picker)
        SectionSurfaceCard {
            Text("2. Photo evidence", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Black)
            Text(
                "Take a fresh photo with your camera or pick one from your gallery. Avoid capturing faces or license plates.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            launchSystemCamera()
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Take Photo")
                }
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(text = if (selectedImage == null) "Add Photo" else "Change Photo")
                }
            }

            if (selectedImage != null) {
                previewBitmap?.let { bmp ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(PinPointSurface)
                    ) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "Selected photo preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        IconButton(
                            onClick = {
                                CameraCaptureHelper.deleteCaptureFile(context, activeCameraPath)
                                activeCameraPath = null
                                selectedImage = null
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(InkCard.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove photo", tint = White)
                        }
                    }
                }
                Text(
                    text = "Photo selected successfully",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ResolvedGreen,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keepOriginal, onCheckedChange = { keepOriginal = it }, enabled = !busy)
                    Text("Also upload original on unmetered Wi-Fi (up to 10 MB)", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "The compressed photo syncs on any connection. Originals are private to you and assigned officials.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText
                )
            }
        }

        // Step 3: Details
        SectionSurfaceCard {
            Text("3. Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Black)
            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 2000) description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Description") },
                placeholder = { Text("Describe the issue...") },
                supportingText = { Text("${description.trim().length} / 2000 (minimum 10 characters)") },
                minLines = 4,
                enabled = !busy,
                shape = RoundedCornerShape(14.dp)
            )
        }

        // Step 4: Location & Map Pin
        SectionSurfaceCard {
            Text("4. Location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Black)
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Location") },
                placeholder = { Text("Enter the location of the issue") },
                enabled = !busy,
                shape = RoundedCornerShape(14.dp)
            )
            OutlinedTextField(
                value = barangay,
                onValueChange = { barangay = it },
                label = { Text("Barangay") },
                placeholder = { Text("Alangilan") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it },
                    label = { Text("Latitude (optional)") },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it },
                    label = { Text("Longitude (optional)") },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }
            OutlinedButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    ) locate()
                    else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                enabled = !busy && !locating,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (locating) "Finding location..." else "Use my current location")
            }
            OutlinedButton(
                onClick = { mapExpanded = !mapExpanded },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Choose or adjust map pin")
            }
            if (mapExpanded) {
                Text("Press and hold the map to set the issue location.", style = MaterialTheme.typography.bodySmall, color = MutedText)
                val lat = latitude.toDoubleOrNull()
                val lon = longitude.toDoubleOrNull()
                ReportMap(
                    reports = if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0)
                        listOf(Report(id = "draft", description = "Issue location", latitude = lat, longitude = lon))
                    else emptyList(),
                    modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)),
                    onPick = { newLat, newLon -> latitude = newLat.toString(); longitude = newLon.toString() }
                )
                Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = MutedText)
            }
        }

        validationError?.let {
            Surface(shape = RoundedCornerShape(12.dp), color = RedTint) {
                Text(it, color = AlertRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
        }

        Button(
            onClick = {
                try {
                    require(latitude.isBlank() || latitude.toDoubleOrNull() != null) { "Latitude must be a number." }
                    require(longitude.isBlank() || longitude.toDoubleOrNull() != null) { "Longitude must be a number." }
                    val draft = ReportDraft(
                        category, description, location, barangay,
                        latitude.toDoubleOrNull(), longitude.toDoubleOrNull()
                    )
                    draft.validate()
                    validationError = null
                    onSubmitClick(draft, selectedImage, keepOriginal)
                } catch (error: IllegalArgumentException) {
                    validationError = error.message
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(if (busy) "Saving report..." else "Submit Report", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
    }
}
