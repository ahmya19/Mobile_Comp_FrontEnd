package com.example.pinpoint.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun PinPointBottomBar(
    selectedRoute: String,
    onHomeClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMapClick: () -> Unit,
    onProfileClick: () -> Unit
) {

    NavigationBar {

        NavigationBarItem(
            selected = selectedRoute == "home",
            onClick = onHomeClick,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = "Home"
                )
            },
            label = {
                Text("Home")
            }
        )

        NavigationBarItem(
            selected = selectedRoute == "reports",
            onClick = onReportsClick,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Assignment,
                    contentDescription = "My Reports"
                )
            },
            label = {
                Text("Reports")
            }
        )

        NavigationBarItem(
            selected = selectedRoute == "map",
            onClick = onMapClick,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Map,
                    contentDescription = "Map"
                )
            },
            label = {
                Text("Map")
            }
        )

        NavigationBarItem(
            selected = selectedRoute == "profile",
            onClick = onProfileClick,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "Profile"
                )
            },
            label = {
                Text("Profile")
            }
        )
    }
}