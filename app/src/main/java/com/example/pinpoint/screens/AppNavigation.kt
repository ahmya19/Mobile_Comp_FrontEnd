package com.example.pinpoint.screens

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun AppNavigation() {

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {

        // HOME
        composable("home") {
            HomeScreen(
                onQuickReportClick = {
                    navController.navigate("report")
                },
                onReportsClick = {
                    navController.navigate("reports")
                },
                onMapClick = {
                    navController.navigate("map")
                },
                onProfileClick = {
                    navController.navigate("profile")
                },
                onNotificationsClick = {
                    navController.navigate("notifications")
                }
            )
        }

        // CREATE REPORT
        composable("report") {
            ReportScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onSubmitClick = {
                    navController.navigate("reportSuccess")
                }
            )
        }

        // REPORT SUCCESS
        composable("reportSuccess") {
            ReportSuccessScreen(
                onViewReportsClick = {
                    navController.navigate("reports") {
                        popUpTo("home")
                    }
                },
                onHomeClick = {
                    navController.navigate("home") {
                        popUpTo("home") {
                            inclusive = true
                        }
                    }
                }
            )
        }

        // MY REPORTS
        composable("reports") {
            MyReportsScreen(
                onReportClick = {
                    navController.navigate("reportDetails")
                },
                onHomeClick = {
                    navController.navigate("home")
                },
                onMapClick = {
                    navController.navigate("map")
                },
                onProfileClick = {
                    navController.navigate("profile")
                }
            )
        }

        // REPORT DETAILS
        composable("reportDetails") {
            ReportDetailsScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // MAP
        composable("map") {
            MapScreen(
                onHomeClick = {
                    navController.navigate("home")
                },
                onReportsClick = {
                    navController.navigate("reports")
                },
                onProfileClick = {
                    navController.navigate("profile")
                }
            )
        }

        // PROFILE
        composable("profile") {
            ProfileScreen(
                onHomeClick = {
                    navController.navigate("home")
                },
                onReportsClick = {
                    navController.navigate("reports")
                },
                onMapClick = {
                    navController.navigate("map")
                }
            )
        }

        // NOTIFICATIONS
        composable("notifications") {
            NotificationsScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}