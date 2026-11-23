package com.example.pinpoint.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.SmsFormatHelper
import com.example.pinpoint.ui.theme.*

/** Offline SMS Reporting Guide & Composer (PDF Board 8.5–8.6).
 *  Uses the exact deployed parser contract (PIN <CATEGORY>;<BARANGAY>;<DESCRIPTION>) and requires explicit
 *  user consent in the system Messages app (never sends silently or to an unknown number). */
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
            .background(PinPointSurface)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = InkCard) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No internet? Report by text.", style = MaterialTheme.typography.headlineSmall, color = White, fontWeight = FontWeight.Bold)
                Text(
                    "Send a single 160-character GSM-7 text message. Photos cannot be sent by SMS, and SMS reports are pinned to Alangilan's approximate barangay centroid.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DarkBarText
                )
            }
        }

        Surface(shape = RoundedCornerShape(14.dp), color = AmberTint, border = BorderStroke(1.dp, Amber.copy(alpha = 0.5f))) {
            Text(
                "Gateway status: Fail-closed until the team's spare Android SMS gateway phone and SIM are active. Only Alangilan is supported.",
                style = MaterialTheme.typography.bodySmall,
                color = Black,
                modifier = Modifier.padding(14.dp)
            )
        }

        SectionSurfaceCard {
            Text("Deployed message format", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Black)
            Surface(shape = RoundedCornerShape(12.dp), color = InkCard) {
                Text(
                    text = "PIN <CATEGORY>;<BARANGAY>;<DESCRIPTION>",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Amber,
                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                )
            }
            Text("Category codes", style = MaterialTheme.typography.labelLarge, color = MutedText)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SmsFormatHelper.categoryCodes.values.toList()) { code ->
                    FilterChip(
                        selected = selectedCode == code,
                        onClick = { selectedCode = code },
                        label = { Text(code) }
                    )
                }
            }
            OutlinedTextField(
                value = details,
                onValueChange = { details = it },
                label = { Text("Short issue details (min 10 chars)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                shape = RoundedCornerShape(14.dp)
            )
            Surface(shape = RoundedCornerShape(12.dp), color = VioletTint) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Preview", style = MaterialTheme.typography.labelSmall, color = DeepViolet)
                    Text(formatted, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = Black)
                    Text(
                        text = when {
                            septets < 0 -> "Contains characters outside GSM-7 (avoid emojis or smart quotes)."
                            septets > 160 -> "$septets / 160 GSM-7 septets (too long for 1 SMS)."
                            else -> "$septets / 160 GSM-7 septets"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (validSms) ResolvedGreen else AlertRed
                    )
                }
            }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DeepViolet) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("PinPoint SMS format", formatted))
                        feedback = "SMS format copied to clipboard."
                    },
                    enabled = validSms,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy format")
                }
                Button(
                    onClick = {
                        // Opens the user's Messages app with prefilled body and blank recipient so the user
                        // explicitly chooses the verified gateway number and confirms sending.
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
                            putExtra("sms_body", formatted)
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure { feedback = "No SMS app is available on this device. Copy the format instead." }
                    },
                    enabled = validSms,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Sms, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open Messages")
                }
            }
        }
    }
}
