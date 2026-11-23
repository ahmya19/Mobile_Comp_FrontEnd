package com.example.pinpoint.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pinpoint.data.PasswordPolicy
import com.example.pinpoint.ui.theme.*

fun shortIssueCode(id: String): String {
    val hex = id.replace("-", "").take(6).uppercase()
    return if (hex.isBlank()) "#PP-LOCAL" else "#PP-$hex"
}

fun categoryStyle(category: String): Triple<ImageVector, Color, Color> = when (category) {
    "Road Damage" -> Triple(Icons.Filled.Warning, Color(0xFFC88712), AmberTint)
    "Garbage" -> Triple(Icons.Filled.Delete, ResolvedGreen, GreenTint)
    "Streetlight" -> Triple(Icons.Filled.Lightbulb, Color(0xFFC98B14), AmberTint)
    "Flooding" -> Triple(Icons.Filled.Water, SkyText, SkyTint)
    "Water Interruption" -> Triple(Icons.Filled.WaterDrop, Color(0xFF287FA3), SkyTint)
    else -> Triple(Icons.Filled.MoreHoriz, DeepViolet, VioletTint)
}

fun categoryHeroColor(category: String): Color = when (category) {
    "Road Damage" -> HeroRoad
    "Garbage" -> HeroGarbage
    "Streetlight" -> HeroLight
    "Flooding" -> HeroFlood
    "Water Interruption" -> HeroWater
    else -> HeroOther
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
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.48f).dp))
    }
}

/** Editorial muted square thumbnail used in My Reports, Home recent rows, and Saved Reports (PDF 2.2, 6.1, 7.1). */
@Composable
fun CategoryThumbnailTile(category: String, modifier: Modifier = Modifier, size: Int = 54) {
    val (icon, _, _) = categoryStyle(category)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(categoryHeroColor(category)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = White.copy(alpha = 0.92f), modifier = Modifier.size((size * 0.44f).dp))
    }
}

/** Top header with soft #F5F5F8 square back button, centered title, and optional trailing action (PDF Boards 1, 3, 6, 7, 8, 9). */
@Composable
fun PinPointTopBar(
    title: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = backEnabled, onClick = onBackClick),
            shape = RoundedCornerShape(12.dp),
            color = PinPointSurface
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Black,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Black,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing
        )
    }
}

@Composable
fun StatusPill(status: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when {
        status in listOf("Pending", "Waiting to sync") -> AmberTint to AmberText
        status == "Under Review" -> SkyTint to SkyText
        status in listOf("In Progress", "Sending") -> VioletTint to DeepViolet
        status in listOf("Resolved", "Uploaded") -> GreenTint to ResolvedGreen
        status.contains("failed", ignoreCase = true) || status in listOf("Rejected", "Needs attention") -> RedTint to AlertRed
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
fun PriorityPill(
    priority: String,
    modifier: Modifier = Modifier,
    darkCard: Boolean = false,
    floatingOnHero: Boolean = false
) {
    val (bg, dot, fg) = when {
        darkCard && priority == "High" -> Triple(DarkHighBg, AlertRed, DarkHighFg)
        darkCard && priority == "Medium" -> Triple(DarkMediumBg, Amber, DarkMediumFg)
        darkCard -> Triple(DarkLowBg, SkyLow, DarkLowFg)
        floatingOnHero && priority == "High" -> Triple(White.copy(alpha = 0.94f), AlertRed, AlertRed)
        floatingOnHero && priority == "Medium" -> Triple(White.copy(alpha = 0.94f), Amber, AmberText)
        floatingOnHero -> Triple(White.copy(alpha = 0.94f), SkyLow, SkyText)
        priority == "High" -> Triple(RedTint, AlertRed, AlertRed)
        priority == "Medium" -> Triple(AmberTint, Amber, AmberText)
        else -> Triple(SkyTint, SkyLow, SkyText)
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
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Text(
                text = priority,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SolidFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    floatingWhite: Boolean = false
) {
    val bg = when {
        selected -> InkChip
        floatingWhite -> White.copy(alpha = 0.96f)
        else -> PinPointSurface
    }
    val fg = if (selected) White else Black
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = bg,
        shadowElevation = if (floatingWhite && !selected) 2.dp else 0.dp
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun pinPointFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = White,
    unfocusedContainerColor = PinPointSurface,
    disabledContainerColor = PinPointSurface,
    focusedBorderColor = Violet,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
    errorBorderColor = AlertRed,
    focusedTextColor = Black,
    unfocusedTextColor = Black
)

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
    val doneColor = Violet
    val inactiveTrack = if (darkCard) DarkTrack else SoftBorder
    val activeText = if (darkCard) White else Black
    val mutedText = if (darkCard) DarkBarText else MutedText

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (status == "Rejected") {
            StatusPill("Rejected")
            return
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.forEachIndexed { index, _ ->
                val done = index < activeIndex
                val current = index == activeIndex
                Box(
                    modifier = Modifier.size(if (current) 20.dp else 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (current) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Violet.copy(alpha = 0.32f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(if (current) 12.dp else 10.dp)
                            .clip(CircleShape)
                            .background(if (done || current) doneColor else inactiveTrack)
                    )
                }
                if (index < steps.lastIndex) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.5.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (index < activeIndex) doneColor else inactiveTrack)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("Pending", "Review", "In progress", "Resolved").forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index <= activeIndex) activeText else mutedText,
                    fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal,
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        3 -> TextAlign.End
                        else -> TextAlign.Center
                    }
                )
            }
        }
    }
}

/** Visual 6-box OTP digit display matching Board 1.6. */
@Composable
fun OtpDigitBoxes(code: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(6) { idx ->
            val ch = code.getOrNull(idx)?.toString().orEmpty()
            val isNext = idx == code.length.coerceAtMost(5)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isNext) White else PinPointSurface)
                    .border(
                        width = if (isNext) 1.5.dp else 1.dp,
                        color = if (isNext) Violet else SoftBorder,
                        shape = RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ch,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Black
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
                        .height(4.dp)
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
    containerColor: Color = PinPointSurface,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = border
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
