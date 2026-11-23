package com.example.pinpoint.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.ReportNotification
import com.example.pinpoint.data.UiText
import com.example.pinpoint.ui.theme.*

/** Notification center styled to PDF Board 8.1 (solid dark filter pills, status-tinted icon squares, violet unread tint & dot). */
@Composable
fun NotificationsScreen(
    onBackClick: () -> Unit,
    notifications: List<ReportNotification>,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onNotificationClick: (ReportNotification) -> Unit,
    onMarkAllRead: () -> Unit = {},
    onSmsGuideClick: () -> Unit = {}
) {
    var filterMode by rememberSaveable { mutableStateOf("All") }
    val unreadCount = notifications.count { !it.read }
    val visible = remember(notifications, filterMode) {
        when (filterMode) {
            "Unread" -> notifications.filter { !it.read }
            "Resolved" -> notifications.filter { it.message.contains("resolved", ignoreCase = true) }
            else -> notifications
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PinPointTopBar(
            title = "Notifications",
            onBackClick = onBackClick,
            trailing = {
                if (unreadCount > 0) {
                    TextButton(onClick = onMarkAllRead) {
                        Text("Mark all read", color = DeepViolet, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        )

        // Solid dark active filter pills (PDF 8.1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Unread", "Resolved").forEach { tab ->
                SolidFilterChip(
                    label = if (tab == "Unread" && unreadCount > 0) "Unread ($unreadCount)" else tab,
                    selected = filterMode == tab,
                    onClick = { filterMode = tab }
                )
            }
        }

        // Compact SMS guide shortcut banner
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onSmsGuideClick),
            shape = RoundedCornerShape(16.dp),
            color = PinPointSurface
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(VioletTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Sms, contentDescription = null, tint = DeepViolet, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("SMS reporting guide", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Black)
                    Text("Learn how to submit a basic report by text without mobile data.", style = MaterialTheme.typography.bodySmall, color = MutedText)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (visible.isEmpty()) {
                item {
                    SectionSurfaceCard {
                        Text(
                            if (notifications.isEmpty()) "No notifications yet." else "No notifications match this filter.",
                            color = MutedText
                        )
                    }
                }
            } else {
                item {
                    Text(
                        text = if (unreadCount > 0 && filterMode != "Resolved") "RECENT UPDATES" else "NOTIFICATIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            items(visible, key = { it.id }) { notification ->
                val isResolved = notification.message.contains("resolved", ignoreCase = true)
                val isProgress = notification.message.contains("progress", ignoreCase = true)
                val (icon, iconTint, iconBg) = when {
                    isResolved -> Triple(Icons.Filled.CheckCircle, ResolvedGreen, GreenTint)
                    isProgress -> Triple(Icons.Filled.Sync, DeepViolet, VioletTint)
                    else -> Triple(Icons.Filled.NotificationsActive, SkyText, SkyTint)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onNotificationClick(notification) },
                    shape = RoundedCornerShape(18.dp),
                    color = if (notification.read) PinPointSurface else VioletTint
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (notification.read) iconBg else White),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = (if (notification.read) "" else "New: ") + notification.message,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (notification.read) FontWeight.Normal else FontWeight.SemiBold,
                                color = Black
                            )
                            if (notification.createdAt.isNotBlank()) {
                                Text(UiText.formatTimestamp(notification.createdAt), style = MaterialTheme.typography.labelSmall, color = MutedText)
                            }
                        }
                        if (!notification.read) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Violet)
                            )
                        }
                    }
                }
            }

            if (hasMore) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !loadingMore, onClick = onLoadMore),
                        shape = RoundedCornerShape(14.dp),
                        color = PinPointSurface
                    ) {
                        Box(modifier = Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                            Text(if (loadingMore) "Loading…" else "Load older notifications", style = MaterialTheme.typography.labelLarge, color = DeepViolet, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
