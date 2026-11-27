package com.example.pinpoint

import android.Manifest
import android.content.Intent
import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.pinpoint.data.SupabaseBackend
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Signs the real app in or out of the live backend through the real UI so a host script can exercise real FCM.
 *  Phases: signin (credentials via instrumentation args, never printed), signout, and destroy. */
@RunWith(AndroidJUnit4::class)
class PushRecipientSetupTest {
    @get:Rule(order = 0) val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun waitText(text: String, millis: Long = 45000) = compose.waitUntil(millis) { compose.onAllNodesWithText(text).fetchSemanticsNodes(false).isNotEmpty() }

    @Test fun run() {
        val phase = args.getString("phase", "signin")
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val signedIn = SupabaseBackend.create(context)?.auth?.currentUser != null
        when (phase) {
            "clear-tray" -> {
                val manager = context.getSystemService(NotificationManager::class.java)
                val deadline = System.currentTimeMillis() + 5000
                do {
                    manager.cancelAll()
                    Thread.sleep(150)
                } while (manager.activeNotifications.isNotEmpty() && System.currentTimeMillis() < deadline)
                check(manager.activeNotifications.isEmpty()) { "Owned notification cancellation did not complete" }
            }
            "destroy" -> {
                instrumentation.runOnMainSync {
                    Stage.values().filter { it != Stage.DESTROYED }.flatMap {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).toList()
                    }.distinct().forEach { it.finish() }
                }
                instrumentation.waitForIdleSync()
                var alive = true
                val deadline = System.currentTimeMillis() + 10000
                while (alive && System.currentTimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        alive = Stage.values().filter { it != Stage.DESTROYED }.any {
                            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).isNotEmpty()
                        }
                    }
                    if (alive) Thread.sleep(100)
                }
                check(!alive) { "Activity destruction was not observed" }
            }
            "signin" -> {
                if (signedIn) { waitText("Quick Report"); compose.onNodeWithText("Profile").performClick(); compose.onNodeWithText("Sign out").performClick(); compose.onNodeWithText("Log out").performClick() }
                compose.waitUntil(45000) {
                    compose.onAllNodesWithText("Sign in to your community").fetchSemanticsNodes(false).isNotEmpty() ||
                        compose.onAllNodesWithText("Skip").fetchSemanticsNodes(false).isNotEmpty()
                }
                if (compose.onAllNodesWithText("Skip").fetchSemanticsNodes(false).isNotEmpty()) {
                    compose.onNodeWithText("Skip").performClick()
                }
                waitText("Sign in to your community")
                compose.onNodeWithText("Email").performTextReplacement(requireNotNull(args.getString("email")))
                compose.onNodeWithText("Password").performTextReplacement(requireNotNull(args.getString("password")))
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.waitForIdle()
                compose.onNodeWithText("Sign in").performScrollTo().assertIsEnabled().performClick()
                waitText("Quick Report", 60000)
            }
            "signout" -> {
                if (signedIn) { waitText("Quick Report"); compose.onNodeWithText("Profile").performClick(); compose.onNodeWithText("Sign out").performClick(); compose.onNodeWithText("Log out").performClick() }
                waitText("Sign in to your community")
            }
            else -> error("Unknown phase")
        }
        // Leave the activity alone: the host script continues with the app in this state.
    }
}
