package com.example.pinpoint.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.*

/** Forgot password (PDF 1.5) and Set a new password (PDF 1.6) with 6-box OTP preview. */
@Composable
fun PasswordRecoveryScreen(
    initialEmail: String,
    busy: Boolean,
    onSendCode: (String) -> Unit,
    onResetPassword: (String, String, String) -> Unit,
    onBack: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var enterCodeStep by rememberSaveable { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    BackHandler { if (!busy) onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PinPointTopBar(title = "", onBackClick = onBack, backEnabled = !busy)

        if (!enterCodeStep) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(68.dp).clip(RoundedCornerShape(20.dp)).background(VioletTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.LockReset, contentDescription = null, tint = Violet, modifier = Modifier.size(32.dp))
                }
                Text("Forgot password?", style = MaterialTheme.typography.headlineMedium, color = Black, textAlign = TextAlign.Center)
                Text(
                    "No problem. Enter the email address on your account and we'll send you a 6-digit code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedText,
                    textAlign = TextAlign.Center
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Recovery email") },
                leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp)) },
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = pinPointFieldColors()
            )

            Button(
                onClick = { onSendCode(email); enterCodeStep = true },
                enabled = !busy && email.contains('@'),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Send reset code", fontWeight = FontWeight.Bold)
            }

            Surface(shape = RoundedCornerShape(14.dp), color = AmberTint) {
                Text(
                    "The code expires in 10 minutes. Never share it with anyone, including barangay staff.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmberText,
                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                )
            }

            OutlinedButton(
                onClick = { enterCodeStep = true },
                enabled = !busy && email.contains('@'),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("I already have a code")
            }
        } else {
            val mismatch = confirmPassword.isNotEmpty() && newPassword != confirmPassword
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Set a new password", style = MaterialTheme.typography.headlineMedium, color = Black)
                Text("Enter the code sent to $email, then choose a new password.", style = MaterialTheme.typography.bodyMedium, color = MutedText)
            }

            OtpDigitBoxes(code)

            OutlinedTextField(
                value = code,
                onValueChange = { value -> code = value.filter { it in '0'..'9' }.take(6) },
                label = { Text("Verification code") },
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = pinPointFieldColors()
            )
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("New password") },
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp)) },
                enabled = !busy,
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Toggle password visibility", tint = MutedText)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = pinPointFieldColors()
            )
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Confirm new password") },
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp)) },
                enabled = !busy,
                singleLine = true,
                isError = mismatch,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = pinPointFieldColors()
            )
            if (mismatch) {
                Text("Passwords don't match yet", color = AlertRed, style = MaterialTheme.typography.bodySmall)
            }
            PasswordStrengthChecklist(newPassword)
            Button(
                onClick = {
                    val submittedCode = code
                    val submittedPw = newPassword
                    code = ""; newPassword = ""; confirmPassword = ""
                    onResetPassword(email, submittedCode, submittedPw)
                },
                enabled = !busy && code.length == 6 && newPassword.length >= 8 && newPassword == confirmPassword,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Reset password", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { enterCodeStep = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Back to request code", color = MutedText)
            }
        }
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Back to log in", color = DeepViolet, fontWeight = FontWeight.SemiBold)
        }
    }
}
