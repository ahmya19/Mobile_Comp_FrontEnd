package com.example.pinpoint

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.pinpoint.screens.AppNavigation
import com.example.pinpoint.ui.theme.PinPointTheme

class MainActivity : ComponentActivity() {
    private var pendingReportId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readReportIntent(intent)

        setContent {
            PinPointTheme {
                AppNavigation(notificationReportId=pendingReportId,onNotificationConsumed={pendingReportId=null})
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readReportIntent(intent)
    }

    private fun readReportIntent(intent: Intent?) {
        val id=intent?.getStringExtra("reportId") ?: return
        if(runCatching { UUID.fromString(id).toString()==id }.getOrDefault(false)) pendingReportId=id
    }
}
