package com.example.pinpoint.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.AlertRed
import com.example.pinpoint.ui.theme.ResolvedGreen
import com.example.pinpoint.ui.theme.Violet
import com.example.pinpoint.ui.theme.VioletTint

@Composable
fun HomeScreen(
    onQuickReportClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMapClick: () -> Unit,
    onProfileClick: () -> Unit,
    onNotificationsClick: () -> Unit
) {

    Scaffold(
        bottomBar = {
            PinPointBottomBar(
                selectedRoute = "home",
                onHomeClick = {},
                onReportsClick = onReportsClick,
                onMapClick = onMapClick,
                onProfileClick = onProfileClick
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {

            // Header
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth()
            ) {

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Good morning!",
                        style = MaterialTheme.typography.headlineMedium
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    Text(
                        text = "Welcome back to PinPoint.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                IconButton(
                    onClick = onNotificationsClick
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = "Notifications"
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(24.dp)
            )

            // Active Report
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
                        text = "Active Report",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    Text(
                        text = "Pothole near the main road",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    Text(
                        text = "Under Review",
                        style = MaterialTheme.typography.labelLarge,
                        color = Violet
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(28.dp)
            )

            Text(
                text = "Recent Community Reports",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                )
            ) {

                Column(
                    modifier = Modifier.padding(16.dp)
                ) {

                    Text(
                        text = "Streetlight not working",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    Text(
                        text = "Reported near Barangay Hall",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    Text(
                        text = "Resolved",
                        style = MaterialTheme.typography.labelLarge,
                        color = ResolvedGreen
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(28.dp)
            )

            Text(
                text = "High-Priority Issues",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                )
            ) {

                Column(
                    modifier = Modifier.padding(16.dp)
                ) {

                    Text(
                        text = "Flooded Road",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    Text(
                        text = "Heavy flooding reported in the area",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    Text(
                        text = "High Priority",
                        style = MaterialTheme.typography.labelLarge,
                        color = AlertRed
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(28.dp)
            )

            Button(
                onClick = onQuickReportClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Quick Report")
            }

            Spacer(
                modifier = Modifier.height(16.dp)
            )
        }
    }
}