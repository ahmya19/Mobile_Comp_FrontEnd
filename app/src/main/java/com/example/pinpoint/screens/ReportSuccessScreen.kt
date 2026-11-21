package com.example.pinpoint.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ReportSuccessScreen(
    onViewReportsClick: () -> Unit,
    onHomeClick: () -> Unit,
    pending: Boolean = false
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Report submitted",
            modifier = Modifier.padding(bottom = 20.dp)
        )

        Text(
            text = if(pending) "Report Saved on Device" else "Report Submitted!",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = if(pending) "Your report is saved safely on this device and will be sent when your connection is available. Check My Reports for its progress." else "Your report has been submitted successfully.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(
                top = 12.dp,
                bottom = 28.dp
            )
        )

        Button(
            onClick = onViewReportsClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("View My Reports")
        }

        Button(
            onClick = onHomeClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Back to Home")
        }
    }
}
