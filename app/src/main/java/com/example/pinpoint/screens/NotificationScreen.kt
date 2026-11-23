package com.example.pinpoint.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pinpoint.data.ReportNotification
import com.example.pinpoint.ui.theme.*

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
            .background(PinPointSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBackClick) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            if (unreadCount > 0) {
                TextButton(onClick = onMarkAllRead) { Text("Mark all read", color = DeepViolet) }
            }
        }

        Text(text = "Notifications", style = MaterialTheme.typography.headlineMedium, color = Black)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Unread", "Resolved").forEach { tab ->
                FilterChip(
                    selected = filterMode == tab,
                    onClick = { filterMode = tab },
                    label = { Text(if (tab == "Unread" && unreadCount > 0) "Unread ($unreadCount)" else tab) }
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onSmsGuideClick),
            shape = RoundedCornerShape(14.dp),
            color = White,
            border = BorderStroke(1.dp, SoftBorder)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Sms, contentDescription = null, tint = Violet)
                Column(modifier = Modifier.weight(1f)) {
                    Text("SMS reporting guide", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Black)
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
            }
            items(visible, key = { it.id }) { notification ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onNotificationClick(notification) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (notification.read) White else VioletTint,
                    border = BorderStroke(1.dp, if (notification.read) SoftBorder else Violet.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!notification.read) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Violet)
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = (if (notification.read) "" else "New: ") + notification.message,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (notification.read) FontWeight.Normal else FontWeight.SemiBold,
                                color = Black
                            )
                            if (notification.createdAt.isNotBlank()) {
                                Text(notification.createdAt, style = MaterialTheme.typography.labelSmall, color = MutedText)
                            }
                        }
                    }
                }
            }
            if (hasMore) {
                item {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !loadingMore,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (loadingMore) "Loading…" else "Load older notifications")
                    }
                }
            }
        }
    }
}
