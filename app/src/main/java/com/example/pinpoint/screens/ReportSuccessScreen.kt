package com.example.pinpoint.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.*

@Composable
fun ReportSuccessScreen(
    onViewReportsClick: () -> Unit,
    onHomeClick: () -> Unit,
    pending: Boolean = false,
    reportId: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PinPointSurface)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(VioletTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Report submitted",
                tint = Violet,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = if (pending) "Report Saved on Device" else "Report Submitted!",
            style = MaterialTheme.typography.headlineMedium,
            color = Black,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = if (pending)
                "Your report is saved safely on this device and will be sent when your connection is available. Check My Reports for its progress."
            else "Your report has been submitted successfully.",
            style = MaterialTheme.typography.bodyLarge,
            color = MutedText,
            textAlign = TextAlign.Center
        )

        if (!reportId.isNullOrBlank()) {
            Spacer(Modifier.height(20.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = InkCard
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Report number", style = MaterialTheme.typography.labelSmall, color = DarkBarText)
                        Text(shortIssueCode(reportId), style = MaterialTheme.typography.titleLarge, color = Amber, fontWeight = FontWeight.Bold)
                    }
                    StatusPill(if (pending) "Waiting to sync" else "Pending")
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = onViewReportsClick,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("View My Reports", fontWeight = FontWeight.SemiBold)
        }

        OutlinedButton(
            onClick = onHomeClick,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Back to Home")
        }
    }
}
