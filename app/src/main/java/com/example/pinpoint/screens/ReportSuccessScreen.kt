package com.example.pinpoint.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.*

/** Submission confirmation styled to PDF Board 3.8 (concentric violet halo, charcoal ticket card, 3-step expectation list). */
@Composable
fun ReportSuccessScreen(
    onViewReportsClick: () -> Unit,
    onHomeClick: () -> Unit,
    pending: Boolean = false,
    reportId: String? = null
) {
    val haloScale = remember { Animatable(0.72f) }
    LaunchedEffect(Unit) {
        haloScale.animateTo(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Concentric soft violet halo with solid violet centre checkmark and spring entrance (PDF 3.8)
        Box(
            modifier = Modifier.size(116.dp).scale(haloScale.value).clip(CircleShape).background(VioletTint.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(86.dp).clip(CircleShape).background(VioletTint),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Violet),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Report submitted",
                        tint = White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = if (pending) "Report Saved on Device" else "Report submitted!",
            style = MaterialTheme.typography.headlineMedium,
            color = Black,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = if (pending)
                "Your report is saved safely on this device and will upload automatically once your connection returns."
            else "Thank you for helping your community. Your barangay will review it shortly.",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedText,
            textAlign = TextAlign.Center
        )

        if (!reportId.isNullOrBlank()) {
            Spacer(Modifier.height(22.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = InkCard
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Report number", style = MaterialTheme.typography.labelSmall, color = DarkBarText)
                        StatusPill(if (pending) "Waiting to sync" else "Pending")
                    }
                    Text(
                        shortIssueCode(reportId),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Amber,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // Vertical 3-step next-steps timeline (PDF 3.8)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConfirmationStep(
                label = if (pending) "Saved offline on this device" else "Report received",
                done = true,
                active = false
            )
            ConfirmationStep(
                label = if (pending) "Uploads automatically when back online" else "Acknowledged by barangay officials",
                done = false,
                active = true
            )
            ConfirmationStep(
                label = "You'll be notified at every status update",
                done = false,
                active = false
            )
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = onViewReportsClick,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = White)
        ) {
            Text("View My Reports", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)),
            shape = RoundedCornerShape(14.dp),
            color = PinPointSurface,
            onClick = onHomeClick
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("Back to Home", style = MaterialTheme.typography.titleSmall, color = Black, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ConfirmationStep(label: String, done: Boolean, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (done) Violet else if (active) White else SoftBorder)
                .then(if (active) Modifier.border(2.dp, Violet, CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = White, modifier = Modifier.size(14.dp))
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done || active) Black else MutedText,
            fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
