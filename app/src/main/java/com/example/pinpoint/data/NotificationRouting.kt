package com.example.pinpoint.data

import android.app.NotificationManager
import android.content.Context

/** Report-update notifications are recipient-bound: only the account that received one may open it. */
fun notificationTargetAllowed(intentUserId: String?, currentUserId: String?): Boolean =
    !intentUserId.isNullOrBlank() && intentUserId == currentUserId

/** Report-update notifications are posted with id 0 and a per-notification tag; upload progress uses another id. */
fun cancelReportNotifications(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    runCatching { manager.activeNotifications.filter { it.id == 0 && it.tag != null }.forEach { manager.cancel(it.tag, it.id) } }
}
