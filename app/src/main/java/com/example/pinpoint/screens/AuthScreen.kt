package com.example.pinpoint.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AuthScreen(busy: Boolean, onAuthenticate: (Boolean, String, String, String) -> Unit,
    onConfirmEmail: (String) -> Unit) {
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PinPoint", style = MaterialTheme.typography.headlineLarge)
        Text(if (register) "Create your citizen account" else "Sign in to your community")
        if (register) OutlinedTextField(name, { name = it }, label = { Text("Full name") },
            enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { onAuthenticate(register, name, email, password); password = "" },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Please wait..." else if (register) "Create account" else "Sign in")
        }
        TextButton(onClick = { register = !register; password = "" }, enabled = !busy) {
            Text(if (register) "Already have an account? Sign in" else "New here? Create account")
        }
        if (!register) TextButton(onClick = { password=""; onConfirmEmail(email) }, enabled = !busy && email.isNotBlank()) {
            Text("Confirm an existing account")
        }
    }
}
