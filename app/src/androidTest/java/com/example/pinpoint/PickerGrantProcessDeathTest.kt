package com.example.pinpoint

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import com.example.pinpoint.data.PhotoCompressor
import com.example.pinpoint.data.SupabaseBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Two instrumentation runs = two processes. Phase "pick" selects a synthetic image through the REAL system picker on the
 *  report form (loopback backend, isolated session, no hosted write) and records the picker URI. Phase "read" runs in a
 *  new process (the earlier process and task are gone) and reports whether that URI is still readable and compressible.
 *  The result is an observation of the platform's grant lifetime, reported through instrumentation status. */
@RunWith(AndroidJUnit4::class)
class PickerGrantProcessDeathTest {
    @get:Rule(order = 0) val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("phase")
    private val prefs get() = context.getSharedPreferences("picker_grant_probe", 0)
    private fun status(vararg pairs: Pair<String, String>) = instrumentation.sendStatus(0, Bundle().apply { pairs.forEach { putString(it.first, it.second) } })

    @Test fun pick() {
        assumeTrue(phase == "pick")
        val server = FakeSupabaseServer()
        val backend = SupabaseBackend.installForTesting(context, server.url, "sb_publishable_grant_probe")
        runBlocking { backend.auth.signIn("grant@probe.test", "x") }
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(200, 90, 30)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray(); bitmap.recycle()
        val source = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "pp-grant-probe.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PinPointTest"); put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis()) }))
        context.contentResolver.openOutputStream(source)!!.use { it.write(bytes) }
        prefs.edit().putString("source", source.toString()).commit()
        try {
            val device = UiDevice.getInstance(instrumentation)
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            compose.waitUntil(30000) { compose.onAllNodesWithText("Quick Report").fetchSemanticsNodes(false).isNotEmpty() }
            compose.onNodeWithText("Quick Report").performScrollTo().performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("Add Photo").fetchSemanticsNodes(false).isNotEmpty() }
            compose.onNodeWithText("Add Photo").performScrollTo().performClick()
            val photo = checkNotNull(device.wait(Until.findObject(By.descStartsWith("Photo taken")), 20000))
            val pkg = photo.applicationPackage; photo.click()
            device.wait(Until.findObject(By.pkg(pkg).text(java.util.regex.Pattern.compile("(?i)add|done|select|ok|confirm.*"))), 3000)?.let { if (device.currentPackageName == pkg) it.click() }
            compose.waitUntil(30000) { compose.onAllNodesWithText("Photo selected successfully").fetchSemanticsNodes(false).isNotEmpty() }
            // The returned URI is held only in Compose state; read it from the system's URI-grant table instead.
            val dump = listOf("dumpsys uri-grants", "dumpsys activity permissions").joinToString("\n") {
                val d = instrumentation.uiAutomation.executeShellCommand(it)
                java.io.FileInputStream(d.fileDescriptor).bufferedReader().readText().also { d.close() }
            }
            val grant = Regex("""content://media/picker[^\s\]}\)]+""").findAll(dump).map { it.value }.toList().lastOrNull()
            prefs.edit().putString("picker", grant).commit()
            status("picked" to "true", "picker_uri_found" to (grant != null).toString())
        } finally { server.close() }
        // Deliberately keep the source in MediaStore: phase "read" removes it. Only the (picker) grant is under test.
    }

    @Test fun read() {
        assumeTrue(phase == "read")
        val source = Uri.parse(requireNotNull(prefs.getString("source", null)))
        val picker = prefs.getString("picker", null)
        val outcome = if (picker == null) "no_picker_uri_recorded" else try {
            val jpeg = runBlocking { PhotoCompressor.jpeg(context, Uri.parse(picker)) }; "readable_and_compressible bytes=${jpeg.size}"
        } catch (error: Exception) { "not_readable ${error.javaClass.simpleName}" }
        val ownMediaStoreFile = try {
            val jpeg = runBlocking { PhotoCompressor.jpeg(context, source) }; "readable_and_compressible bytes=${jpeg.size}"
        } catch (error: Exception) { "not_readable ${error.javaClass.simpleName}" }
        runCatching { context.contentResolver.delete(source, null, null) }
        status("outcome" to outcome, "own_mediastore_file_for_comparison" to ownMediaStoreFile)
    }
}
