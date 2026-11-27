package com.example.pinpoint.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pinpoint.BuildConfig
import com.example.pinpoint.data.Citizen
import com.example.pinpoint.data.ProfileInput
import com.example.pinpoint.data.Report
import com.example.pinpoint.ui.theme.*

/** Profile & Settings styled to PDF Board 9 (centered avatar + 3 stat boxes, #F5F5F8 chevron rows, soft pink logout, Board 9.9 modal). */
@Composable
fun ProfileScreen(
    onHomeClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMapClick: () -> Unit,
    user: Citizen,
    busy: Boolean,
    onSaveProfile: (String, String, String) -> Unit,
    onSignOut: () -> Unit,
    reports: List<Report> = emptyList(),
    pendingUploads: Int = 0,
    onReportCreateClick: (() -> Unit)? = null,
    onChangePassword: (String, String) -> Unit = { _, _ -> },
    onSmsGuideClick: () -> Unit = {},
    onManageSavedClick: () -> Unit = {},
    onAppTourClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {}
) {
    var editing by remember { mutableStateOf(false) }
    var name by remember(user.displayName) { mutableStateOf(user.displayName) }
    var contact by remember(user.contactNumber) { mutableStateOf(user.contactNumber.orEmpty()) }
    var homeLocation by remember(user.homeLocation) { mutableStateOf(user.homeLocation.orEmpty()) }
    var profileError by remember { mutableStateOf<String?>(null) }
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
            containerColor = White,
            shape = RoundedCornerShape(24.dp),
            icon = {
                Box(
                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(RedTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = AlertRed, modifier = Modifier.size(24.dp))
                }
            },
            title = { Text("Log out of PinPoint?", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "You'll need to log in again to report or track issues. Reports saved offline stay on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                        textAlign = TextAlign.Center
                    )
                    if (pendingUploads > 0) {
                        Surface(shape = RoundedCornerShape(14.dp), color = AmberTint) {
                            Text(
                                "You have $pendingUploads saved report(s) not yet uploaded. They stay saved on this device until you sign back in.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AmberText,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showLogoutDialog = false; onSignOut() },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed, contentColor = White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Log out", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Stay logged in", color = Black, fontWeight = FontWeight.SemiBold) }
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
            containerColor = White,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Change password", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = currentPw,
                        onValueChange = { currentPw = it },
                        label = { Text("Current password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(14.dp),
                        colors = pinPointFieldColors()
                    )
                    OutlinedTextField(
                        value = newPw,
                        onValueChange = { newPw = it },
                        label = { Text("New password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(14.dp),
                        colors = pinPointFieldColors()
                    )
                    OutlinedTextField(
                        value = confirmPw,
                        onValueChange = { confirmPw = it },
                        label = { Text("Confirm new password") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(14.dp),
                        colors = pinPointFieldColors()
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
                    enabled = !busy && currentPw.isNotBlank() && newPw.length >= 8 && newPw == confirmPw,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Update password") }
            },
            dismissButton = {
                TextButton(onClick = { showChangePassword = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = White,
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
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Profile", style = MaterialTheme.typography.headlineMedium, color = Black)

            // Centered Identity Hero (PDF 9.1)
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier.size(76.dp).clip(CircleShape).background(VioletTint),
                    contentAlignment = Alignment.Center
                ) {
                    Text(initials, fontSize = 26.sp, color = DeepViolet, fontWeight = FontWeight.ExtraBold)
                }
                Text(text = user.displayName.ifBlank { "Citizen" }, style = MaterialTheme.typography.titleLarge, color = Black)
                Text(
                    text = "${user.email} · ${user.barangay ?: "Alangilan"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                    textAlign = TextAlign.Center
                )
                Surface(shape = RoundedCornerShape(999.dp), color = if (user.official) GreenTint else VioletTint) {
                    Text(
                        text = if (user.official) "Official · ${user.barangay ?: "Alangilan"}" else "Citizen · Alangilan",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (user.official) ResolvedGreen else DeepViolet,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            // 3 separate equal-width #F5F5F8 stat tiles (PDF 9.1)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileStatBox("Reports", reports.size, Modifier.weight(1f))
                ProfileStatBox("Resolved", resolvedCount, Modifier.weight(1f))
                ProfileStatBox("Upvotes", upvotesTotal, Modifier.weight(1f))
            }

            AnimatedVisibility(visible = editing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SectionSurfaceCard {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Display name") },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = MutedText, modifier = Modifier.size(18.dp)) },
                        enabled = !busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = White,
                            unfocusedContainerColor = White,
                            focusedBorderColor = Violet,
                            unfocusedBorderColor = SoftBorder
                        )
                    )
                    OutlinedTextField(
                        value = contact,
                        onValueChange = { contact = it; profileError = null },
                        label = { Text("Contact number (optional)") },
                        leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null, tint = MutedText, modifier = Modifier.size(18.dp)) },
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = White,
                            unfocusedContainerColor = White,
                            focusedBorderColor = Violet,
                            unfocusedBorderColor = SoftBorder
                        )
                    )
                    OutlinedTextField(
                        value = homeLocation,
                        onValueChange = { homeLocation = it; profileError = null },
                        label = { Text("Location (optional)") },
                        supportingText = { Text("Your purok, street or area in Alangilan.") },
                        leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MutedText, modifier = Modifier.size(18.dp)) },
                        enabled = !busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = White,
                            unfocusedContainerColor = White,
                            focusedBorderColor = Violet,
                            unfocusedBorderColor = SoftBorder
                        )
                    )
                    profileError?.let { Text(it, color = AlertRed, style = MaterialTheme.typography.bodySmall) }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                try {
                                    ProfileInput.contact(contact); ProfileInput.location(homeLocation)
                                    onSaveProfile(name, contact, homeLocation); editing = false; profileError = null
                                } catch (failure: IllegalArgumentException) { profileError = failure.message }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Save Profile", fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = { name = user.displayName; contact = user.contactNumber.orEmpty(); homeLocation = user.homeLocation.orEmpty(); profileError = null; editing = false },
                            enabled = !busy,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Cancel") }
                    }
                }
            }

            // Individual #F5F5F8 chevron rows matching PDF 9.1 / 9.4
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileMenuRow(Icons.Filled.Person, "Edit Profile", "Name, contact number, location") { editing = !editing }
                ProfileMenuRow(Icons.Filled.Lock, "Change password", "Update your account password") { showChangePassword = true }
                if (pendingUploads > 0) {
                    ProfileMenuRow(Icons.Filled.CloudOff, "Saved offline reports ($pendingUploads)", "Review, edit, retry or remove unsent reports", onClick = onManageSavedClick)
                }
                ProfileMenuRow(Icons.Filled.Sms, "SMS reporting guide", "Format a 160-character report when data is gone", onClick = onSmsGuideClick)
                ProfileMenuRow(Icons.Filled.Explore, "App tour & onboarding", "Preview the 3-step citizen walkthrough anytime", onClick = onAppTourClick)
                ProfileMenuRow(Icons.Filled.Security, "App permissions", "Location, notification, and camera access", onClick = onPermissionsClick)
                ProfileMenuRow(
                    icon = Icons.Filled.HelpOutline,
                    title = "Help and support",
                    subtitle = "FAQ, evidence policy, and background sync tips",
                    expanded = showHelp
                ) { showHelp = !showHelp }
                AnimatedVisibility(visible = showHelp, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FaqAccordionItem("Can I report without internet?", "Yes. Reports save safely in encrypted local storage and upload automatically once you reconnect. Full originals upload only on unmetered Wi-Fi.")
                        FaqAccordionItem("Why can't I delete a submitted report?", "Committed reports and photos are preserved as auditable community records. You can edit or delete unsent saved reports before they upload.")
                        FaqAccordionItem("Background sync on TECNO / battery-managed phones", "Set PinPoint to Unrestricted battery and No Restrictions in Battery Lab so offline reports sync automatically in the background.")
                    }
                }
                ProfileMenuRow(
                    icon = Icons.Filled.Info,
                    title = "About PinPoint",
                    subtitle = "Version ${BuildConfig.VERSION_NAME} · Alangilan, Batangas City",
                    expanded = showAbout
                ) { showAbout = !showAbout }
                AnimatedVisibility(visible = showAbout, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    SectionSurfaceCard {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Violet),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = White, modifier = Modifier.size(28.dp))
                            }
                            Text("PinPoint", style = MaterialTheme.typography.titleLarge, color = Black)
                            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MutedText)
                            Text(
                                "PinPoint connects residents with barangay authorities in Alangilan, Batangas City using offline-first syncing, Wi-Fi-only resumable originals, community urgency voting, and SMS fallback.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedText,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Soft pink-red Sign out row matching PDF 9.1 (preserves exact "Sign out" label)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = !busy) { showLogoutDialog = true },
                shape = RoundedCornerShape(16.dp),
                color = RedTint
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = AlertRed, modifier = Modifier.size(20.dp))
                    Text("Sign out", style = MaterialTheme.typography.titleSmall, color = AlertRed, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ProfileStatBox(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = PinPointSurface) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(count.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Black)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MutedText)
        }
    }
}

@Composable
private fun ProfileMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    expanded: Boolean? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = PinPointSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(White),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = DeepViolet, modifier = Modifier.size(19.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Black, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedText)
            }
            Icon(
                imageVector = when (expanded) {
                    true -> Icons.Filled.ExpandLess
                    false -> Icons.Filled.ExpandMore
                    null -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                tint = MutedText,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun FaqAccordionItem(question: String, answer: String) {
    var open by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { open = !open },
        shape = RoundedCornerShape(14.dp),
        color = PinPointSurface
    ) {
        Column(modifier = Modifier.fillMaxWidth().animateContentSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(question, style = MaterialTheme.typography.labelLarge, color = Black, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = DeepViolet, modifier = Modifier.size(18.dp))
            }
            AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(answer, style = MaterialTheme.typography.bodySmall, color = MutedText)
            }
        }
    }
}
