package com.example.pinpoint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.pinpoint.screens.AppNavigation
import com.example.pinpoint.ui.theme.PinPointTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PinPointTheme {
                AppNavigation()
            }
        }
    }
}