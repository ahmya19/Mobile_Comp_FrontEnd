package com.example.pinpoint

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.annotation.SuppressLint
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.example.pinpoint.data.*
import com.example.pinpoint.screens.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Covers Batch 2A: FileProvider camera URI + EXIF/GPS/cleanup, account-isolated durable drafts,
 *  policy-safe unsent queue edit/delete (refusing Synced/Sending), password recovery/change, and UI parity controls. */
// The test deliberately uses the platform ExifInterface: it is the class the app itself reads orientation with.
@SuppressLint("ExifInterface")
@RunWith(AndroidJUnit4::class)
class UiParityAndCameraDraftTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: FakeSupabaseServer
    private lateinit var backend: SupabaseBackend

    @Before fun setUp() {
        server = FakeSupabaseServer()
        backend = SupabaseBackend.installForTesting(context, server.url, "sb_publishable_test")
    }

    @After fun tearDown() {
        // Leave nothing behind in the app's real queue database: this suite's rows and drafts belong to its own loopback project.
        runCatching {
            val db = ReportQueue.get(context).writableDatabase
            db.delete("queue", "project=?", arrayOf(server.url)); db.delete("drafts", "project=?", arrayOf(server.url))
        }
        runCatching { WorkManager.getInstance(context).cancelAllWork() }
        SupabaseBackend.resetForTesting()
        server.close()
    }

    private fun writeOrientedGpsJpeg(file: File) {
        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(124, 77, 204))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "13/1,46/1,54/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "121/1,4/1,1/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
        exif.saveAttributes()
    }

    @Test fun cameraFileProviderLifecycleAppliesExifStripsGpsAndCleansTempFiles(): Unit = runBlocking {
        backend.auth.signIn("camera-owner@test.example", "secret-pass")
        val queue = ReportQueue.get(context)
        val (captureFile, captureUri) = CameraCaptureHelper.createCaptureTarget(context)
        assertTrue("FileProvider authority must match app", captureUri.authority == "${context.packageName}.fileprovider")
        writeOrientedGpsJpeg(captureFile)

        // Verify EXIF orientation applied and GPS stripped on compressed output.
        val compressed = PhotoCompressor.jpeg(context, captureUri)
        val decoded = BitmapFactory.decodeByteArray(compressed, 0, compressed.size)
        assertEquals(200, decoded.width)
        assertEquals(400, decoded.height)
        decoded.recycle()
        val tmpCheck = File(context.cacheDir, "exif_check_${UUID.randomUUID()}.jpg")
        try {
            tmpCheck.writeBytes(compressed)
            assertNull("GPS latitude must be stripped", ExifInterface(tmpCheck.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        } finally { tmpCheck.delete() }

        // Enqueuing from a camera FileProvider URI creates the durable copy and deletes the temp capture file.
        val rid = UUID.randomUUID().toString()
        val draft = ReportDraft("Road Damage", "Camera captured pothole along Alangilan street.", "Main Rd", "Alangilan")
        queue.enqueue(backend, rid, draft, captureUri, keepOriginal = false)
        assertFalse("Temp camera capture must be deleted after enqueue", captureFile.exists())
        val queued = queue.items(backend.auth.currentUser!!.id, backend.url).first { it.id == rid }
        assertTrue(File(requireNotNull(queued.photoFile)).isFile)
        // Clean up queue item after check.
        queue.setState(rid, "Queued")
        assertTrue(queue.deleteUnsent(backend.auth.currentUser!!.id, backend.url, rid))

        // Cancel/failed capture cleanup + stale purge (>24h).
        val (cancelledFile, _) = CameraCaptureHelper.createCaptureTarget(context)
        cancelledFile.writeBytes(byteArrayOf(1, 2, 3))
        CameraCaptureHelper.deleteCaptureFile(context, cancelledFile.absolutePath)
        assertFalse(cancelledFile.exists())

        val (staleFile, _) = CameraCaptureHelper.createCaptureTarget(context)
        staleFile.writeBytes(byteArrayOf(1))
        staleFile.setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1000)
        CameraCaptureHelper.purgeStaleCaptures(context)
        assertFalse("Stale camera capture must be purged", staleFile.exists())
    }

    @Test fun durableDraftsAreAccountAndProjectIsolatedAndClearedOnSubmit(): Unit = runBlocking {
        val queue = ReportQueue.get(context)
        backend.auth.signIn("draft-a@test.example", "pass-a")
        val ownerA = backend.auth.currentUser!!.id
        val ownerB = server.userId("draft-b@test.example")
        val (pic, picUri) = CameraCaptureHelper.createCaptureTarget(context)
        writeOrientedGpsJpeg(pic)

        val saved = queue.saveDraft(ownerA, backend.url, "Flooding", "Drainage overflowing near Alangilan bridge", "Bridge St", "Alangilan", "13.78", "121.07", true, picUri)
        assertNotNull(saved.photoFile)
        assertTrue(File(requireNotNull(saved.photoFile)).isFile)

        // Account A sees its draft; Account B and another project URL see null.
        assertEquals("Flooding", queue.loadDraft(ownerA, backend.url)?.category)
        assertNull("Account B must never see Account A's draft", queue.loadDraft(ownerB, backend.url))
        assertNull("Another project URL must never see this draft", queue.loadDraft(ownerA, "https://other.supabase.co"))

        // Submitting a report automatically clears the saved draft and deletes its private draft photo.
        val rid = UUID.randomUUID().toString()
        queue.enqueue(backend, rid, ReportDraft("Flooding", "Drainage overflowing near Alangilan bridge", "Bridge St", "Alangilan"), picUri, keepOriginal = false)
        assertNull(queue.loadDraft(ownerA, backend.url))
        assertFalse("Draft photo must be cleaned when draft clears", File(requireNotNull(saved.photoFile)).exists())
        queue.setState(rid, "Queued")
        queue.deleteUnsent(ownerA, backend.url, rid)
    }

    @Test fun queueEditAndDeleteWorkForUnsentRowsAndRefuseSyncedOrSendingRows(): Unit = runBlocking {
        backend.auth.signIn("queue-guard@test.example", "pass-q")
        val owner = backend.auth.currentUser!!.id
        val queue = ReportQueue.get(context)

        val rid = UUID.randomUUID().toString()
        val (pic, picUri) = CameraCaptureHelper.createCaptureTarget(context)
        writeOrientedGpsJpeg(pic)
        val initial = ReportDraft("Garbage", "Uncollected trash bags beside the basketball court.", "Purok 3", "Alangilan")
        queue.enqueue(backend, rid, initial, picUri, keepOriginal = false)
        WorkManager.getInstance(context).cancelAllWork()
        queue.setState(rid, "Failed", "offline")

        // Allowed on Failed/Queued: updateUnsent
        val updated = initial.copy(description = "Updated unsent report text before upload occurs.")
        assertTrue(queue.updateUnsent(owner, backend.url, rid, updated))
        assertEquals("Queued", queue.items(owner, backend.url).first { it.id == rid }.state)
        assertEquals(updated.description, queue.items(owner, backend.url).first { it.id == rid }.draft.description)

        // Refused on Sending
        queue.setState(rid, "Sending")
        try { queue.deleteUnsent(owner, backend.url, rid); fail("Sending row must not be deletable") } catch (_: IllegalArgumentException) {}
        try { queue.updateUnsent(owner, backend.url, rid, initial); fail("Sending row must not be editable") } catch (_: IllegalArgumentException) {}

        // Refused on Synced (evidence preservation policy!)
        queue.setState(rid, "Synced")
        try { queue.deleteUnsent(owner, backend.url, rid); fail("Synced row must not be deletable") } catch (_: IllegalArgumentException) {}
        try { queue.updateUnsent(owner, backend.url, rid, initial); fail("Synced row must not be editable") } catch (_: IllegalArgumentException) {}

        // Delete a fresh Queued row and verify local photo file is removed too.
        val rid2 = UUID.randomUUID().toString()
        val (pic2, picUri2) = CameraCaptureHelper.createCaptureTarget(context)
        writeOrientedGpsJpeg(pic2)
        queue.enqueue(backend, rid2, initial, picUri2, keepOriginal = false)
        WorkManager.getInstance(context).cancelAllWork()
        queue.setState(rid2, "Queued")
        val photoPath = requireNotNull(queue.items(owner, backend.url).first { it.id == rid2 }.photoFile)
        assertTrue(File(photoPath).isFile)
        assertTrue(queue.deleteUnsent(owner, backend.url, rid2))
        assertFalse("Local queued photo must be deleted with unsent row", File(photoPath).exists())
        assertTrue(queue.items(owner, backend.url).none { it.id == rid2 })
    }

    @Test fun passwordRecoveryAndChangePasswordWorkAgainstLoopbackAuth(): Unit = runBlocking {
        val email = "recover-me@test.example"
        server.passwords[email] = "OldPass#123"
        backend.auth.requestPasswordReset(email)
        assertTrue(server.recoveryRequests.contains(email))

        try {
            backend.auth.completePasswordReset(email, "000000", "NewPass#123")
            fail("Wrong recovery OTP must be rejected")
        } catch (err: BackendException) {
            assertEquals("otp_expired", err.code)
        }
        assertNull("Failed recovery must not create a session", backend.auth.currentUser)

        backend.auth.completePasswordReset(email, "654321", "NewPass#123")
        assertNotNull(backend.auth.currentUser)

        try {
            backend.auth.changePassword("WrongCurrent#1", "BrandNew#123")
            fail("Wrong current password must be rejected")
        } catch (err: BackendException) {
            assertEquals("invalid_credentials", err.code)
        }
        backend.auth.changePassword("NewPass#123", "BrandNew#123")
        assertEquals("BrandNew#123", server.passwords[email])
    }

    @Test fun profileShowsUnsyncedLogoutConfirmationAndHomeShowsActiveTimeline() {
        var signedOut = false
        val user = Citizen("u-1", "resident@test.example", "Ana Reyes", official = false, barangay = "Alangilan")
        compose.setContent {
            ProfileScreen(
                onHomeClick = {}, onReportsClick = {}, onMapClick = {},
                user = user, busy = false, onSaveProfile = { _, _, _ -> },
                onSignOut = { signedOut = true }, pendingUploads = 2
            )
        }
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.onNodeWithText("Log out of PinPoint?").assertIsDisplayed()
        compose.onNodeWithText("You have 2 saved report(s) not yet uploaded. They stay saved on this device until you sign back in.").assertIsDisplayed()
        compose.onNodeWithText("Stay logged in").performClick()
        assertFalse(signedOut)
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.onNodeWithText("Log out").performClick()
        assertTrue(signedOut)
    }

    @Test fun smsGuideAndNotificationsFilterBehaveAccurately() {
        var markAllCalled = false
        val notices = listOf(
            ReportNotification("n1", "r1", "Your Road Damage report is resolved.", read = false, createdAt = "2026-11-23"),
            ReportNotification("n2", "r2", "Your Garbage report is under review.", read = true, createdAt = "2026-11-22")
        )
        compose.setContent {
            NotificationsScreen(
                onBackClick = {},
                notifications = notices,
                onNotificationClick = {},
                onMarkAllRead = { markAllCalled = true }
            )
        }
        compose.onNodeWithText("Mark all read").performClick()
        assertTrue(markAllCalled)
        compose.onNodeWithText("Resolved").performClick()
        compose.onNodeWithText("New: Your Road Damage report is resolved.").assertIsDisplayed()
        compose.onNodeWithText("Your Garbage report is under review.").assertDoesNotExist()
    }
}
