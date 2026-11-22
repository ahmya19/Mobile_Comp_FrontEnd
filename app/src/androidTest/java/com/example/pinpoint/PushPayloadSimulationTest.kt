package com.example.pinpoint

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.pinpoint.data.PinPointMessagingService
import com.example.pinpoint.data.SupabaseBackend
import com.example.pinpoint.data.cancelReportNotifications
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** SIMULATED delivery: payloads are handed straight to PinPointMessagingService (no Google FCM involved) because
 *  duplicate FCM delivery and a late payload after an account switch cannot be driven safely through the hosted route. */
@RunWith(AndroidJUnit4::class)
class PushPayloadSimulationTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: FakeSupabaseServer
    private lateinit var backend: SupabaseBackend
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun setup() {
        server = FakeSupabaseServer(); backend = SupabaseBackend.installForTesting(context, server.url, "sb_publishable_push_sim")
        cancelReportNotifications(context)
    }
    @After fun cleanup() {
        cancelReportNotifications(context); runCatching { runBlocking { backend.auth.signOut() } }
        SupabaseBackend.resetForTesting(); server.close()
    }

    private fun message(user: String, notification: String, report: String = UUID.randomUUID().toString()) =
        RemoteMessage.Builder("sim@fcm.googleapis.com").setMessageId(UUID.randomUUID().toString())
            .addData("userId", user).addData("reportId", report).addData("notificationId", notification)
            .addData("title", "Report updated").addData("body", "Your report is under review.").build()
    private fun tags() = manager.activeNotifications.filter { it.id == 0 }.mapNotNull { it.tag }.sorted()
    private fun await(expected: List<String>, label: String) {
        val end = System.currentTimeMillis() + 10_000
        while (tags() != expected && System.currentTimeMillis() < end) Thread.sleep(100)
        assertEquals(label, expected, tags())
    }
    private fun service() = PinPointMessagingService().also { ContextWrapperAttach.attach(it, context) }

    @Test fun theSameNotificationDeliveredTwiceIsOneTrayEntry() {
        runBlocking { backend.auth.signIn("a@sim.test", "x") }
        val a = server.userId("a@sim.test"); val svc = service(); val report = UUID.randomUUID().toString()
        svc.onMessageReceived(message(a, "n-1", report)); svc.onMessageReceived(message(a, "n-1", report))
        await(listOf("n-1"), "a duplicate payload replaces, not stacks")
        svc.onMessageReceived(message(a, "n-2", report))
        await(listOf("n-1", "n-2"), "distinct notifications for one report remain distinct")
    }

    @Test fun aLatePayloadForTheSignedOutAccountIsDroppedAfterSwitchingToAnother() {
        runBlocking { backend.auth.signIn("a@sim.test", "x") }
        val a = server.userId("a@sim.test"); val b = server.userId("b@sim.test")
        runBlocking { backend.auth.signOut() }; cancelReportNotifications(context)
        runBlocking { backend.auth.signIn("b@sim.test", "x") }
        val svc = service()
        svc.onMessageReceived(message(a, "late-for-a"))
        Thread.sleep(1500)
        assertEquals("account A's late payload must not appear for account B", emptyList<String>(), tags())
        svc.onMessageReceived(message(b, "for-b"))
        await(listOf("for-b"), "account B still receives its own")
    }

    @Test fun aPayloadWithNoSignedInAccountIsDropped() {
        val a = server.userId("a@sim.test")
        service().onMessageReceived(message(a, "no-session"))
        Thread.sleep(1500); assertEquals(emptyList<String>(), tags())
    }
}
