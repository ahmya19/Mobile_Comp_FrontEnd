package com.example.pinpoint.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.VioletTint

@Composable
fun MapScreen(
    onHomeClick: () -> Unit,
    onReportsClick: () -> Unit,
    onProfileClick: () -> Unit
) {

    Scaffold(
        bottomBar = {
            PinPointBottomBar(
                selectedRoute = "map",
                onHomeClick = onHomeClick,
                onReportsClick = onReportsClick,
                onMapClick = {},
                onProfileClick = onProfileClick
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
        ) {

            Text(
                text = "Explore Map",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = "View reported issues in your community.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .height(450.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = VioletTint
                )
            ) {

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {

                        Text(
                            text = "Community Map",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Text(
                            text = "Reported issues will appear here.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}