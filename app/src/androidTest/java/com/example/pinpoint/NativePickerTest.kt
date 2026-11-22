package com.example.pinpoint

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.example.pinpoint.data.SupabaseBackend
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Drives the REAL Android system picker (no stubbed activity result) against a loopback fake server. */
@RunWith(AndroidJUnit4::class)
class NativePickerTest {
    @get:Rule(order = 0) val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private lateinit var server: FakeSupabaseServer
    private lateinit var backend: SupabaseBackend
    private val inserted = mutableListOf<Uri>()

    @Before fun setup() {
        server = FakeSupabaseServer()
        backend = SupabaseBackend.installForTesting(context, server.url, "sb_publishable_picker_test")
        runBlocking { backend.auth.signIn("picker@native.test", "not-a-real-password") }
        // Scheduling must not run real workers in parallel with the explicit sync call below.
        val factory = object : androidx.work.WorkerFactory() {
            override fun createWorker(appContext: Context, name: String, parameters: androidx.work.WorkerParameters) =
                SchedulingProbeWorker(appContext, parameters)
        }
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).setWorkerFactory(factory).build())
        context.getDatabasePath("pinpoint_queue.db").parentFile?.mkdirs()
    }

    @After fun cleanup() {
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        runCatching { runBlocking { backend.auth.signOut() } }
        queue().writableDatabase.delete("queue", "project=?", arrayOf(server.url))
        androidx.work.testing.WorkManagerTestInitHelper.closeWorkDatabase(); androidx.work.impl.WorkManagerImpl.setDelegate(null)
        SupabaseBackend.resetForTesting(); server.close()
    }

    private fun insertJpeg(name: String, width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(90, 70, 180)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray(); bitmap.recycle()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PinPointTest")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        inserted += uri
        return uri
    }

    private fun launch(): android.app.Activity {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitText("Quick Report"); compose.onNodeWithText("Quick Report").performScrollTo().performClick()
        waitText("Select a Category")
        return activity
    }
    private fun waitText(text: String, millis: Long = 30000) = compose.waitUntil(millis) { compose.onAllNodesWithText(text).fetchSemanticsNodes(false).isNotEmpty() }
    private fun enter(label: String, value: String) { compose.onNodeWithText(label).performScrollTo().performTextInput(value) }
    private fun fillForm(description: String) {
        compose.onNodeWithText("Select a Category").performClick(); compose.onNodeWithText("Water Interruption").performClick()
        enter("Description", description); enter("Location", "Picker test location"); enter("Barangay", "Alangilan")
    }
    /** Opens the real Android photo picker and taps the first (most recent) photo. Returns the picker package. */
    private fun pickFirstPhoto(): String {
        compose.onNodeWithText("Add Photo").performScrollTo().performClick()
        val photo = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.descStartsWith("Photo taken")), 15000)
        checkNotNull(photo) { "System picker showed no photo" }
        val pkg = photo.applicationPackage
        photo.click()
        return pkg
    }
    private fun queue() = com.example.pinpoint.data.ReportQueue.get(context)
    private fun rows() = queue().items(server.userId("picker@native.test"), server.url)
    private fun resetQueue() { queue().writableDatabase.delete("queue", "project=?", arrayOf(server.url)) }

    @Test fun nativePickerPhotoSurvivesRecreationAndSourceLossThenCommitsOnceWithTheCompressedObject() {
        resetQueue()
        val source = insertJpeg("pp-picker-${UUID.randomUUID().toString().take(8)}.jpg", 3000, 2000)
        val activity = launch()
        val pkg = pickFirstPhoto()
        assertTrue("expected the system photo picker, got $pkg", pkg.contains("photopicker") || pkg.contains("documentsui"))
        waitText("Photo selected successfully")
        // Activity recreation: the selection is saved UI state and must remain usable.
        instrumentation.runOnMainSync { activity.recreate() }
        waitText("Photo selected successfully")
        fillForm("Native picker ${UUID.randomUUID()} pothole")
        compose.onNode(isToggleable()).performScrollTo().performClick() // compressed path only
        compose.onNodeWithText("Submit Report").performScrollTo().performClick()
        waitText("View My Reports")
        val row = rows().single()
        val saved = java.io.File(requireNotNull(row.photoFile))
        assertTrue("the queue must hold its own copy", saved.length() in 1..256000)
        // The picker source disappears after selection; the queued copy must still be uploadable.
        context.contentResolver.delete(source, null, null); inserted.remove(source)
        val bytes = saved.readBytes()
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
        assertTrue("${bounds.outWidth}x${bounds.outHeight}", maxOf(bounds.outWidth, bounds.outHeight) <= 1280 && bounds.outWidth > bounds.outHeight)
        // Restore connectivity: run the real sync worker against the fake server.
        val result = runBlocking { androidx.work.testing.TestListenableWorkerBuilder<com.example.pinpoint.data.ReportSyncWorker>(context)
            .setInputData(androidx.work.workDataOf("owner" to server.userId("picker@native.test"))).build().doWork() }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals(listOf(server.userId("picker@native.test") to row.id), server.creations.toList())
        val object_ = server.photos["${server.userId("picker@native.test")}/${row.id}/photo.jpg"]
        assertNotNull("private object missing", object_)
        assertEquals("uploaded bytes must equal the queued compressed copy", sha(bytes), sha(object_!!))
        assertEquals("Synced", rows().single().state)
    }

    @Test fun cancellingThePickerLeavesNoPhotoAndNoQueuedReport() {
        resetQueue()
        insertJpeg("pp-cancel-${UUID.randomUUID().toString().take(8)}.jpg", 200, 200)
        launch()
        compose.onNodeWithText("Add Photo").performScrollTo().performClick()
        checkNotNull(device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.descStartsWith("Photo taken")), 15000))
        device.pressBack()
        waitText("Create a Report")
        compose.onNodeWithText("Photo selected successfully").assertDoesNotExist()
        compose.onNodeWithText("Add Photo").assertExists()
        assertTrue(rows().isEmpty())
    }

    @Test fun sourceDeletedBeforeSubmitShowsAnErrorAndLeavesNoBrokenQueueRow() {
        resetQueue()
        val before = java.io.File(context.filesDir, "queued_photos").list().orEmpty().toSet()
        val source = insertJpeg("pp-gone-${UUID.randomUUID().toString().take(8)}.jpg", 300, 200)
        launch(); pickFirstPhoto(); waitText("Photo selected successfully")
        context.contentResolver.delete(source, null, null); inserted.remove(source)
        fillForm("Vanished source ${UUID.randomUUID()}")
        compose.onNodeWithText("Submit Report").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasText("Cannot", substring = true) or hasText("photo", substring = true, ignoreCase = true) and !hasText("Photo selected successfully")).fetchSemanticsNodes(false).isNotEmpty() }
        assertTrue("no broken queue row", rows().isEmpty())
        val leftovers = java.io.File(context.filesDir, "queued_photos").list().orEmpty().toSet() - before
        assertTrue("leftover files: $leftovers", leftovers.isEmpty())
        assertEquals(0, server.creations.size)
    }

    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
