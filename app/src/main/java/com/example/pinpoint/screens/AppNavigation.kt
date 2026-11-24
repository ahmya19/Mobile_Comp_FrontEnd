package com.example.pinpoint.screens

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.pinpoint.data.notificationTargetAllowed
import com.example.pinpoint.data.PinPointViewModel
import com.example.pinpoint.ui.theme.White
import java.net.URLEncoder
import kotlinx.coroutines.delay

@Composable
fun AppNavigation(
    model: PinPointViewModel = viewModel(),
    notificationReportId: String? = null,
    notificationUserId: String? = null,
    onNotificationConsumed: () -> Unit = {}
) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSplash by rememberSaveable { mutableStateOf(true) }
    var onboardingDone by rememberSaveable { mutableStateOf(OnboardingStore.isOnboardingDone(context)) }
    var showPrimerBeforeAuth by rememberSaveable { mutableStateOf(false) }
    var replayTourAuth by rememberSaveable { mutableStateOf(false) }
    var recoveryEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSuccessId by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) model.registerPushToken()
    }
    LaunchedEffect(Unit) {
        if (showSplash) {
            delay(650)
            showSplash = false
        }
    }
    LaunchedEffect(state.user?.id) {
        if (state.user != null) {
            // Returning signed-in users should never be sent back through first-run onboarding on sign-out.
            if (!onboardingDone) {
                OnboardingStore.markOnboardingDone(context)
                onboardingDone = true
            }
            showPrimerBeforeAuth = false
            replayTourAuth = false
            recoveryEmail = null
            if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("report_updates", "Report updates", NotificationManager.IMPORTANCE_DEFAULT))
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Box(Modifier.fillMaxSize().background(White)) {
    Column(Modifier.fillMaxSize().background(White).systemBarsPadding()) {
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
                    requireNotNull(state.confirmation), state.busy, model::verifyEmail,
                    model::resendConfirmation, model::cancelConfirmation
                )
                state.user == null && recoveryEmail != null -> PasswordRecoveryScreen(
                    initialEmail = recoveryEmail.orEmpty(),
                    busy = state.busy,
                    onSendCode = model::requestPasswordReset,
                    onResetPassword = model::completePasswordReset,
                    onBack = { recoveryEmail = null; model.clearFeedback() }
                )
                state.user == null && showPrimerBeforeAuth -> PermissionPrimerScreen(
                    onContinue = {
                        OnboardingStore.markPrimerDone(context)
                        OnboardingStore.markOnboardingDone(context)
                        onboardingDone = true
                        showPrimerBeforeAuth = false
                        replayTourAuth = false
                    },
                    onSkip = {
                        OnboardingStore.markPrimerDone(context)
                        OnboardingStore.markOnboardingDone(context)
                        onboardingDone = true
                        showPrimerBeforeAuth = false
                        replayTourAuth = false
                    }
                )
                state.user == null && (!onboardingDone || replayTourAuth) -> OnboardingScreen(
                    onGetStarted = {
                        OnboardingStore.markOnboardingDone(context)
                        onboardingDone = true
                        replayTourAuth = false
                        showPrimerBeforeAuth = true
                    },
                    onAlreadyHaveAccount = {
                        OnboardingStore.markOnboardingDone(context)
                        onboardingDone = true
                        replayTourAuth = false
                    },
                    onSkip = {
                        OnboardingStore.markOnboardingDone(context)
                        onboardingDone = true
                        replayTourAuth = false
                    }
                )
                state.user == null -> AuthScreen(
                    busy = state.busy,
                    onAuthenticate = model::authenticate,
                    onConfirmEmail = model::enterConfirmation,
                    onForgotPassword = { email -> recoveryEmail = email; model.clearFeedback() },
                    onViewTour = { replayTourAuth = true }
                )
                else -> key(state.user?.id) {
                    val nav = rememberNavController()
                    val tabRoutes = remember { setOf("home", "reports", "map", "profile") }
                    fun go(route: String) { nav.navigate(route) { launchSingleTop = true } }
                    fun details(id: String) { go("reportDetails/$id") }
                    fun openReport(category: String = "") {
                        model.startReport()
                        val encoded = URLEncoder.encode(category, "UTF-8")
                        go("report?category=$encoded")
                    }
                    LaunchedEffect(notificationReportId, state.user?.id) {
                        // A notification belongs to the account that received it; another signed-in account ignores it.
                        notificationReportId?.let { if (notificationTargetAllowed(notificationUserId, state.user?.id)) details(it); onNotificationConsumed() }
                    }
                    NavHost(
                        navController = nav,
                        startDestination = "home",
                        enterTransition = {
                            val dest = targetState.destination.route?.substringBefore('?')?.substringBefore('/')
                            if (dest in tabRoutes) fadeIn(tween(180))
                            else slideInHorizontally(tween(240)) { it / 3 } + fadeIn(tween(200))
                        },
                        exitTransition = {
                            val dest = targetState.destination.route?.substringBefore('?')?.substringBefore('/')
                            if (dest in tabRoutes) fadeOut(tween(150))
                            else slideOutHorizontally(tween(240)) { -it / 6 } + fadeOut(tween(180))
                        },
                        popEnterTransition = {
                            val src = initialState.destination.route?.substringBefore('?')?.substringBefore('/')
                            if (src in tabRoutes) fadeIn(tween(180))
                            else slideInHorizontally(tween(240)) { -it / 6 } + fadeIn(tween(200))
                        },
                        popExitTransition = {
                            val src = initialState.destination.route?.substringBefore('?')?.substringBefore('/')
                            if (src in tabRoutes) fadeOut(tween(150))
                            else slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(180))
                        }
                    ) {
                        composable("home") {
                            HomeScreen(
                                onQuickReportClick = { openReport() },
                                onReportsClick = { go("reports") },
                                onMapClick = { go("map") },
                                onProfileClick = { go("profile") },
                                onNotificationsClick = { go("notifications") },
                                reports = state.reports,
                                community = state.community,
                                loading = state.loading,
                                onCommunityReportsClick = { go("community") },
                                user = state.user,
                                unreadNotifications = state.notifications.count { !it.read },
                                onReportClick = ::details,
                                onQuickCategoryClick = { cat -> openReport(cat) }
                            )
                        }
                        composable(
                            route = "report?category={category}",
                            arguments = listOf(navArgument("category") { type = NavType.StringType; defaultValue = "" })
                        ) { entry ->
                            val initialCategory = entry.arguments?.getString("category").orEmpty()
                            LaunchedEffect(state.submittedReportId) {
                                val submitted = state.submittedReportId
                                if (submitted != null) {
                                    lastSuccessId = submitted
                                    model.acknowledgeSubmission()
                                    nav.navigate("reportSuccess") { popUpTo("report?category={category}") { inclusive = true } }
                                }
                            }
                            ReportScreen(
                                onBackClick = { nav.popBackStack() },
                                busy = state.busy,
                                onSubmitClick = model::submit,
                                initialCategory = initialCategory,
                                savedDraft = state.savedDraft,
                                onSaveDraftClick = model::saveDraft,
                                onDiscardDraftClick = model::discardDraft
                            )
                        }
                        composable("reportSuccess") {
                            ReportSuccessScreen(
                                pending = state.submissionPending,
                                reportId = lastSuccessId,
                                onViewReportsClick = { nav.navigate("reports") { popUpTo("home") } },
                                onHomeClick = { nav.navigate("home") { popUpTo("home") { inclusive = true } } }
                            )
                        }
                        composable("reports") {
                            MyReportsScreen(
                                reports = state.reports,
                                loading = state.loading,
                                feed = state.mineFeed,
                                onFilter = { model.filterReports(true, it) },
                                onLoadMore = { model.loadMoreReports(true) },
                                pendingUploads = state.pendingUploads,
                                onRetryUploads = model::retryUploads,
                                onReportClick = ::details,
                                onHomeClick = { go("home") },
                                onMapClick = { go("map") },
                                onProfileClick = { go("profile") },
                                onReportCreateClick = { openReport() },
                                onManageSavedClick = { go("savedReports") }
                            )
                        }
                        composable("savedReports") {
                            SavedReportsScreen(
                                items = state.queuedReports,
                                busy = state.busy,
                                onBackClick = { nav.popBackStack() },
                                onRetryAll = model::retryUploads,
                                onEditItem = model::updateSavedReport,
                                onDeleteItem = model::deleteSavedReport,
                                onSmsFallbackClick = { go("smsGuide") }
                            )
                        }
                        composable("community") {
                            MyReportsScreen(
                                reports = state.community,
                                loading = state.loading,
                                feed = state.communityFeed,
                                onFilter = { model.filterReports(false, it) },
                                onLoadMore = { model.loadMoreReports(false) },
                                onReportClick = ::details,
                                onHomeClick = { go("home") },
                                onMapClick = { go("map") },
                                onProfileClick = { go("profile") },
                                communityFeed = true,
                                onReportCreateClick = { openReport() }
                            )
                        }
                        composable("reportDetails/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                            val id = requireNotNull(entry.arguments?.getString("id"))
                            LaunchedEffect(id) { model.openReport(id) }
                            ReportDetailsScreen(
                                onBackClick = { nav.popBackStack() },
                                report = state.selectedReport,
                                loading = state.detailLoading,
                                error = state.detailError,
                                photo = state.photo,
                                official = state.user?.official == true && state.user?.barangay == state.selectedReport?.barangay,
                                busy = state.busy,
                                onUpdateStatus = { status, priority -> model.updateStatus(id, status, priority) },
                                hasVoted = state.hasVoted,
                                onVote = { model.vote(id) },
                                canReadOriginal = (state.user?.official == true && state.user?.barangay == state.selectedReport?.barangay) || state.selectedReport?.ownerId == state.user?.id,
                                onShowOriginal = model::showOriginal,
                                history = state.statusHistory
                            )
                        }
                        composable("map") {
                            MapScreen(
                                onHomeClick = { go("home") },
                                onReportsClick = { go("reports") },
                                onProfileClick = { go("profile") },
                                reports = state.community,
                                onReportClick = ::details,
                                feed = state.communityFeed,
                                onFilter = { model.filterReports(false, it) },
                                onLoadMore = { model.loadMoreReports(false) },
                                onReportCreateClick = { openReport() }
                            )
                        }
                        composable("profile") {
                            ProfileScreen(
                                user = requireNotNull(state.user),
                                busy = state.busy,
                                onSaveProfile = model::editProfile,
                                onSignOut = model::signOut,
                                onHomeClick = { go("home") },
                                onReportsClick = { go("reports") },
                                onMapClick = { go("map") },
                                reports = state.reports,
                                pendingUploads = state.pendingUploads,
                                onReportCreateClick = { openReport() },
                                onChangePassword = model::changePassword,
                                onSmsGuideClick = { go("smsGuide") },
                                onManageSavedClick = { go("savedReports") },
                                onAppTourClick = { go("appTour") },
                                onPermissionsClick = { go("permissions") }
                            )
                        }
                        composable("appTour") {
                            OnboardingScreen(
                                onGetStarted = { go("permissions") },
                                onAlreadyHaveAccount = { nav.popBackStack() },
                                onSkip = { nav.popBackStack() },
                                previewMode = true
                            )
                        }
                        composable("permissions") {
                            PermissionPrimerScreen(
                                onContinue = { nav.popBackStack("profile", inclusive = false) },
                                onSkip = { nav.popBackStack("profile", inclusive = false) },
                                stepLabel = "APP PERMISSIONS"
                            )
                        }
                        composable("notifications") {
                            NotificationsScreen(
                                notifications = state.notifications,
                                hasMore = state.notificationsHasMore,
                                loadingMore = state.notificationsLoadingMore,
                                onLoadMore = model::loadMoreNotifications,
                                onBackClick = { nav.popBackStack() },
                                onNotificationClick = { notice -> model.markRead(notice.id); details(notice.reportId) },
                                onMarkAllRead = model::markAllNotificationsRead,
                                onSmsGuideClick = { go("smsGuide") }
                            )
                        }
                        composable("smsGuide") {
                            SmsGuideScreen(onBackClick = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
    AnimatedVisibility(
        visible = showSplash,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(280))
    ) {
        PinPointSplashScreen()
    }
    }
}
