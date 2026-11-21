package com.example.pinpoint.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.VioletTint

data class Report(
    val title: String,
    val location: String,
    val status: String
)

@Composable
fun MyReportsScreen(
    onReportClick: () -> Unit,
    onHomeClick: () -> Unit,
    onMapClick: () -> Unit,
    onProfileClick: () -> Unit
) {

    val reports = listOf(
        Report(
            "Pothole near the main road",
            "Main Road",
            "Under Review"
        ),
        Report(
            "Broken streetlight",
            "Barangay Hall",
            "Resolved"
        ),
        Report(
            "Flooded pathway",
            "Zone 2",
            "Pending"
        )
    )

    Scaffold(
        bottomBar = {
            PinPointBottomBar(
                selectedRoute = "reports",
                onHomeClick = onHomeClick,
                onReportsClick = {},
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
        ) {

            Text(
                text = "My Reports",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = "Track the reports you have submitted.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                items(reports) { report ->

                    Card(
                        onClick = onReportClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = VioletTint
                        )
                    ) {

                        Column(
                            modifier = Modifier.padding(18.dp)
                        ) {

                            Text(
                                text = report.title,
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text(
                                text = report.location,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 6.dp)
                            )

                            Text(
                                text = report.status,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}