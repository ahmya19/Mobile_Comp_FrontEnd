package com.example.pinpoint

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pinpoint.screens.OnboardingScreen
import com.example.pinpoint.screens.OnboardingStore
import com.example.pinpoint.screens.PermissionPrimerScreen
import com.example.pinpoint.screens.PinPointSplashScreen
import com.example.pinpoint.screens.ReportSuccessScreen
import com.example.pinpoint.ui.theme.PinPointTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic UI tests for CP-54 Splash (1.1), 3-slide Onboarding (1.2), Permission Primer (1.7), and confirmation animation. */
@RunWith(AndroidJUnit4::class)
class OnboardingAndMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun restorePrefs() {
        // Leave onboarding marked completed so subsequent physical-device runs preserve returning-user behavior.
        OnboardingStore.markOnboardingDone(compose.activity)
    }

    @Test fun splashScreenRendersBrandWordmarkAndLoadingCopy() {
        compose.setContent { PinPointTheme { PinPointSplashScreen() } }
        compose.onNodeWithText("PinPoint").assertIsDisplayed()
        compose.onNodeWithText("Report. Track. Improve your community.").assertIsDisplayed()
        compose.onNodeWithText("Loading your community...").assertIsDisplayed()
    }

    @Test fun onboardingCarouselAdvancesThroughAllThreeSlidesIntoPermissionPrimer() {
        var step by mutableStateOf("onboarding")
        compose.setContent {
            PinPointTheme {
                when (step) {
                    "onboarding" -> OnboardingScreen(
                        onGetStarted = { step = "primer" },
                        onAlreadyHaveAccount = { step = "login" },
                        onSkip = { step = "login" }
                    )
                    "primer" -> PermissionPrimerScreen(
                        onContinue = { step = "login" },
                        onSkip = { step = "login" }
                    )
                    else -> ReportSuccessScreen(onViewReportsClick = {}, onHomeClick = {}, reportId = "12345678-1234-1234-1234-1234567890ab")
                }
            }
        }
        compose.onNodeWithText("1 OF 3").assertIsDisplayed()
        compose.onNodeWithText("See a problem?\nPin it.").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("2 OF 3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Works even\nwithout signal.").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("3 OF 3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Track & upvote\ntogether.").assertIsDisplayed()
        compose.onNodeWithText("Get started").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Let PinPoint work for your barangay").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Location access").assertIsDisplayed()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithText("Camera access").assertIsDisplayed()
        compose.onNodeWithText("Maybe later").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Report submitted!").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun onboardingStorePersistsCompletionFlag() {
        val ctx = compose.activity
        OnboardingStore.reset(ctx)
        assertFalse(OnboardingStore.isOnboardingDone(ctx))
        OnboardingStore.markOnboardingDone(ctx)
        assertTrue(OnboardingStore.isOnboardingDone(ctx))
        OnboardingStore.markPrimerDone(ctx)
        assertTrue(OnboardingStore.isPrimerDone(ctx))
    }
}
