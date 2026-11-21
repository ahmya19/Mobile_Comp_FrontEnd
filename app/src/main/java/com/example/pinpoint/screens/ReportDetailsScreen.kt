package com.example.pinpoint.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.VioletTint

@Composable
fun ReportDetailsScreen(
    onBackClick: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        IconButton(
            onClick = onBackClick
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = "Back"
            )
        }

        Text(
            text = "Report Details",
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
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                Text(
                    text = "Pothole near the main road",
                    style = MaterialTheme.typography.titleLarge
                )

                Text(
                    text = "Category: Road Damage"
                )

                Text(
                    text = "Location: Main Road"
                )

                Text(
                    text = "Status: Under Review"
                )

                Text(
                    text = "Submitted: November 21, 2026"
                )

                Text(
                    text = "Description: A large pothole is affecting vehicles and pedestrians using the road."
                )
            }
        }

        Button(
            onClick = onBackClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to My Reports")
        }
    }
}