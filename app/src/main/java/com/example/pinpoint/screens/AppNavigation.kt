package com.example.pinpoint.screens

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.pinpoint.data.PinPointViewModel

@Composable
fun AppNavigation(model: PinPointViewModel = viewModel(),notificationReportId: String?=null,onNotificationConsumed: ()->Unit={}) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) model.registerPushToken()
    }
    LaunchedEffect(state.user?.id) {
        if (state.user != null) {
            if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("report_updates", "Report updates", NotificationManager.IMPORTANCE_DEFAULT))
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            Row {
                TextButton(onClick = model::retry, enabled = !state.busy) { Text("Refresh") }
                TextButton(onClick = model::clearFeedback) { Text("Dismiss") }
            }
        }
        state.message?.let { Text(it, modifier = Modifier.padding(12.dp)) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f)) {
            when {
                !state.configured -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Backend setup required", style = MaterialTheme.typography.headlineMedium)
                    Text("Connect the team's Supabase Free project, then rebuild the app.")
                }
                state.user == null && state.confirmation != null -> EmailConfirmationScreen(
                    requireNotNull(state.confirmation),state.busy,model::verifyEmail,
                    model::resendConfirmation,model::cancelConfirmation)
                state.user == null -> AuthScreen(state.busy, model::authenticate, model::enterConfirmation)
                else -> key(state.user?.id) {
                    val nav = rememberNavController()
                    fun go(route: String) { nav.navigate(route) { launchSingleTop = true } }
                    fun details(id: String) { go("reportDetails/$id") }
                    LaunchedEffect(notificationReportId) {
                        notificationReportId?.let { details(it);onNotificationConsumed() }
                    }
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(onQuickReportClick = { model.startReport(); go("report") }, onReportsClick = { go("reports") },
                                onMapClick = { go("map") }, onProfileClick = { go("profile") },
                                onNotificationsClick = { go("notifications") }, reports = state.reports,
                                community = state.community, loading = state.loading,
                                onCommunityReportsClick = { go("community") })
                        }
                        composable("report") {
                            LaunchedEffect(state.submittedReportId) {
                                if (state.submittedReportId != null) {
                                    model.acknowledgeSubmission()
                                    nav.navigate("reportSuccess") { popUpTo("report") { inclusive = true } }
                                }
                            }
                            ReportScreen(onBackClick = { nav.popBackStack() }, busy = state.busy,
                                onSubmitClick = model::submit)
                        }
                        composable("reportSuccess") {
                            ReportSuccessScreen(pending=state.submissionPending,onViewReportsClick = { nav.navigate("reports") { popUpTo("home") } },
                                onHomeClick = { nav.navigate("home") { popUpTo("home") { inclusive = true } } })
                        }
                        composable("reports") {
                            MyReportsScreen(reports = state.reports, loading = state.loading,
                                feed=state.mineFeed,onFilter={model.filterReports(true,it)},onLoadMore={model.loadMoreReports(true)},
                                pendingUploads=state.pendingUploads,onRetryUploads=model::retryUploads,
                                onReportClick = ::details, onHomeClick = { go("home") },
                                onMapClick = { go("map") }, onProfileClick = { go("profile") })
                        }
                        composable("community") {
                            MyReportsScreen(reports = state.community, loading = state.loading,
                                feed=state.communityFeed,onFilter={model.filterReports(false,it)},onLoadMore={model.loadMoreReports(false)},
                                onReportClick = ::details, onHomeClick = { go("home") },
                                onMapClick = { go("map") }, onProfileClick = { go("profile") }, communityFeed = true)
                        }
                        composable("reportDetails/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                            val id = requireNotNull(entry.arguments?.getString("id"))
                            LaunchedEffect(id) { model.openReport(id) }
                            ReportDetailsScreen(onBackClick = { nav.popBackStack() }, report = state.selectedReport,
                                loading = state.detailLoading, error = state.detailError, photo = state.photo,
                                official = state.user?.official == true && state.user?.barangay==state.selectedReport?.barangay, busy = state.busy,
                                onUpdateStatus = { status, priority -> model.updateStatus(id, status, priority) },
                                hasVoted=state.hasVoted,onVote={model.vote(id)},
                                canReadOriginal=(state.user?.official==true && state.user?.barangay==state.selectedReport?.barangay) || state.selectedReport?.ownerId==state.user?.id,
                                onShowOriginal=model::showOriginal,history=state.statusHistory)
                        }
                        composable("map") {
                            MapScreen(onHomeClick = { go("home") }, onReportsClick = { go("reports") },
                                onProfileClick = { go("profile") },reports=state.community,onReportClick=::details,
                                feed=state.communityFeed,onFilter={model.filterReports(false,it)},onLoadMore={model.loadMoreReports(false)})
                        }
                        composable("profile") {
                            ProfileScreen(user = requireNotNull(state.user), busy = state.busy,
                                onSaveProfile = model::editProfile, onSignOut = model::signOut,
                                onHomeClick = { go("home") }, onReportsClick = { go("reports") }, onMapClick = { go("map") })
                        }
                        composable("notifications") {
                            NotificationsScreen(notifications = state.notifications, hasMore = state.notificationsHasMore, loadingMore = state.notificationsLoadingMore, onLoadMore = model::loadMoreNotifications, onBackClick = { nav.popBackStack() },
                                onNotificationClick = { notice -> model.markRead(notice.id); details(notice.reportId) })
                        }
                    }
                }
            }
        }
    }
}
