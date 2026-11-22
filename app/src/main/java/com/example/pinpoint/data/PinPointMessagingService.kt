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

class PinPointMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (!BuildConfig.FCM_CONFIGURED) return
        val backend = SupabaseBackend.create(this) ?: return
        val owner=backend.auth.currentUser?.id ?: return
        DeviceRegistrationWorker.schedule(this,owner)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val owner=SupabaseBackend.create(this)?.auth?.currentUser?.id ?: return
        if(message.data["userId"]!=owner) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("report_updates", "Report updates", NotificationManager.IMPORTANCE_DEFAULT))
        val reportId=message.data["reportId"]
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("reportId",reportId)
        val pending = PendingIntent.getActivity(this, (reportId ?: "pinpoint").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "report_updates")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(message.data["title"] ?: "Report updated")
            .setContentText(message.data["body"] ?: "Your report has been updated.")
            .setContentIntent(pending).setAutoCancel(true).build()
        NotificationManagerCompat.from(this).notify(
            message.data["notificationId"] ?: message.messageId ?: "pinpoint", 0, notification)
    }
}
