package com.example.pinpoint.screens

import android.net.Uri
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
    onSubmitClick: (ReportDraft, Uri?) -> Unit
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

                DropdownMenuItem(
                    text = {
                        Text("Road Damage")
                    },
                    onClick = {
                        category = "Road Damage"
                        categoryExpanded = false
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text("Streetlight")
                    },
                    onClick = {
                        category = "Streetlight"
                        categoryExpanded = false
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text("Flooding")
                    },
                    onClick = {
                        category = "Flooding"
                        categoryExpanded = false
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text("Garbage")
                    },
                    onClick = {
                        category = "Garbage"
                        categoryExpanded = false
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text("Other")
                    },
                    onClick = {
                        category = "Other"
                        categoryExpanded = false
                    }
                )
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
        Text("Enter both coordinates if known. A map pin picker will be added by the mobile team.")
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
                    onSubmitClick(draft, selectedImage)
                } catch (error: IllegalArgumentException) { validationError = error.message }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Saving report..." else "Submit Report")
        }
    }
}
