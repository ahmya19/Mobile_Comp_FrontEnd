package com.example.pinpoint.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.PasswordPolicy
import com.example.pinpoint.ui.theme.*

fun shortIssueCode(id: String): String {
    val hex = id.replace("-", "").take(6).uppercase()
    return if (hex.isBlank()) "#PP-LOCAL" else "#PP-$hex"
}

fun categoryStyle(category: String): Triple<ImageVector, Color, Color> = when (category) {
    "Road Damage" -> Triple(Icons.Filled.Warning, Amber, AmberTint)
    "Garbage" -> Triple(Icons.Filled.Delete, ResolvedGreen, GreenTint)
    "Streetlight" -> Triple(Icons.Filled.Lightbulb, Color(0xFFD99A1E), AmberTint)
    "Flooding" -> Triple(Icons.Filled.Water, SkyLow, SkyTint)
    "Water Interruption" -> Triple(Icons.Filled.WaterDrop, Color(0xFF398AB9), SkyTint)
    else -> Triple(Icons.Filled.MoreHoriz, Violet, VioletTint)
}

@Composable
fun CategoryIconBadge(category: String, modifier: Modifier = Modifier, size: Int = 44) {
    val (icon, tint, bg) = categoryStyle(category)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size / 2).dp))
    }
}

@Composable
fun StatusPill(status: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        "Pending" -> AmberTint to Color(0xFF9A6500)
        "Under Review" -> SkyTint to Color(0xFF1D6B87)
        "In Progress" -> VioletTint to DeepViolet
        "Resolved" -> GreenTint to ResolvedGreen
        "Rejected", "Needs attention" -> RedTint to AlertRed
        else -> VioletTint to DeepViolet
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
            Text(status, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun PriorityPill(priority: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when (priority) {
        "High" -> RedTint to AlertRed
        "Medium" -> AmberTint to Color(0xFF9A6500)
        else -> SkyTint to Color(0xFF1D6B87)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = bg
    ) {
        Text(
            text = priority,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun ReportProgressTimeline(status: String, modifier: Modifier = Modifier, darkCard: Boolean = false) {
    val steps = listOf("Pending", "Under Review", "In Progress", "Resolved")
    val activeIndex = when (status) {
        "Pending" -> 0
        "Under Review" -> 1
        "In Progress" -> 2
        "Resolved" -> 3
        "Rejected" -> -1
        else -> 0
    }
    val activeColor = if (darkCard) Violet else Violet
    val doneColor = if (darkCard) Amber else ResolvedGreen
    val inactiveTrack = if (darkCard) Color(0xFF393944) else SoftBorder
    val activeText = if (darkCard) White else Black
    val mutedText = if (darkCard) DarkBarText else MutedText

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status == "Rejected") {
            StatusPill("Rejected")
            return
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.forEachIndexed { index, _ ->
                val done = index < activeIndex
                val current = index == activeIndex
                val dotColor = when {
                    done -> doneColor
                    current -> activeColor
                    else -> inactiveTrack
                }
                Box(
                    modifier = Modifier
                        .size(if (current) 14.dp else 10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                if (index < steps.lastIndex) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .background(if (index < activeIndex) doneColor else inactiveTrack)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("Pending", "Review", "Progress", "Resolved").forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index <= activeIndex) activeText else mutedText,
                    fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun PasswordStrengthChecklist(password: String, modifier: Modifier = Modifier) {
    val s = PasswordPolicy.evaluate(password)
    val barColor = when (s.score) {
        0, 1 -> AlertRed
        2 -> Amber
        3 -> SkyLow
        else -> ResolvedGreen
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { idx ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (password.isNotEmpty() && idx < s.score) barColor else SoftBorder)
                )
            }
        }
        if (password.isNotEmpty()) {
            Text(s.label, style = MaterialTheme.typography.labelMedium, color = barColor, fontWeight = FontWeight.SemiBold)
        }
        // Only the length is enforced by the app; the server applies its own minimum. The other rows are advice, and the
        // screen says so, so it never claims a rule that is not actually checked.
        ChecklistRow("Required: at least 8 characters", s.minLength)
        Text("Recommended, not required:", style = MaterialTheme.typography.labelSmall, color = MutedText)
        ChecklistRow("Upper and lowercase letters", s.hasUpperAndLower)
        ChecklistRow("At least one number", s.hasDigit)
        ChecklistRow("One symbol, like ! or #", s.hasSymbol)
    }
}

@Composable
private fun ChecklistRow(label: String, met: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = if (met) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (met) ResolvedGreen else MutedText,
            modifier = Modifier.size(16.dp)
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (met) Black else MutedText)
    }
}

@Composable
fun SectionSurfaceCard(
    modifier: Modifier = Modifier,
    containerColor: Color = White,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, SoftBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
