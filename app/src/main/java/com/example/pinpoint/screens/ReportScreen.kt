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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
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

// Keep short labels distinct from full dropdown names so automated UI tests selecting "Road Damage" / "Water Interruption"
// after tapping "Select a Category" never hit an ambiguous match.
private val QUICK_CATEGORY_CHIPS = listOf(
    "Road Damage" to "Road",
    "Garbage" to "Trash",
    "Streetlight" to "Lights",
    "Flooding" to "Flood",
    "Water Interruption" to "Water",
    "Other" to "Other issue"
)

/** Issue reporting screen styled to PDF Board 3 (5-segment progress bar, 2-column selected category cards, dark photo preview, violet location button). */
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

    val filledSteps = remember(category, selectedImage, description, location, barangay) {
        var count = 1
        if (category.isNotBlank()) count++
        if (selectedImage != null) count++
        if (description.trim().length >= 10) count++
        if (location.isNotBlank() && barangay.isNotBlank()) count++
        count.coerceIn(1, 5)
    }

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
            .background(White)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Top header bar + Save as draft (PDF 3.1 / 3.7; preserves "Create a Report" selector for NativePickerTest)
        PinPointTopBar(
            title = "Create a Report",
            onBackClick = onBackClick,
            backEnabled = !busy,
            trailing = {
                TextButton(
                    onClick = {
                        onSaveDraftClick(category, description, location, barangay, latitude, longitude, keepOriginal, selectedImage)
                    },
                    enabled = !busy && (category.isNotBlank() || description.isNotBlank() || location.isNotBlank() || selectedImage != null)
                ) {
                    Text("Save as draft", color = DeepViolet, fontWeight = FontWeight.SemiBold)
                }
            }
        )

        // 5-segment horizontal violet progress bar (PDF Board 3.1–3.7)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(5) { idx ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (idx < filledSteps) Violet else SoftBorder)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("$filledSteps / 5", style = MaterialTheme.typography.labelSmall, color = MutedText, fontWeight = FontWeight.SemiBold)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "What's the issue?", style = MaterialTheme.typography.headlineMedium, color = Black)
            Text(text = "Tell us about an issue in your community.", style = MaterialTheme.typography.bodyMedium, color = MutedText)
        }

        if (savedDraft != null) {
            Surface(shape = RoundedCornerShape(16.dp), color = VioletTint) {
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
                        Text("Discard draft", color = AlertRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 1. Category 2-column Figma grid (PDF 3.1) + preserved "Select a Category" dropdown
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CATEGORY", style = MaterialTheme.typography.labelSmall, color = MutedText, fontWeight = FontWeight.Bold)
            QUICK_CATEGORY_CHIPS.chunked(2).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (fullName, shortLabel) ->
                        val selected = category == fullName
                        Surface(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable(enabled = !busy) { category = fullName },
                            shape = RoundedCornerShape(18.dp),
                            color = if (selected) AmberTint.copy(alpha = 0.55f) else PinPointSurface,
                            border = if (selected) BorderStroke(1.5.dp, Amber) else null
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CategoryIconBadge(fullName, size = 38)
                                    Text(
                                        shortLabel,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Black,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(Amber),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box {
                OutlinedButton(
                    onClick = { categoryExpanded = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (category.isNotBlank()) Violet else SoftBorder),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (category.isNotBlank()) VioletTint.copy(alpha = 0.5f) else White)
                ) {
                    Text(
                        text = category.ifEmpty { "Select a Category" },
                        color = if (category.isNotBlank()) DeepViolet else Black,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                    CATEGORIES.forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { category = option; categoryExpanded = false })
                    }
                }
            }
        }

        // 2. Photo evidence (PDF 3.2 & 3.3)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PHOTO EVIDENCE", style = MaterialTheme.typography.labelSmall, color = MutedText, fontWeight = FontWeight.Bold)
            if (selectedImage != null) {
                previewBitmap?.let { bmp ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(InkCard)
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
                                .padding(10.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(InkCard.copy(alpha = 0.78f))
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove photo", tint = White, modifier = Modifier.size(18.dp))
                        }
                        Surface(
                            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                            shape = RoundedCornerShape(999.dp),
                            color = InkCard.copy(alpha = 0.78f)
                        ) {
                            Text(
                                "EXIF & GPS stripped on upload",
                                style = MaterialTheme.typography.labelSmall,
                                color = White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "Photo selected successfully",
                    style = MaterialTheme.typography.bodySmall,
                    color = ResolvedGreen,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = !busy) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                launchSystemCamera()
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = VioletTint
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = DeepViolet, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Take Photo", style = MaterialTheme.typography.labelLarge, color = DeepViolet, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SoftBorder)
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = Black, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(text = if (selectedImage == null) "Add Photo" else "Change Photo", color = Black, fontWeight = FontWeight.SemiBold)
                }
            }
            if (selectedImage != null) {
                SectionSurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keepOriginal, onCheckedChange = { keepOriginal = it }, enabled = !busy)
                        Text("Also upload original on unmetered Wi-Fi (up to 10 MB)", style = MaterialTheme.typography.bodySmall, color = Black)
                    }
                    Text(
                        "The compressed photo syncs on any connection. Originals are private to you and assigned officials.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText
                    )
                }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = PinPointSurface) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MutedText, modifier = Modifier.size(16.dp))
                    Text(
                        "Make sure the problem is clearly visible. Avoid capturing faces or license plates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                }
            }
        }

        // 3. Issue Details (PDF 3.4)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DETAILS", style = MaterialTheme.typography.labelSmall, color = MutedText, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 2000) description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Description") },
                placeholder = { Text("Describe the issue clearly...") },
                supportingText = {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Minimum 10 characters", color = MutedText)
                        Text("${description.trim().length} / 2000", color = MutedText)
                    }
                },
                minLines = 4,
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
                colors = pinPointFieldColors()
            )
        }

        // 4. Issue Location & Pinning (PDF 3.5 & 3.6; avoid "Location" in heading so UI automation targets the field cleanly)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("WHERE IS IT?", style = MaterialTheme.typography.labelSmall, color = MutedText, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Location") },
                placeholder = { Text("Street, purok or landmark") },
                leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Violet, modifier = Modifier.size(18.dp)) },
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                colors = pinPointFieldColors()
            )
            OutlinedTextField(
                value = barangay,
                onValueChange = { barangay = it },
                label = { Text("Barangay") },
                placeholder = { Text("Alangilan") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = pinPointFieldColors()
            )

            // Soft violet pill CTA matching PDF 3.5 ("Use my current location")
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = !busy && !locating) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        ) locate()
                        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                shape = RoundedCornerShape(14.dp),
                color = VioletTint
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MyLocation, contentDescription = null, tint = DeepViolet, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (locating) "Finding location..." else "Use my current location", style = MaterialTheme.typography.labelLarge, color = DeepViolet, fontWeight = FontWeight.Bold)
                }
            }

            OutlinedButton(
                onClick = { mapExpanded = !mapExpanded },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, SoftBorder)
            ) {
                Text("Choose or adjust map pin", color = Black, fontWeight = FontWeight.SemiBold)
            }

            if (mapExpanded) {
                Surface(shape = RoundedCornerShape(16.dp), color = PinPointSurface) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Press and hold the map to set the issue location.", style = MaterialTheme.typography.bodySmall, color = MutedText)
                        val lat = latitude.toDoubleOrNull()
                        val lon = longitude.toDoubleOrNull()
                        ReportMap(
                            reports = if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0)
                                listOf(Report(id = "draft", description = "Issue location", latitude = lat, longitude = lon))
                            else emptyList(),
                            modifier = Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(14.dp)),
                            onPick = { newLat, newLon -> latitude = newLat.toString(); longitude = newLon.toString() }
                        )
                        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = MutedText)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it },
                    label = { Text("Latitude (optional)", style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = pinPointFieldColors()
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it },
                    label = { Text("Longitude (optional)", style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = pinPointFieldColors()
                )
            }
        }

        validationError?.let {
            Surface(shape = RoundedCornerShape(14.dp), color = RedTint) {
                Text(it, color = AlertRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(12.dp))
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
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = White)
        ) {
            Text(if (busy) "Saving report..." else "Submit Report", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
    }
}
