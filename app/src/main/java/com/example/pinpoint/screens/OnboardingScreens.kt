package com.example.pinpoint.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.pinpoint.ui.theme.*
import kotlinx.coroutines.launch

/** Persists first-run onboarding & permission-primer completion separately from encrypted auth sessions. */
object OnboardingStore {
    private const val PREFS = "pinpoint_onboarding"
    private const val KEY_DONE = "onboarding_done"
    private const val KEY_PRIMER = "primer_done"

    fun isOnboardingDone(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markOnboardingDone(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_DONE, true) }
    }

    fun isPrimerDone(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PRIMER, false)

    fun markPrimerDone(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_PRIMER, true) }
    }

    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }
}

/** PDF 1.1: Full-bleed violet splash with soft ambient circle, warm amber accent, white pin badge and 3 pulsing dots. */
@Composable
fun PinPointSplashScreen(modifier: Modifier = Modifier) {
    val logoScale = remember { Animatable(0.84f) }
    LaunchedEffect(Unit) {
        logoScale.animateTo(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    val infinite = rememberInfiniteTransition(label = "splashDots")
    val dotPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "dotPhase"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Violet, Color(0xFF6B3EC0), DeepViolet)))
    ) {
        // Decorative ambient circles matching PDF 1.1
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = White.copy(alpha = 0.08f),
                radius = size.width * 0.42f,
                center = Offset(size.width * 0.08f, size.height * 0.24f)
            )
            drawCircle(
                color = Amber,
                radius = size.width * 0.28f,
                center = Offset(size.width * 0.94f, size.height * 0.62f)
            )
        }

        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(78.dp).scale(logoScale.value),
                shape = RoundedCornerShape(22.dp),
                color = White,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = "PinPoint logo",
                        tint = Violet,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }
            Text(
                text = "PinPoint",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp,
                color = White
            )
            Text(
                text = "Report. Track. Improve your community.",
                style = MaterialTheme.typography.bodyMedium,
                color = White.copy(alpha = 0.88f),
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { idx ->
                    val active = dotPhase.toInt() % 3 == idx
                    Box(
                        modifier = Modifier
                            .size(if (active) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(White.copy(alpha = if (active) 1f else 0.45f))
                    )
                }
            }
            Text(
                text = "Loading your community...",
                style = MaterialTheme.typography.labelSmall,
                color = White.copy(alpha = 0.82f)
            )
        }
    }
}

