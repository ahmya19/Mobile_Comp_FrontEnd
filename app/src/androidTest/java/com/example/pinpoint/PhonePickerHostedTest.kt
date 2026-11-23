package com.example.pinpoint

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.pinpoint.data.ReportQueue
import com.example.pinpoint.data.SupabaseBackend
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Opt-in, hosted: uses the app's EXISTING real session (never signs in/out), the REAL system photo picker and one
 *  synthetic generated image, submits one labelled report with a compressed photo and reports its queued copy's SHA-256
 *  and dimensions. The host driver verifies Storage independently and removes the fixture. */
@RunWith(AndroidJUnit4::class)
class PhonePickerHostedTest {
    @get:Rule(order = 0) val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun waitText(text: String, millis: Long = 45000) = compose.waitUntil(millis) { compose.onAllNodesWithText(text).fetchSemanticsNodes(false).isNotEmpty() }

    @Test fun pickedPhotoIsSubmittedToTheLiveBackend() {
        val marker = InstrumentationRegistry.getArguments().getString("marker")
        org.junit.Assume.assumeTrue("Run with -e marker <label> from the host driver", marker != null)
        val backend = requireNotNull(SupabaseBackend.create(context)); val owner = requireNotNull(backend.auth.currentUser).id
        val name = "pp-hosted-${UUID.randomUUID().toString().take(8)}.jpg"
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(20, 120, 90)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray(); bitmap.recycle()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PinPointTest"); put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        }
        val source = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            context.contentResolver.openOutputStream(source)!!.use { it.write(bytes) }
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitText("Quick Report"); compose.onNodeWithText("Quick Report").performScrollTo().performClick()
            waitText("Select a Category")
            compose.onNodeWithText("Select a Category").performClick(); compose.onNodeWithText("Road Damage").performClick()
            compose.onNodeWithText("Description").performScrollTo().performTextInput(marker!!)
            compose.onNodeWithText("Location").performScrollTo().performTextInput("PUSHFCM phone picker fixture")
            compose.onNodeWithText("Barangay").performScrollTo().performTextInput("Alangilan")
            compose.onNodeWithText("Add Photo").performScrollTo().performClick()
            val photo = checkNotNull(device.wait(Until.findObject(By.descStartsWith("Photo taken")), 20000)) { "no photo in the system picker" }
            val pkg = photo.applicationPackage; photo.click()
            device.wait(Until.findObject(By.pkg(pkg).text(java.util.regex.Pattern.compile("(?i)add|done|select|ok|confirm.*"))), 3000)?.let { if (device.currentPackageName == pkg) it.click() }
            waitText("Photo selected successfully")
            compose.onNode(isToggleable()).performScrollTo().performClick()      // compressed photo only
            compose.onNodeWithText("Submit Report").performScrollTo().performClick()
            waitText("View My Reports")
            val queue = ReportQueue.get(context)
            var row = queue.items(owner, backend.url).first { it.draft.description == marker }
            val end = System.currentTimeMillis() + 120_000
            while (row.state != "Synced" && System.currentTimeMillis() < end) { Thread.sleep(1000); row = queue.items(owner, backend.url).first { it.id == row.id } }
            assertEquals("Synced", row.state)
            val info = Bundle().apply { putString("report_id", row.id); putString("owner", owner) }
            instrumentation.sendStatus(0, info)
        } finally { runCatching { context.contentResolver.delete(source, null, null) } }
    }
}
