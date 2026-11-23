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
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.pinpoint.ui.theme.VioletGlow
import com.example.pinpoint.ui.theme.White

/** Floating dark capsule tab bar with elevated centre violet Report button (PDF Page 1 & Boards 2, 4, 6, 9). */
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
            .background(Color.Transparent)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 10.dp)
            .height(if (onReportClick != null) 78.dp else 64.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(28.dp),
            color = InkCard,
            shadowElevation = 8.dp
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
                    selectedIcon = Icons.Filled.Home,
                    unselectedIcon = Icons.Outlined.Home,
                    label = "Home",
                    description = "Home",
                    onClick = onHomeClick,
                    modifier = Modifier.weight(1f)
                )
                BarItem(
                    selected = selectedRoute == "reports",
                    selectedIcon = Icons.Filled.Assignment,
                    unselectedIcon = Icons.Outlined.Assignment,
                    label = "Reports",
                    description = "My Reports",
                    onClick = onReportsClick,
                    modifier = Modifier.weight(1f)
                )
                if (onReportClick != null) {
                    Spacer(Modifier.width(66.dp))
                }
                BarItem(
                    selected = selectedRoute == "map",
                    selectedIcon = Icons.Filled.Map,
                    unselectedIcon = Icons.Outlined.Map,
                    label = "Map",
                    description = "Map",
                    onClick = onMapClick,
                    modifier = Modifier.weight(1f)
                )
                BarItem(
                    selected = selectedRoute == "profile",
                    selectedIcon = Icons.Filled.Person,
                    unselectedIcon = Icons.Outlined.Person,
                    label = "Profile",
                    description = "Profile",
                    onClick = onProfileClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Elevated protruding centre Report FAB with soft violet glow (PDF Page 1)
        if (onReportClick != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(VioletGlow),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = "Create report" }
                        .clickable(role = Role.Button, onClick = onReportClick),
                    shape = CircleShape,
                    color = Violet,
                    shadowElevation = 6.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = null,
                            tint = White,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BarItem(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
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
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}