/** PDF 1.2: 3-slide swipeable citizen onboarding carousel matching Board 1.2 visual grammar. */
@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit,
    onAlreadyHaveAccount: () -> Unit,
    onSkip: () -> Unit = onAlreadyHaveAccount,
    previewMode: Boolean = false
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    val titles = listOf(
        "See a problem?\nPin it.",
        "Works even\nwithout signal.",
        "Track & upvote\ntogether."
    )
    val bodies = listOf(
        "Report road damage, garbage, broken lights and more, then watch your barangay respond in real time.",
        "Reports save safely on your device and sync automatically when internet returns — with Wi-Fi-only full photos and SMS fallback.",
        "Support urgent neighborhood issues with one tap and receive real-time push alerts on every official status update."
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top row: 1 OF 3 + Skip (PDF 1.2)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${pagerState.currentPage + 1} OF 3",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onSkip) {
                Text(
                    text = if (previewMode) "Close" else "Skip",
                    color = DeepViolet,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Swipeable 3-slide visual showcase + copy
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) { page ->
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                OnboardingDarkStage(page = page)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = titles[page],
                        style = MaterialTheme.typography.headlineLarge,
                        color = Black
                    )
                    Text(
                        text = bodies[page],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText
                    )
                }
            }
        }

        // Animated expanding pill dots + bottom actions (PDF 1.2)
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { idx ->
                    val selected = pagerState.currentPage == idx
                    val width by animateDpAsState(targetValue = if (selected) 24.dp else 8.dp, label = "onboardingDotW")
                    val color by animateColorAsState(targetValue = if (selected) Violet else SoftBorder, label = "onboardingDotC")
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(width)
                            .clip(RoundedCornerShape(999.dp))
                            .background(color)
                            .clickable { scope.launch { pagerState.animateScrollToPage(idx) } }
                    )
                }
            }

            Button(
                onClick = {
                    if (pagerState.currentPage < 2) {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    } else {
                        onGetStarted()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = White)
            ) {
                Text(
                    text = if (pagerState.currentPage < 2) "Next" else if (previewMode) "Review permissions" else "Get started",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!previewMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    color = PinPointSurface,
                    onClick = onAlreadyHaveAccount
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "I already have an account",
                            style = MaterialTheme.typography.titleSmall,
                            color = Black,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingDarkStage(page: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(250.dp),
        shape = RoundedCornerShape(24.dp),
        color = InkCard
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(18.dp)) {
            // Subtle concentric radar rings matching PDF 1.2
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                listOf(0.22f, 0.40f, 0.58f).forEach { factor ->
                    drawCircle(
                        color = White.copy(alpha = 0.08f),
                        radius = size.minDimension * factor,
                        center = center,
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }
            }

            // Glowing center icon
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Violet.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(50.dp).clip(CircleShape).background(Violet),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (page) {
                            0 -> Icons.Filled.LocationOn
                            1 -> Icons.Filled.CloudOff
                            else -> Icons.Filled.Notifications
                        },
                        contentDescription = null,
                        tint = White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Floating feature pills positioned cleanly around the radar (PDF 1.2)
            when (page) {
                0 -> {
                    OnboardingPill("Pothole", Amber, Black, Modifier.align(Alignment.TopStart))
                    OnboardingPill("Streetlight", White, Black, Modifier.align(Alignment.TopEnd).offset(y = 26.dp))
                    OnboardingPill("Flooding", SkyTint, SkyText, Modifier.align(Alignment.BottomStart))
                    OnboardingPill("Resolved", GreenTint, ResolvedGreen, Modifier.align(Alignment.BottomEnd), leadingCheck = true)
                }
                1 -> {
                    OnboardingPill("Saved offline", Amber, Black, Modifier.align(Alignment.TopStart), icon = Icons.Filled.CloudOff)
                    OnboardingPill("Wi-Fi originals", White, Black, Modifier.align(Alignment.TopEnd).offset(y = 26.dp), icon = Icons.Filled.Wifi)
                    OnboardingPill("SMS fallback", SkyTint, SkyText, Modifier.align(Alignment.BottomStart), icon = Icons.Filled.Sms)
                    OnboardingPill("Auto-syncs", GreenTint, ResolvedGreen, Modifier.align(Alignment.BottomEnd), leadingCheck = true)
                }
                else -> {
                    OnboardingPill("3+ votes · Med", Amber, Black, Modifier.align(Alignment.TopStart), icon = Icons.Filled.ThumbUp)
                    OnboardingPill("Live push alert", White, Black, Modifier.align(Alignment.TopEnd).offset(y = 26.dp), icon = Icons.Filled.Notifications)
                    OnboardingPill("Alangilan map", SkyTint, SkyText, Modifier.align(Alignment.BottomStart), icon = Icons.Filled.LocationOn)
                    OnboardingPill("Verified log", GreenTint, ResolvedGreen, Modifier.align(Alignment.BottomEnd), leadingCheck = true)
                }
            }
        }
    }
}

@Composable
private fun OnboardingPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leadingCheck: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (leadingCheck) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = contentColor, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** PDF 1.7: Location, Notification & Camera permission primer screen. */
@Composable
fun PermissionPrimerScreen(
    onContinue: () -> Unit,
    onSkip: () -> Unit = onContinue,
    stepLabel: String = "STEP 2 OF 2"
) {
    val context = LocalContext.current

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var hasLocation by remember {
        mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    var hasNotifications by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
    }
    var hasCamera by remember {
        mutableStateOf(granted(Manifest.permission.CAMERA))
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasLocation = results.values.any { it } || granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasNotifications = ok || Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasCamera = ok || granted(Manifest.permission.CAMERA)
    }
    val allLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        hasNotifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        hasCamera = granted(Manifest.permission.CAMERA)
        onContinue()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stepLabel, style = MaterialTheme.typography.labelSmall, color = MutedText, fontWeight = FontWeight.Bold)
            TextButton(onClick = onSkip) {
                Text("Skip", color = DeepViolet, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Let PinPoint work for your barangay", style = MaterialTheme.typography.headlineLarge, color = Black)
            Text(
                "Quick permissions make reporting faster. You can change them anytime in Android Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText
            )
        }

        PermissionPrimerCard(
            icon = Icons.Filled.LocationOn,
            iconBg = Violet,
            iconTint = White,
            title = "Location access",
            description = "Pin issues accurately in Alangilan and view nearby community reports.",
            actionLabel = if (hasLocation) "Location enabled" else "Allow while using the app",
            granted = hasLocation,
            onClick = {
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        )

        PermissionPrimerCard(
            icon = Icons.Filled.Notifications,
            iconBg = Amber,
            iconTint = Black,
            title = "Notifications",
            description = "Know right away when your report is reviewed, in progress, or resolved.",
            actionLabel = if (hasNotifications) "Notifications enabled" else "Allow notifications",
            granted = hasNotifications,
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else hasNotifications = true
            }
        )

        PermissionPrimerCard(
            icon = Icons.Filled.CameraAlt,
            iconBg = SkyLow,
            iconTint = White,
            title = "Camera access",
            description = "Capture issue photos directly inside PinPoint (EXIF & GPS metadata are stripped on upload).",
            actionLabel = if (hasCamera) "Camera enabled" else "Allow camera",
            granted = hasCamera,
            onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) }
        )

        Text(
            text = "Your exact location is shared only when you choose to include it in a report.",
            style = MaterialTheme.typography.bodySmall,
            color = MutedText
        )

        Spacer(Modifier.weight(1f, fill = false))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = {
                    val missing = buildList {
                        if (!hasLocation) {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                        if (!hasNotifications && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (!hasCamera) add(Manifest.permission.CAMERA)
                    }
                    if (missing.isEmpty()) onContinue() else allLauncher.launch(missing.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = White)
            ) {
                Text(
                    text = if (hasLocation && hasNotifications && hasCamera) "Continue" else "Allow & continue",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            TextButton(onClick = onSkip) {
                Text("Maybe later", color = DeepViolet, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PermissionPrimerCard(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    description: String,
    actionLabel: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = PinPointSurface
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = Black, fontWeight = FontWeight.Bold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MutedText)
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(11.dp)),
                shape = RoundedCornerShape(11.dp),
                color = if (granted) GreenTint else VioletTint,
                onClick = { if (!granted) onClick() }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (granted) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = ResolvedGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (granted) ResolvedGreen else DeepViolet,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
