package com.example.pinpoint.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val PinPointColorScheme = lightColorScheme(
    primary = Violet,
    onPrimary = White,

    secondary = Amber,
    onSecondary = Black,

    background = PinPointSurface,
    onBackground = Black,

    surface = White,
    onSurface = Black,

    error = AlertRed,
    onError = White
)

@Composable
fun PinPointTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PinPointColorScheme,
        typography = PinPointTypography,
        content = content
    )
}