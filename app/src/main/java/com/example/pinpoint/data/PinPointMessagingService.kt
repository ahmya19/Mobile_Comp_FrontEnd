package com.example.pinpoint.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.pinpoint.MainActivity
import com.example.pinpoint.BuildConfig
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PinPointMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (BuildConfig.FIREBASE_EMULATORS) return
        // Durable retry also happens whenever a signed-in app is opened.
        val backend = FirebaseBackend.create(this) ?: return
        if (backend.auth.currentUser == null) return
        val id = getSharedPreferences("pinpoint_device", 0).getString("id", null) ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { PinPointRepository(backend).registerDevice(id, token) }
            catch (_: Exception) { /* Retried on the next signed-in app launch. */ }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("report_updates", "Report updates", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "report_updates")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(message.notification?.title ?: "PinPoint")
            .setContentText(message.notification?.body ?: "Your report has been updated.")
            .setContentIntent(pending).setAutoCancel(true).build()
        NotificationManagerCompat.from(this).notify(
            message.data["notificationId"] ?: message.messageId ?: "pinpoint", 0, notification)
    }
}
