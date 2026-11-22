package com.example.pinpoint.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.VioletTint
import com.example.pinpoint.data.Citizen

@Composable
fun ProfileScreen(
    onHomeClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMapClick: () -> Unit,
    user: Citizen,
    busy: Boolean,
    onSaveProfile: (String) -> Unit,
    onSignOut: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var name by remember(user.displayName) { mutableStateOf(user.displayName) }

    Scaffold(
        bottomBar = {
            PinPointBottomBar(
                selectedRoute = "profile",
                onHomeClick = onHomeClick,
                onReportsClick = onReportsClick,
                onMapClick = onMapClick,
                onProfileClick = {}
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Text(
                text = "Profile",
                style = MaterialTheme.typography.headlineMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = VioletTint
                )
            ) {

                Column(
                    modifier = Modifier.padding(20.dp)
                ) {

                    Text(
                        text = user.displayName.ifBlank { "Citizen" },
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        text = user.email,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            if (editing) OutlinedTextField(name, { name = it }, label = { Text("Display name") }, enabled = !busy)
            Button(
                onClick = { if (editing) onSaveProfile(name) else editing = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (editing) "Save Profile" else "Edit Profile")
            }

            OutlinedButton(
                onClick = onSignOut,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign out")
            }
        }
    }
}
