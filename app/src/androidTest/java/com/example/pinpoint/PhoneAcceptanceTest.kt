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
import com.example.pinpoint.data.ReportDraft
import com.example.pinpoint.data.ReportQueue
import com.example.pinpoint.data.ReportSyncWorker
import com.example.pinpoint.data.SupabaseBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * Phase driver for the physical-phone acceptance cohorts (backend/scripts/phone_acceptance.py). Inert unless `-e phase`.
 * Uses the REAL production backend and the REAL app queue/workers (no test seam). It never signs out the account that is
 * signed in when it starts: `signin` replaces the session in place, so the host driver can restore the owner's own saved
 * session files afterwards. Timings use the phone's own clock (durations only). Receipts go to files/acceptance-receipt.json.
 *
 * Phases: signin | enqueue | signout | cleanup-queue.
 */
@RunWith(AndroidJUnit4::class)
class PhoneAcceptanceTest {
    @get:Rule val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val receiptFile get() = File(context.filesDir, "acceptance-receipt.json")
    private fun receipt(update: JSONObject.() -> Unit) {
        val current = if (receiptFile.exists()) JSONObject(receiptFile.readText()) else JSONObject()
        receiptFile.writeText(current.apply(update).toString())
    }
    private fun backend() = requireNotNull(SupabaseBackend.create(context)).also {
        require(it.url.startsWith("https://") && it.url.endsWith(".supabase.co")) { "acceptance phases only run against the real HTTPS project" }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A decodable photo-like JPEG (~200 KB of block noise), optionally padded to a larger "original" with seeded random bytes. */
    private fun sourceFile(id: String, padTo: Int): Pair<File, ByteArray> {
        val bitmap = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        val random = Random(20261123L + id.hashCode())
        for (by in 0 until 960 step 8) for (bx in 0 until 1280 step 8) {
            val color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            for (y in by until by + 8) for (x in bx until bx + 8) bitmap.setPixel(x, y, color)
        }
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray(); bitmap.recycle()
        val bytes = if (padTo > jpeg.size) jpeg + ByteArray(padTo - jpeg.size).also { Random(7).nextBytes(it) } else jpeg
        return File(context.cacheDir, "acceptance-$id.jpg").also { it.writeBytes(bytes) } to bytes
    }

    @Test fun run() {
        val phase = args.getString("phase"); assumeTrue("inert without -e phase", phase != null)
        when (phase) {
            "signin" -> {
                val b = backend()
                runBlocking { b.auth.signIn(requireNotNull(args.getString("email")), requireNotNull(args.getString("password"))) }   // no signOut: the owner's session is saved by the host
                receiptFile.delete(); receipt { put("signed_in_user", requireNotNull(b.auth.currentUser).id) }
            }
            "enqueue" -> {
                val b = backend(); val owner = requireNotNull(b.auth.currentUser).id; val queue = ReportQueue.get(context)
                val id = requireNotNull(args.getString("id")); val marker = requireNotNull(args.getString("marker"))
                val original = args.getString("original") == "yes"
                val (file, bytes) = sourceFile(id, if (original) (args.getString("orig_bytes")?.toInt() ?: 9_000_000) else 0)
                receiptFile.delete()
                receipt { put("id", id); put("owner", owner); put("marker", marker); put("original", original); if (original) { put("original_sha256", sha(bytes)); put("original_bytes", bytes.size) } }
                val t0 = System.currentTimeMillis()
                runBlocking { queue.enqueue(b, id, ReportDraft("Road Damage", marker, "Alangilan phone acceptance", "Alangilan"), Uri.fromFile(file), keepOriginal = original) }
                val tAck = System.currentTimeMillis()
                val row = queue.items(owner, b.url).single { it.id == id }
                val rpcBody = JSONObject(mapOf("p_id" to id, "p_category" to "Road Damage", "p_description" to marker, "p_location" to "Alangilan phone acceptance",
                    "p_barangay" to "Alangilan", "p_latitude" to JSONObject.NULL, "p_longitude" to JSONObject.NULL, "p_photo_path" to "$owner/$id/photo.jpg")).toString().toByteArray().size
                receipt {
                    put("t_start_ms", t0); put("t_ack_ms", tAck); put("ack_duration_ms", tAck - t0)
                    put("queued_photo_bytes", row.photoFile?.let { File(it).length() } ?: 0L); put("rpc_body_bytes", rpcBody)
                    put("app_payload_bytes", (row.photoFile?.let { File(it).length() } ?: 0L) + rpcBody)
                    put("acked", true)
                }
                // Optionally stay alive until the REAL workers finish, timing the client-observed milestones.
                if (args.getString("wait") == "yes") {
                    val deadline = t0 + (args.getString("timeout_s")?.toLong() ?: 90L) * 1000
                    var syncedAt = 0L; var originalAt = 0L
                    while (System.currentTimeMillis() < deadline) {
                        val r = queue.items(owner, b.url).firstOrNull { it.id == id } ?: break
                        if (syncedAt == 0L && r.state == "Synced") {
                            syncedAt = System.currentTimeMillis()
                            val timing = ReportSyncWorker.recentTimings[id]
                            receipt {
                                put("t_synced_ms", syncedAt); put("client_submit_ms", syncedAt - t0)
                                if (timing != null) {
                                    put("worker_start_delay_ms", (timing.firstWorkerStartMs - t0).coerceAtLeast(0L))
                                    put("submit_exec_ms", ((timing.syncedMs ?: syncedAt) - timing.firstWorkerStartMs).coerceAtLeast(0L))
                                    put("worker_attempts", timing.attempts)
                                    if (timing.lastErrorKind != null) put("worker_last_error", timing.lastErrorKind)
                                }
                            }
                        }
                        if (!original && syncedAt != 0L) break
                        if (original && r.originalState == "Uploaded") { originalAt = System.currentTimeMillis(); receipt { put("t_original_done_ms", originalAt); put("original_total_ms", originalAt - t0) }; break }
                        Thread.sleep(150)
                    }
                    receipt { put("waited", true); put("synced", syncedAt != 0L); put("original_uploaded", originalAt != 0L) }
                    // Let ReportSyncWorker commit Result.success() to WorkManager before instrumentation ends the process,
                    // without affecting the already-recorded client_submit_ms timestamp.
                    if (!original && syncedAt != 0L) {
                        val finishDeadline = System.currentTimeMillis() + 2_000
                        while (System.currentTimeMillis() < finishDeadline) {
                            val infos = runCatching {
                                WorkManager.getInstance(context).getWorkInfosForUniqueWork("pinpoint-reports-$owner").get(1, TimeUnit.SECONDS)
                            }.getOrNull()
                            if (infos != null && infos.all { it.state.isFinished }) break
                            Thread.sleep(100)
                        }
                    }
                }
            }
            "signout" -> { val b = backend(); runBlocking { b.auth.signOut() }; receiptFile.delete() }
            "cleanup-queue" -> {
                val owner = requireNotNull(args.getString("owner")); val queue = ReportQueue.get(context); val b = backend()
                queue.writableDatabase.query("queue", arrayOf("photo_file", "original_file"), "owner_id=? and project=?", arrayOf(owner, b.url), null, null, null).use { c ->
                    while (c.moveToNext()) for (i in 0..1) c.getString(i)?.let { File(it).delete() } }
                queue.writableDatabase.delete("queue", "owner_id=? and project=?", arrayOf(owner, b.url))
                queue.writableDatabase.delete("drafts", "owner_id=? and project=?", arrayOf(owner, b.url))
                receiptFile.delete()
            }
            else -> error("unknown phase")
        }
    }
}
