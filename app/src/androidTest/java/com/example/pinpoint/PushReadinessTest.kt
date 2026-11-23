package com.example.pinpoint

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pinpoint.data.SupabaseBackend
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Opt-in read-only diagnostic; private receipt contains booleans and a token digest, never credentials. */
@RunWith(AndroidJUnit4::class)
class PushReadinessTest {
    @Test fun inspect() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("push_readiness") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = requireNotNull(SupabaseBackend.create(context))
        if (args.getString("refresh_token") == "true") {
            check(backend.auth.currentUser == null) { "Refresh diagnostics require a signed-out owned installation" }
            Tasks.await(FirebaseMessaging.getInstance().deleteToken(), 45, TimeUnit.SECONDS)
        }
        val token = Tasks.await(FirebaseMessaging.getInstance().token, 45, TimeUnit.SECONDS)
        val digest = MessageDigest.getInstance("MD5").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        val result = JSONObject().apply {
            put("expected_user_matches", backend.auth.currentUser?.id == args.getString("expected_user"))
            put("configured_backend_matches", backend.url == BuildConfig.SUPABASE_URL)
            put("notifications_enabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
            put("permission_granted", ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            put("channel_importance", context.getSystemService(NotificationManager::class.java).getNotificationChannel("report_updates")?.importance ?: -1)
            put("token_md5", digest)
        }
        context.filesDir.resolve("push-readiness.json").writeText(result.toString())
    }
}
