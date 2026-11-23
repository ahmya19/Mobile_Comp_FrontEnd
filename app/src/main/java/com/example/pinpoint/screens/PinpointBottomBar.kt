package com.example.pinpoint.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pinpoint.ui.theme.Amber
import com.example.pinpoint.ui.theme.DarkBarText
import com.example.pinpoint.ui.theme.InkCard
import com.example.pinpoint.ui.theme.Violet
import com.example.pinpoint.ui.theme.White

@Composable
fun PinPointBottomBar(
    selectedRoute: String,
    onHomeClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMapClick: () -> Unit,
    onProfileClick: () -> Unit,
    onReportClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
            shape = RoundedCornerShape(24.dp),
            color = InkCard,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BarItem(
                    selected = selectedRoute == "home",
                    icon = Icons.Filled.Home,
                    label = "Home",
                    description = "Home",
                    onClick = onHomeClick,
                    modifier = Modifier.weight(1f)
                )
                BarItem(
                    selected = selectedRoute == "reports",
                    icon = Icons.Filled.Assignment,
                    label = "Reports",
                    description = "My Reports",
                    onClick = onReportsClick,
                    modifier = Modifier.weight(1f)
                )
                if (onReportClick != null) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Violet)
                            .semantics { contentDescription = "Create report" }
                            .clickable(role = Role.Button, onClick = onReportClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = White, modifier = Modifier.size(24.dp))
                    }
                }
                BarItem(
                    selected = selectedRoute == "map",
                    icon = Icons.Filled.Map,
                    label = "Map",
                    description = "Map",
                    onClick = onMapClick,
                    modifier = Modifier.weight(1f)
                )
                BarItem(
                    selected = selectedRoute == "profile",
                    icon = Icons.Filled.Person,
                    label = "Profile",
                    description = "Profile",
                    onClick = onProfileClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BarItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) Amber else DarkBarText
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}
