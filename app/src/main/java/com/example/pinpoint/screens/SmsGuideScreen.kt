package com.example.pinpoint.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.SmsFormatHelper
import com.example.pinpoint.ui.theme.*

/** Offline SMS Reporting Guide & Composer styled to PDF Boards 8.5 & 8.6.
 *  Uses the exact deployed parser contract (PIN <CATEGORY>;<BARANGAY>;<DESCRIPTION>) and explicit user consent in Messages. */
@Composable
fun SmsGuideScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    var selectedCode by rememberSaveable { mutableStateOf("ROAD") }
    var details by rememberSaveable { mutableStateOf("Large pothole near Alangilan barangay hall") }
    var feedback by remember { mutableStateOf<String?>(null) }

    val formatted = SmsFormatHelper.buildMessage(selectedCode, "Alangilan", details)
    val septets = SmsFormatHelper.gsm7Septets(formatted)
    val validSms = septets in 1..160 && details.trim().length >= 10

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PinPointTopBar(title = "SMS reporting", onBackClick = onBackClick)

        // Charcoal Hero Card with violet chat icon badge (PDF 8.5)
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = InkCard) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Violet.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Sms, contentDescription = null, tint = VioletTint, modifier = Modifier.size(22.dp))
                }
                Text("No internet? Report by text.", style = MaterialTheme.typography.headlineSmall, color = White, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Send a basic report from any phone. No app or mobile data needed. SMS reports use Alangilan's barangay center point.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DarkBarText
                )
            }
        }

        // 3 numbered step cards (PDF 8.5)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmsStepRow("1", "Write your message", "Use category, barangay (Alangilan), and a short description.")
            SmsStepRow("2", "Send to the gateway number", "One 160-character text equals one report.")
            SmsStepRow("3", "Track in PinPoint", "Once received, your report appears under Alangilan.")
        }

        // Honest gateway status notice
        Surface(shape = RoundedCornerShape(14.dp), color = AmberTint) {
            Text(
                "Gateway status: Fail-closed until the team's spare Android SMS gateway phone and SIM are active. Only Alangilan is supported.",
                style = MaterialTheme.typography.bodySmall,
                color = AmberText,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        // Dark code block for deployed format (PDF 8.6)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MESSAGE FORMAT", style = MaterialTheme.typography.labelSmall, color = MutedText, fontWeight = FontWeight.Bold)
            Surface(shape = RoundedCornerShape(16.dp), color = InkCard) {
                Text(
                    text = "PIN <CATEGORY>;<BARANGAY>;<DESCRIPTION>",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Amber,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }

            Text("CATEGORY CODES", style = MaterialTheme.typography.labelSmall, color = MutedText, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SmsFormatHelper.categoryCodes.values.toList()) { code ->
                    SolidFilterChip(
                        label = code,
                        selected = selectedCode == code,
                        onClick = { selectedCode = code }
                    )
                }
            }

            OutlinedTextField(
                value = details,
                onValueChange = { details = it },
                label = { Text("Short issue details (min 10 chars)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                shape = RoundedCornerShape(14.dp),
                colors = pinPointFieldColors()
            )

            // Realistic Messages bubble preview (PDF 8.6)
            SectionSurfaceCard {
                Text("Messages preview", style = MaterialTheme.typography.labelSmall, color = MutedText, modifier = Modifier.align(Alignment.CenterHorizontally))
                Surface(
                    modifier = Modifier.fillMaxWidth(0.92f).align(Alignment.End),
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    color = Violet
                ) {
                    Text(
                        text = formatted,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = White,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Text(
                    text = when {
                        septets < 0 -> "Contains characters outside GSM-7 (avoid emojis or smart quotes)."
                        septets > 160 -> "$septets / 160 GSM-7 septets (too long for 1 SMS)."
                        else -> "$septets / 160 GSM-7 septets"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (validSms) ResolvedGreen else AlertRed,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.End)
                )
            }

            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DeepViolet, fontWeight = FontWeight.SemiBold) }

            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
                        putExtra("sms_body", formatted)
                    }
                    runCatching { context.startActivity(intent) }
                        .onFailure { feedback = "No SMS app is available on this device. Copy the format instead." }
                },
                enabled = validSms,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open Messages", fontWeight = FontWeight.Bold)
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = validSms) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("PinPoint SMS format", formatted))
                        feedback = "SMS format copied to clipboard."
                    },
                shape = RoundedCornerShape(14.dp),
                color = PinPointSurface
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = Black, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy format", style = MaterialTheme.typography.labelLarge, color = Black)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SmsStepRow(number: String, title: String, subtitle: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = PinPointSurface) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(26.dp).clip(CircleShape).background(Violet),
                contentAlignment = Alignment.Center
            ) {
                Text(number, style = MaterialTheme.typography.labelSmall, color = White, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Black, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedText)
            }
        }
    }
}
