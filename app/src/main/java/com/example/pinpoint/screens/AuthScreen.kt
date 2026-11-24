package com.example.pinpoint.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
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

/** Login (PDF 1.3) and Registration (PDF 1.4) on pure white canvas with 52dp filled #F5F5F8 fields. */
@Composable
fun AuthScreen(
    busy: Boolean,
    onAuthenticate: (Boolean, String, String, String) -> Unit,
    onConfirmEmail: (String) -> Unit,
    onForgotPassword: (String) -> Unit = {},
    onViewTour: (() -> Unit)? = null
) {
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var acceptedTerms by rememberSaveable { mutableStateOf(true) }

    // System Back on Register returns to Sign in (it is a mode of this screen, not a separate destination) instead of leaving the app (CP-55).
    BackHandler(enabled = register) { register = false; password = "" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (register) {
            PinPointTopBar(
                title = "",
                onBackClick = { register = false; password = "" },
                backEnabled = !busy,
                trailing = {
                    Text(
                        "STEP 1 OF 2",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        } else {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Violet),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, tint = White, modifier = Modifier.size(26.dp))
                }
                Text("PinPoint", style = MaterialTheme.typography.titleLarge, color = Black)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (register) "Create your account" else "Welcome back",
                style = MaterialTheme.typography.headlineLarge,
                color = Black
            )
            Text(
                text = if (register) "Public signups create citizen accounts. Officials are assigned by barangay administration."
                else "Sign in to your community",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText
            )
        }

        Spacer(Modifier.height(2.dp))

        AnimatedVisibility(
            visible = register,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Full name") },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp)) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = pinPointFieldColors()
            )
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp)) },
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = pinPointFieldColors()
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp)) },
            enabled = !busy,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = MutedText
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = pinPointFieldColors()
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { password = ""; onForgotPassword(email) }, enabled = !busy) {
                    Text("Forgot password?", color = DeepViolet, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Button(
            onClick = { onAuthenticate(register, name, email, password); password = "" },
            enabled = !busy && email.isNotBlank() && password.isNotBlank() && (!register || (name.isNotBlank() && acceptedTerms)),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = White)
        ) {
            Text(
                text = if (busy) "Please wait..." else if (register) "Create account" else "Sign in",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.weight(1f, fill = false))

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TextButton(onClick = { register = !register; password = "" }, enabled = !busy) {
                Text(
                    if (register) "Already have an account? Sign in" else "New to PinPoint? Create account",
                    color = DeepViolet,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (!register) {
                TextButton(
                    onClick = { password = ""; onConfirmEmail(email) },
                    enabled = !busy && email.isNotBlank()
                ) {
                    Text("Confirm an existing account", color = if (email.isNotBlank()) MutedText else MutedText.copy(alpha = 0.5f))
                }
                if (onViewTour != null) {
                    TextButton(onClick = onViewTour, enabled = !busy) {
                        Text("View app tour", color = MutedText)
                    }
                }
            }
        }
    }
}
