package com.example.pinpoint

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Process
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
import com.example.pinpoint.data.ReportDraft
import com.example.pinpoint.data.ReportQueue
import com.example.pinpoint.data.SupabaseBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Phase driver for the HOST-coordinated process-death harness (backend/scripts/process_death_harness.py). It is inert
 * unless `-e phase <name>` is passed. Android kills this process (and the instrumentation) when the host sends SIGKILL, so
 * every checkpoint is written to an app-private receipt (`shared_prefs/lifecycle_receipt.xml`, readable by the host via
 * run-as in debug builds) BEFORE the phase waits to be killed. Targets a persisted loopback test backend and an isolated test
 * session only: the user's real session and the hosted project are never used.
 *
 * Phases: setup | enqueue | ui-pick | switch | probe | cleanup.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleDriverTest {
    @get:Rule(order = 0) val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val receipt get() = context.getSharedPreferences("lifecycle_receipt", 0)
    private fun note(vararg pairs: Pair<String, String>) = receipt.edit().apply { pairs.forEach { putString(it.first, it.second) } }.commit()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun waitToBeKilled(seconds: Int = 180) { repeat(seconds) { Thread.sleep(1000) } }
    private fun backend() = requireNotNull(SupabaseBackend.create(context)) { "no backend" }

    private fun jpegFile(name: String, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(width % 255, height % 255, 120)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray(); bitmap.recycle()
        return File(context.cacheDir, name).also { it.writeBytes(bytes) }
    }

    @Test fun run() {
        val phase = args.getString("phase"); assumeTrue("inert without -e phase", phase != null)
        when (phase) {
            "setup" -> {
                SupabaseBackend.installPersistedForTesting(context, requireNotNull(args.getString("url")), "sb_publishable_lifecycle")
                runBlocking { SupabaseBackend.create(context)!!.auth.signIn(requireNotNull(args.getString("email")), "not-a-real-password") }
                note("setup" to "done", "user" to requireNotNull(SupabaseBackend.create(context)!!.auth.currentUser).id)
            }
            "enqueue" -> {
                val backend = backend(); val owner = requireNotNull(backend.auth.currentUser).id
                val id = requireNotNull(args.getString("id")); val queue = ReportQueue.get(context)
                val file = jpegFile("lifecycle-$id.jpg", args.getString("width", "900").toInt(), 700)
                val expected = runBlocking { PhotoCompressor.jpeg(context, Uri.fromFile(file)) }
                note("phase" to "enqueue", "id" to id, "owner" to owner, "expected_sha" to sha(expected), "expected_bytes" to expected.size.toString(), "acked" to "no")
                val keepOriginal = args.getString("original", "no") == "yes"
                runBlocking { queue.enqueue(backend, id, ReportDraft("Road Damage", "Lifecycle ${args.getString("tag", "run")} pothole", "Main Road", "Alangilan"), Uri.fromFile(file), keepOriginal) }
                // A resumable-upload session as the TUS transport would have saved it, to prove it survives process death.
                args.getString("session")?.let { queue.saveUploadSession(id, it) }
                // The crash window between "acknowledged: Synced" and deleting the local compressed copy.
                if (args.getString("crashwindow") == "yes") queue.setState(id, "Synced")
                // The acknowledgement the user would see has happened: from here on the report must survive a kill.
                note("acked" to "yes", "pid" to Process.myPid().toString())
                if (args.getString("wait", "yes") == "yes") waitToBeKilled()
            }
            "switch" -> {
                runBlocking { backend().auth.signIn(requireNotNull(args.getString("email")), "not-a-real-password") }
                note("switched_to" to requireNotNull(backend().auth.currentUser).id, "pid" to Process.myPid().toString())
                if (args.getString("wait", "yes") == "yes") waitToBeKilled()
            }
            "ui-pick" -> uiPick()
            "probe" -> {
                val backend = backend(); val queue = ReportQueue.get(context)
                val owners = (args.getString("owners") ?: "").split(',').filter { it.isNotBlank() }
                val rows = owners.flatMap { o -> queue.items(o, backend.url).map { "${it.id}|${it.ownerId}|${it.state}|${it.photoFile != null && File(it.photoFile!!).isFile}|${it.error ?: ""}" } }
                note("probe" to rows.joinToString(";;"), "pid" to Process.myPid().toString())
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("rows", rows.joinToString(";;")) })
            }
            "cleanup" -> {
                val backend = SupabaseBackend.create(context)
                val queue = ReportQueue.get(context)
                if (backend != null) {
                    queue.writableDatabase.query("queue", arrayOf("photo_file", "original_file"), "project=?", arrayOf(backend.url), null, null, null).use { c ->
                        while (c.moveToNext()) { for (i in 0..1) c.getString(i)?.let { File(it).delete() } }
                    }
                    queue.writableDatabase.delete("queue", "project=?", arrayOf(backend.url))
                    runCatching { runBlocking { backend.auth.signOut() } }
                }
                runCatching { context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Images.Media.DISPLAY_NAME} like ?", arrayOf("pp-lifecycle-%")) }
                androidx.work.WorkManager.getInstance(context).cancelAllWork()
                SupabaseBackend.clearPersistedTesting(context)
                context.getSharedPreferences("pinpoint_test_session", 0).edit().clear().commit()
                note("cleanup" to "done")
            }
            else -> error("unknown phase")
        }
    }

    /** Real UI: form fields + REAL system photo picker, then park until killed (the host adds the OS-level kill). */
    private fun uiPick() {
        val device = UiDevice.getInstance(instrumentation)
        val name = "pp-lifecycle-${System.nanoTime()}.jpg"
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(30, 90, 200)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray(); bitmap.recycle()
        val source = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PinPointTest"); put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis()) }))
        context.contentResolver.openOutputStream(source)!!.use { it.write(bytes) }
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun wait(text: String, ms: Long = 30000) = compose.waitUntil(ms) { compose.onAllNodesWithText(text).fetchSemanticsNodes(false).isNotEmpty() }
        wait("Quick Report"); compose.onNodeWithText("Quick Report").performScrollTo().performClick(); wait("Select a Category")
        compose.onNodeWithText("Select a Category").performClick(); compose.onNodeWithText("Road Damage").performClick()
        compose.onNodeWithText("Description").performScrollTo().performTextInput("Lifecycle ${args.getString("tag", "ui")} pothole")
        compose.onNodeWithText("Location").performScrollTo().performTextInput("Main Road"); compose.onNodeWithText("Barangay").performScrollTo().performTextInput("Alangilan")
        compose.onNodeWithText("Add Photo").performScrollTo().performClick()
        val photo = checkNotNull(device.wait(Until.findObject(By.descStartsWith("Photo taken")), 20000)) { "no photo in the system picker" }
        val pkg = photo.applicationPackage; photo.click()
        device.wait(Until.findObject(By.pkg(pkg).text(java.util.regex.Pattern.compile("(?i)add|done|select|ok|confirm.*"))), 3000)?.let { if (device.currentPackageName == pkg) it.click() }
        wait("Photo selected successfully")
        compose.onNode(isToggleable()).performScrollTo().performClick()      // compressed photo only
        note("phase" to "ui-pick", "ready" to "yes", "pid" to Process.myPid().toString(), "source" to source.toString())
        waitToBeKilled()
    }
}
