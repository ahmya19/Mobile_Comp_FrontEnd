package com.example.pinpoint.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.*

@Composable
fun AuthScreen(
    busy: Boolean,
    onAuthenticate: (Boolean, String, String, String) -> Unit,
    onConfirmEmail: (String) -> Unit,
    onForgotPassword: (String) -> Unit = {}
) {
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var acceptedTerms by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PinPointSurface)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Violet),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = White, modifier = Modifier.size(28.dp))
            }
            Column {
                Text("PinPoint", style = MaterialTheme.typography.headlineLarge, color = Black)
                Text(
                    text = if (register) "Create your citizen account" else "Sign in to your community",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedText
                )
            }
        }

        SectionSurfaceCard {
            Text(
                text = if (register) "Create your account" else "Welcome back",
                style = MaterialTheme.typography.titleLarge,
                color = Black,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (register) "Public signups create citizen accounts. Officials are assigned by barangay administration."
                else "Report local issues, vote on community priorities, and track barangay updates.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText
            )

            if (register) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full name") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                enabled = !busy,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            if (register) {
                PasswordStrengthChecklist(password)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acceptedTerms, onCheckedChange = { acceptedTerms = it }, enabled = !busy)
                    Text(
                        "I agree to PinPoint's community reporting terms and privacy notice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { password = ""; onForgotPassword(email) }, enabled = !busy) {
                        Text("Forgot password?", color = DeepViolet)
                    }
                }
            }

            Button(
                onClick = { onAuthenticate(register, name, email, password); password = "" },
                enabled = !busy && email.isNotBlank() && password.isNotBlank() && (!register || (name.isNotBlank() && acceptedTerms)),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(if (busy) "Please wait..." else if (register) "Create account" else "Sign in")
            }
        }

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = { register = !register; password = "" }, enabled = !busy) {
                Text(if (register) "Already have an account? Sign in" else "New here? Create account")
            }
            if (!register) {
                TextButton(
                    onClick = { password = ""; onConfirmEmail(email) },
                    enabled = !busy && email.isNotBlank()
                ) {
                    Text("Confirm an existing account")
                }
            }
        }
    }
}
