package com.example.pinpoint

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import com.example.pinpoint.data.PinPointRepository
import com.example.pinpoint.data.ReportDraft
import com.example.pinpoint.data.ReportQueue
import com.example.pinpoint.data.SupabaseBackend
import com.example.pinpoint.data.WifiOriginals
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.UUID

/**
 * Opt-in REAL Wi-Fi-bound HTTPS TUS smoke against the deployed Supabase Storage endpoint (Batch 1A).
 * Does NOT install WifiOriginals.installForTesting or SupabaseBackend.installForTesting: uses the real
 * WifiLink pinned to unmetered Wi-Fi, HTTPS port 443, and real Supabase TUS POST/PATCH/HEAD + attach_original.
 * Skipped unless -e allowHostedOriginalTusSmoke true is supplied by the guarded host driver.
 */
@RunWith(AndroidJUnit4::class)
class HostedOriginalTusSmokeTest {
    @get:Rule val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val tempFiles = mutableListOf<File>()

    @Before fun checkOptIn() {
        assumeTrue("Opt-in hosted TUS smoke disabled by default.", args.getString("allowHostedOriginalTusSmoke") == "true")
        WifiOriginals.resetForTesting()
        SupabaseBackend.resetForTesting()
    }

    @After fun cleanupLocal() {
        tempFiles.forEach { it.delete() }
        runCatching { WorkManager.getInstance(context).cancelAllWork() }
    }

    private class SimulatedChunkInterrupt(val ackedOffset: Long) : RuntimeException("interrupted after $ackedOffset bytes")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun realWifiTusUploadInterruptResumeAndVerifyHash(): Unit = runBlocking {
        val email = requireNotNull(args.getString("fixture_email")) { "fixture_email required" }
        val password = requireNotNull(args.getString("fixture_password")) { "fixture_password required" }
        val marker = requireNotNull(args.getString("marker")) { "marker required" }
        val extraBytes = args.getString("extra_bytes")?.toIntOrNull() ?: (6_500_000)

        val backend = requireNotNull(SupabaseBackend.create(context)) { "Production Supabase config missing" }
        assertTrue("Must hit real HTTPS Supabase", backend.url.startsWith("https://") && backend.url.endsWith(".supabase.co"))

        val queue = ReportQueue.get(context)
        val reportId = UUID.randomUUID().toString()
        var ownerId = ""

        try {
            // Ensure clean fixture citizen sign-in.
            if (backend.auth.currentUser != null) runCatching { backend.auth.signOut() }
            backend.auth.signIn(email, password)
            ownerId = requireNotNull(backend.auth.currentUser).id

            // Generate a valid decodable synthetic JPEG padded past one 6 MiB TUS chunk (~6.5 MiB total).
            val headerJpeg = ByteArrayOutputStream().also { out ->
                Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(90, 47, 166))
                }.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }.toByteArray()
            val originalBytes = headerJpeg + ByteArray(extraBytes).also { Random(20261123L).nextBytes(it) }
            val originalSha = sha256(originalBytes)
            val srcFile = File(context.cacheDir, "tus-smoke-$reportId.jpg").also {
                it.writeBytes(originalBytes)
                tempFiles += it
            }

            // Enqueue locally (creates compressed copy <=250KB + private original copy), then cancel WorkManager
            // so this test deterministically controls the interrupt-after-chunk-1 boundary.
            val draft = ReportDraft(
                category = "Road Damage",
                description = marker,
                location = "Alangilan TUS smoke",
                barangay = "Alangilan"
            )
            queue.enqueue(backend, reportId, draft, Uri.fromFile(srcFile), keepOriginal = true)
            WorkManager.getInstance(context).cancelAllWork()

            val initialRow = queue.items(ownerId, backend.url).single { it.id == reportId }
            val compressedBytes = File(requireNotNull(initialRow.photoFile)).readBytes()
            assertTrue("Compressed JPEG must be <= 256000 bytes", compressedBytes.size in 1..256000)

            // Record identity receipt BEFORE any hosted write so cleanup always knows the exact report/paths even on failure.
            File(context.filesDir, "tus-smoke-receipt.json").writeText(
                JSONObject()
                    .put("report_id", reportId)
                    .put("owner_id", ownerId)
                    .put("marker", marker)
                    .put("photo_path", "$ownerId/$reportId/photo.jpg")
                    .put("original_path", "$ownerId/$reportId/original")
                    .put("phase", "pre-submit")
                    .toString()
            )

