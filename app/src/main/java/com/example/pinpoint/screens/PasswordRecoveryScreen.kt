package com.example.pinpoint.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.*

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
            .background(PinPointSurface)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconButton(onClick = onBack, enabled = !busy) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        if (!enterCodeStep) {
            Text("Forgot password?", style = MaterialTheme.typography.headlineMedium, color = Black)
            SectionSurfaceCard {
                Text(
                    "Enter the email address on your PinPoint account to request a 6-digit recovery code. Delivery depends on the team SMTP sender configuration.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedText
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Recovery email") },
                    enabled = !busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = { onSendCode(email); enterCodeStep = true },
                    enabled = !busy && email.contains('@'),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Send reset code")
                }
                OutlinedButton(
                    onClick = { enterCodeStep = true },
                    enabled = !busy && email.contains('@'),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("I already have a code")
                }
                Surface(shape = RoundedCornerShape(12.dp), color = AmberTint) {
                    Text(
                        "Never share your verification code with anyone, including barangay staff.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Black,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        } else {
            val mismatch = confirmPassword.isNotEmpty() && newPassword != confirmPassword
            Text("Set a new password", style = MaterialTheme.typography.headlineMedium, color = Black)
            SectionSurfaceCard {
                Text("Enter the 6-digit code sent to $email and choose a new password.", style = MaterialTheme.typography.bodyMedium, color = MutedText)
                OutlinedTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter { it in '0'..'9' }.take(6) },
                    label = { Text("Verification code") },
                    enabled = !busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("New password") },
                    enabled = !busy,
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Toggle password visibility")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm new password") },
                    enabled = !busy,
                    singleLine = true,
                    isError = mismatch,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
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
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Reset password")
                }
                TextButton(onClick = { enterCodeStep = false }, enabled = !busy) {
                    Text("Back to request code")
                }
            }
        }
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Back to log in")
        }
    }
}
