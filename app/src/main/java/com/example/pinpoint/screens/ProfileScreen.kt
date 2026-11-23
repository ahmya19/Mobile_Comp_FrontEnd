package com.example.pinpoint.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.pinpoint.BuildConfig
import com.example.pinpoint.data.Citizen
import com.example.pinpoint.data.Report
import com.example.pinpoint.ui.theme.*

@Composable
fun ProfileScreen(
    onHomeClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMapClick: () -> Unit,
    user: Citizen,
    busy: Boolean,
    onSaveProfile: (String) -> Unit,
    onSignOut: () -> Unit,
    reports: List<Report> = emptyList(),
    pendingUploads: Int = 0,
    onReportCreateClick: (() -> Unit)? = null,
    onChangePassword: (String, String) -> Unit = { _, _ -> },
    onSmsGuideClick: () -> Unit = {},
    onManageSavedClick: () -> Unit = {}
) {
    var editing by remember { mutableStateOf(false) }
    var name by remember(user.displayName) { mutableStateOf(user.displayName) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    val resolvedCount = reports.count { it.status == "Resolved" }
    val upvotesTotal = reports.sumOf { it.voteCount }
    val initials = user.displayName.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("").ifBlank { "C" }

    // Board 9.9: Logout confirmation with unsynced reports warning
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out of PinPoint?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("You'll need to log in again to report or track issues. Reports saved offline stay on this device.")
                    if (pendingUploads > 0) {
                        Surface(shape = RoundedCornerShape(12.dp), color = AmberTint) {
                            Text(
                                "You have $pendingUploads saved report(s) not yet uploaded. They stay saved on this device until you sign back in.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Black,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showLogoutDialog = false; onSignOut() },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("Log out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Stay logged in") }
            }
        )
    }

    // Board 9.3: Change password dialog
    if (showChangePassword) {
        var currentPw by remember { mutableStateOf("") }
        var newPw by remember { mutableStateOf("") }
        var confirmPw by remember { mutableStateOf("") }
        val mismatch = confirmPw.isNotEmpty() && newPw != confirmPw
        AlertDialog(
            onDismissRequest = { showChangePassword = false },
            title = { Text("Change password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = currentPw,
                        onValueChange = { currentPw = it },
                        label = { Text("Current password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    OutlinedTextField(
                        value = newPw,
                        onValueChange = { newPw = it },
                        label = { Text("New password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    OutlinedTextField(
                        value = confirmPw,
                        onValueChange = { confirmPw = it },
                        label = { Text("Confirm new password") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    if (mismatch) Text("Passwords don't match yet", color = AlertRed, style = MaterialTheme.typography.bodySmall)
                    PasswordStrengthChecklist(newPw)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cur = currentPw; val next = newPw
                        currentPw = ""; newPw = ""; confirmPw = ""
                        showChangePassword = false
                        onChangePassword(cur, next)
                    },
                    enabled = !busy && currentPw.isNotBlank() && newPw.length >= 8 && newPw == confirmPw
                ) { Text("Update password") }
            },
            dismissButton = {
                TextButton(onClick = { showChangePassword = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = PinPointSurface,
        bottomBar = {
            PinPointBottomBar(
                selectedRoute = "profile",
                onHomeClick = onHomeClick,
                onReportsClick = onReportsClick,
                onMapClick = onMapClick,
                onProfileClick = {},
                onReportClick = onReportCreateClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Profile", style = MaterialTheme.typography.headlineMedium, color = Black)

            // User Identity & Counters Card (PDF 9.1)
            SectionSurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(VioletTint),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initials, style = MaterialTheme.typography.titleLarge, color = DeepViolet, fontWeight = FontWeight.Bold)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = user.displayName.ifBlank { "Citizen" }, style = MaterialTheme.typography.titleLarge, color = Black)
                        Text(text = user.email, style = MaterialTheme.typography.bodySmall, color = MutedText)
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = RoundedCornerShape(999.dp), color = if (user.official) GreenTint else VioletTint) {
                            Text(
                                text = if (user.official) "Official · ${user.barangay ?: "Alangilan"}" else "Citizen · Alangilan",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (user.official) ResolvedGreen else DeepViolet,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = SoftBorder)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ProfileStat("Reports", reports.size)
                    ProfileStat("Resolved", resolvedCount)
                    ProfileStat("Upvotes", upvotesTotal)
                }
            }

            if (editing) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name") },
                    enabled = !busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            Button(
                onClick = {
                    if (editing) {
                        onSaveProfile(name)
                        editing = false
                    } else editing = true
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(if (editing) "Save Profile" else "Edit Profile")
            }

            // Account & Utility Rows (PDF 9.1 / 9.4)
            SectionSurfaceCard {
                ProfileRow(Icons.Filled.Lock, "Change password", "Update your account password") { showChangePassword = true }
                HorizontalDivider(color = SoftBorder)
                if (pendingUploads > 0) {
                    ProfileRow(Icons.Filled.CloudOff, "Saved offline reports ($pendingUploads)", "Review, edit, retry or remove unsent reports", onManageSavedClick)
                    HorizontalDivider(color = SoftBorder)
                }
                ProfileRow(Icons.Filled.Sms, "SMS reporting guide", "Format a 160-character report when data is gone", onSmsGuideClick)
                HorizontalDivider(color = SoftBorder)
                ProfileRow(Icons.Filled.HelpOutline, "Help and support", "FAQ, evidence policy, and background sync tips") { showHelp = !showHelp }
                if (showHelp) {
                    Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FaqItem("Can I report without internet?", "Yes. Reports save safely in encrypted local storage and upload automatically once you reconnect. Full originals upload only on unmetered Wi-Fi.")
                        FaqItem("Why can't I delete a submitted report?", "Committed reports and photos are preserved as auditable community records. You can edit or delete unsent saved reports before they upload.")
                        FaqItem("Background sync on TECNO / battery-managed phones", "Set PinPoint to Unrestricted battery and No Restrictions in Battery Lab so offline reports sync automatically in the background.")
                    }
                }
                HorizontalDivider(color = SoftBorder)
                ProfileRow(Icons.Filled.Info, "About PinPoint", "Version ${BuildConfig.VERSION_NAME} · Alangilan, Batangas City") { showAbout = !showAbout }
                if (showAbout) {
                    Text(
                        "PinPoint connects residents with barangay authorities in Alangilan, Batangas City using offline-first syncing, Wi-Fi-only resumable originals, community urgency voting, and SMS fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                }
            }

            // Preserve exact "Sign out" label; guard unsynced local reports with Board 9.9 confirmation dialog.
            OutlinedButton(
                onClick = { if (pendingUploads > 0) showLogoutDialog = true else onSignOut() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.6f))
            ) {
                Text("Sign out", color = AlertRed)
            }
        }
    }
}

@Composable
private fun ProfileStat(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Black)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MutedText)
    }
}

@Composable
private fun ProfileRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(VioletTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = DeepViolet, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Black, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
    }
}

@Composable
private fun FaqItem(question: String, answer: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = PinPointSurface) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(question, style = MaterialTheme.typography.labelLarge, color = DeepViolet, fontWeight = FontWeight.SemiBold)
            Text(answer, style = MaterialTheme.typography.bodySmall, color = Black)
        }
    }
}
