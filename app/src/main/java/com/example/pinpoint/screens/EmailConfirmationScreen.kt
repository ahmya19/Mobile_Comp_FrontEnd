package com.example.pinpoint.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.PendingEmailConfirmation
import com.example.pinpoint.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun EmailConfirmationScreen(
    pending: PendingEmailConfirmation,
    busy: Boolean,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onCancel: () -> Unit
) {
    // The OTP is deliberately absent from saved instance state and persistent storage.
    var code by remember(pending.email) { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pending.resendAvailableAt) {
        do { now = System.currentTimeMillis(); delay(1000) } while (now < pending.resendAvailableAt)
    }
    val seconds = ((pending.resendAvailableAt - now + 999) / 1000).coerceAtLeast(0)
    BackHandler { if (!busy) onCancel() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PinPointSurface)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Confirm your email", style = MaterialTheme.typography.headlineMedium, color = Black)
        SectionSurfaceCard {
            Text(pending.email, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = DeepViolet)
            Text(
                "Enter the six-digit code from your PinPoint confirmation email. Check your spam folder too.",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText
            )
            OutlinedTextField(
                value = code,
                onValueChange = { value -> code = value.filter { it in '0'..'9' }.take(6) },
                label = { Text("Confirmation code") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            Button(
                onClick = { val submitted = code; code = ""; onVerify(submitted) },
                enabled = !busy && code.length == 6,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Verify email")
            }
            OutlinedButton(
                onClick = onResend,
                enabled = !busy && seconds == 0L,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (seconds > 0) "Resend code in ${seconds}s" else "Resend code")
            }
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Use another email / Sign in")
            }
        }
    }
}
