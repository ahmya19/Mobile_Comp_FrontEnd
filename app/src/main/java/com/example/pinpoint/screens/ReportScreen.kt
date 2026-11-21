package com.example.pinpoint.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ReportScreen(
    onBackClick: () -> Unit,
    onSubmitClick: () -> Unit
) {

    var category by remember {
        mutableStateOf("")
    }

    var categoryExpanded by remember {
        mutableStateOf(false)
    }

    var description by remember {
        mutableStateOf("")
    }

    var location by remember {
        mutableStateOf("")
    }

    var selectedImage by remember {
        mutableStateOf<Uri?>(null)
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { imageUri ->
        selectedImage = imageUri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // Back button
        IconButton(
            onClick = onBackClick
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
        Button(
            onClick = onSubmitClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Submit Report")
        }
    }
}