            // Commit the report + compressed photo through the real repository path.
            PinPointRepository(backend).submit(reportId, draft, compressedBytes, ownerId)
            queue.setState(reportId, "Synced")
            File(requireNotNull(initialRow.photoFile)).delete()

            // Phase 1: Real Wi-Fi-bound HTTPS TUS upload -> abort immediately after first 6 MiB chunk is acknowledged.
            var interruptedOffset = -1L
            val t0 = System.currentTimeMillis()
            try {
                WifiOriginals.create(context, backend).upload(
                    queue.items(ownerId, backend.url).single { it.id == reportId },
                    onProgress = { acked ->
                        if (acked >= 6L * 1024 * 1024 && acked < originalBytes.size.toLong()) {
                            throw SimulatedChunkInterrupt(acked)
                        }
                    },
                    saveSession = { url -> queue.saveUploadSession(reportId, url) }
                )
                fail("Expected interruption after first 6 MiB chunk")
            } catch (interrupted: SimulatedChunkInterrupt) {
                interruptedOffset = interrupted.ackedOffset
            }
            assertEquals(6L * 1024 * 1024, interruptedOffset)

            // Verify persisted upload session survived in SQLite and points at expected *.storage.supabase.co host.
            val midRow = queue.items(ownerId, backend.url).single { it.id == reportId }
            val savedSession = requireNotNull(midRow.uploadUrl) { "Resumable session URL must persist in SQLite" }
            assertTrue("Saved session must be HTTPS on storage.supabase.co",
                savedSession.startsWith("https://") && savedSession.contains(".storage.supabase.co/storage/v1/upload/resumable/"))

            // Phase 2: Fresh WifiOriginals instance resumes from persisted SQLite row via real TUS HEAD -> PATCH remainder.
            val resumedOffsets = mutableListOf<Long>()
            WifiOriginals.create(context, backend).upload(
                midRow,
                onProgress = { resumedOffsets += it },
                saveSession = { url -> queue.saveUploadSession(reportId, url) }
            )
            assertEquals("Resume must complete with exact file length", listOf(originalBytes.size.toLong()), resumedOffsets)

            // Attach original via real RPC (twice to verify idempotency!).
            backend.json("rest/v1/rpc/attach_original", "POST", mapOf("p_report_id" to reportId), expectedUserId = ownerId)
            backend.json("rest/v1/rpc/attach_original", "POST", mapOf("p_report_id" to reportId), expectedUserId = ownerId)
            queue.setOriginalState(reportId, "Uploaded")
            queue.saveUploadSession(reportId, null)
            val elapsedMs = System.currentTimeMillis() - t0

            // Purge local original and verify it is gone.
            queue.purgeSyncedPhotos(ownerId, backend.url)
            assertFalse("Local original copy must be purged after attach", File(requireNotNull(midRow.originalFile)).exists())

            // Real Wi-Fi private download & byte-for-byte SHA-256 verification.
            val downloaded = WifiOriginals.create(context, backend).download("$ownerId/$reportId/original")
            val downloadedSha = sha256(downloaded)
            assertEquals("Downloaded original bytes must match local SHA-256", originalSha, downloadedSha)

            // Write receipt (identifiers & hashes only, never tokens or full upload URL).
            val receipt = JSONObject()
                .put("report_id", reportId)
                .put("owner_id", ownerId)
                .put("marker", marker)
                .put("compressed_bytes", compressedBytes.size)
                .put("original_bytes", originalBytes.size)
                .put("original_sha256", originalSha)
                .put("downloaded_sha256", downloadedSha)
                .put("sha_match", originalSha == downloadedSha)
                .put("interrupted_offset", interruptedOffset)
                .put("resumed_final_offset", resumedOffsets.last())
                .put("elapsed_ms", elapsedMs)
            File(context.filesDir, "tus-smoke-receipt.json").writeText(receipt.toString())
        } finally {
            // Remove local SQLite row for this smoke report and sign out fixture session locally.
            if (ownerId.isNotBlank()) {
                queue.writableDatabase.delete("queue", "id=? and owner_id=?", arrayOf(reportId, ownerId))
            }
            runCatching { backend.auth.signOut() }
        }
    }
}
