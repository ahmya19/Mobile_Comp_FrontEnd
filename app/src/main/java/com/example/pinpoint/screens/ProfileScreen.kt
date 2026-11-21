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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.VioletTint

@Composable
fun ProfileScreen(
    onHomeClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMapClick: () -> Unit
) {

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
                        text = "Citizen User",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        text = "citizen@example.com",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Edit Profile")
            }

            OutlinedButton(
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Settings")
            }
        }
    }
}