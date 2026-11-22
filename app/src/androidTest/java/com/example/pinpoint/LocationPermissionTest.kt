package com.example.pinpoint

import android.Manifest
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pinpoint.screens.ReportScreen
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real system permission dialog on the emulator, answered through the accessibility API (no extra library).
 * `pm revoke` would kill this same process, so the HOST sets the permission state before each run and passes the
 * scenario: `-e locationScenario deny` or `-e locationScenario grant` (with `adb emu geo fix` for the grant case).
 * The location is a SIMULATED emulator fix; it says nothing about real GPS accuracy.
 */
@RunWith(AndroidJUnit4::class)
class LocationPermissionTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scenario = InstrumentationRegistry.getArguments().getString("locationScenario")

    private fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun texts(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        if (node == null) return out
        out += node
        for (i in 0 until node.childCount) texts(node.getChild(i), out)
        return out
    }

    /** Click the first clickable node whose text matches in the active window (the permission dialog) until the timeout. */
    private fun clickDialog(timeoutMs: Long, matcher: (String) -> Boolean): String {
        val automation = instrumentation.uiAutomation
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (node in texts(automation.rootInActiveWindow)) {
                val label = node.text?.toString() ?: continue
                if (!matcher(label)) continue
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return label
            }
            Thread.sleep(250)
        }
        fail("No permission dialog button matched within ${timeoutMs} ms")
        return ""
    }

    private fun showForm() = compose.setContent { ReportScreen(onBackClick = {}, busy = false, onSubmitClick = { _, _, _ -> }) }

    @Test fun declinedPermissionKeepsTheFormUsableAndOffersTheMapPin() {
        assumeTrue("Run with -e locationScenario deny and the permission revoked by the host", scenario == "deny")
        assertFalse("the host must revoke location first", granted())
        showForm()
        compose.onNodeWithText("Use my current location").performScrollTo().performClick()
        val label = clickDialog(15_000) { it.contains("allow", ignoreCase = true) && it.contains("don", ignoreCase = true) }
        println("dialog button used: $label")
        compose.waitUntil(10_000) { compose.onAllNodesWithTextSafe("Location permission was declined. You can still choose a map pin.") }
        assertFalse(granted())
        // The fallback named in the message must still work: the map pin control opens the map.
        compose.onNodeWithText("Choose or adjust map pin").performScrollTo().performClick()
        compose.onNodeWithText("Press and hold the map to set the issue location.").assertExists()
    }

    @Test fun grantedPermissionFillsBothCoordinateFieldsFromTheSimulatedFix() {
        assumeTrue("Run with -e locationScenario grant, permission revoked and an emulator geo fix set by the host", scenario == "grant")
        assertFalse("the host must revoke location first so the dialog appears", granted())
        showForm()
        compose.onNodeWithText("Use my current location").performScrollTo().performClick()
        clickDialog(15_000) { it.contains("while using", ignoreCase = true) || it.equals("Allow", ignoreCase = true) }
        compose.waitUntil(40_000) { compose.onAllNodesWithTextSafe("GPS accuracy: about", substring = true) }
        assertTrue(granted())
        // The host set a simulated fix near 13.7565, 121.0583; both fields must hold it (not only the message).
        assertTrue("latitude field", compose.onAllNodesWithTextSafe("13.75", substring = true))
        assertTrue("longitude field", compose.onAllNodesWithTextSafe("121.05", substring = true))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafe(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
}
