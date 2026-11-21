package com.example.pinpoint.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
fun NotificationsScreen(
    onBackClick: () -> Unit
) {

    val notifications = listOf(
        "Your pothole report is now under review.",
        "Your streetlight report has been resolved.",
        "A new high-priority issue was reported near your area."
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
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
            text = "Notifications",
            style = MaterialTheme.typography.headlineMedium
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            items(notifications) { notification ->

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = VioletTint
                    )
                ) {

                    Text(
                        text = notification,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        }
    }
}