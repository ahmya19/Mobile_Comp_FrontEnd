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
import com.example.pinpoint.data.Report

@Composable
fun MyReportsScreen(
    reports: List<Report>,
    loading: Boolean,
    onReportClick: (String) -> Unit,
    onHomeClick: () -> Unit,
    onMapClick: () -> Unit,
    onProfileClick: () -> Unit,
    communityFeed: Boolean = false
) {

    Scaffold(
        bottomBar = {
            PinPointBottomBar(
                selectedRoute = if (communityFeed) "home" else "reports",
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
                text = if (communityFeed) "Community Reports" else "My Reports",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = if (communityFeed) "Recent issues reported by your community." else "Track the reports you have submitted.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                if (loading) item { Text("Loading reports...") }
                else if (reports.isEmpty()) item { Text(if (communityFeed) "No community reports yet." else "You have not submitted any reports yet.") }
                items(reports, key = { it.id }) { report ->

                    Card(
                        onClick = { onReportClick(report.id) },
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
