package com.example.pinpoint

import android.Manifest
import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.pinpoint.data.PinPointRepository
import com.example.pinpoint.data.ReportQueue
import com.example.pinpoint.data.SupabaseBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Runs the existing screens with both demo roles on one isolated emulator.
 * Only the system photo-picker result is stubbed; Auth, Storage, SQL and jobs are live. */
@RunWith(AndroidJUnit4::class)
class WorkflowRehearsalTest {
    @get:Rule(order=0) val permission=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order=1) val compose=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val saved get()=context.getSharedPreferences("checkpoint_rehearsal",0)
    private val backend get()=requireNotNull(SupabaseBackend.create(context))

    @Before fun launch() {
        Intents.init()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @After fun close() {
        instrumentation.runOnMainSync {
            val monitor=ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED,Stage.PAUSED,Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it).toList() }
                .distinct().forEach { it.finish() }
        }
        Intents.release()
    }

    @Test fun twoAccountWorkflow(): Unit=runBlocking {
        when(args.getString("phase","full")) {
            "full"->{ citizenCreates();officialUpdates();citizenReviews() }
            "citizen"->citizenCreates()
            "official"->officialUpdates()
            "review"->citizenReviews()
            else->error("Unknown rehearsal phase")
        }
        stage("passed")
    }

    private fun stage(value: String) {
        saved.edit().putString("stage",value).commit()
        println("CP16_STAGE=$value")
    }

    private fun signIn(role: String) {
        stage("$role login")
        if(backend.auth.currentUser!=null) {
            waitText("Quick Report")
            compose.onNodeWithText("Profile").performClick()
            compose.onNodeWithText("Sign out").performClick()
        }
        compose.waitUntil(45000) {
            compose.onAllNodesWithText("Sign in to your community").fetchSemanticsNodes(false).isNotEmpty() ||
                compose.onAllNodesWithText("Skip").fetchSemanticsNodes(false).isNotEmpty()
        }
        if (compose.onAllNodesWithText("Skip").fetchSemanticsNodes(false).isNotEmpty()) {
            compose.onNodeWithText("Skip").performClick()
        }
        waitText("Sign in to your community")
        compose.onNodeWithText("Email").performTextReplacement(requireNotNull(args.getString("${role}_email")))
        compose.onNodeWithText("Password").performTextReplacement(requireNotNull(args.getString("${role}_password")))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithText("Sign in").performScrollTo().assertIsEnabled().performClick()
        waitText("Quick Report",60000)
    }

    private suspend fun citizenCreates() {
        signIn("citizen")
        assertFalse(requireNotNull(PinPointRepository(backend).citizen()).official)
        stage("citizen form")
        val description="CP16 ${UUID.randomUUID()}: water service issue."
        saved.edit().putString("description",description).commit()
        val image=File(context.cacheDir,"cp16-picker.jpg")
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(100,80,190))
        image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)) };bitmap.recycle()
        intending(hasAction(Intent.ACTION_GET_CONTENT)).respondWith(ActivityResult(Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(image))))

        compose.onNodeWithText("Quick Report").performScrollTo().performClick()
        waitText("Select a Category",15000)
        compose.onNodeWithText("Select a Category").performClick()
        compose.onNodeWithText("Water Interruption").performClick()
        enter("Description",description)
        enter("Location","CP16 simulated test location")
        enter("Barangay","Alangilan")
        enter("Latitude (optional)","13.78")
        enter("Longitude (optional)","121.07")
        compose.onNodeWithText("Add Photo").performScrollTo().performClick()
        compose.onNodeWithText("Photo selected successfully").assertExists()
        compose.onNodeWithText("Submit Report").performScrollTo().performClick()
        waitText("View My Reports")
        compose.onNodeWithText("View My Reports").performClick()
        stage("waiting for report sync")
        compose.waitUntil(60000) {
            compose.onAllNodes(hasText(description.take(80)) and hasText("Pending")).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
        }
        val row=ReportQueue.get(context).items(requireNotNull(backend.auth.currentUser).id,backend.url)
            .single { it.draft.description==description }
        assertEquals("Synced",row.state)
        saved.edit().putString("report_id",row.id).commit()
        compose.onNodeWithText(description.take(80)).performClick()
        waitText("Report Details")
        compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("Report photo").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        compose.onNodeWithText("Official controls").assertDoesNotExist()
        compose.onNodeWithText("Back to My Reports").performScrollTo().performClick()
        compose.onNodeWithText("Home").performClick()
    }

    private suspend fun officialUpdates() {
        signIn("official")
        assertTrue(requireNotNull(PinPointRepository(backend).citizen()).official)
        val description=requireNotNull(saved.getString("description",null))
        stage("official report details")
        compose.onNodeWithText("View community reports").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText(description.take(80)).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        compose.onNodeWithText(description.take(80)).performClick()
        waitText("Official controls")
        compose.onNodeWithText("Status: Pending").performScrollTo().performClick()
        compose.onNodeWithText("Under Review").performClick()
        compose.onNodeWithText("Save status").performScrollTo().performClick()
        stage("waiting for official update")
        waitText("Status: Under Review | Priority: Low")
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Pending → Under Review",substring=true)).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        compose.onNodeWithText("Back to My Reports").performScrollTo().performClick()
        compose.onNodeWithText("Home").performClick()
    }

    private suspend fun citizenReviews() {
        signIn("citizen")
        stage("citizen notification")
        val id=requireNotNull(saved.getString("report_id",null))
        val description=requireNotNull(saved.getString("description",null))
        val before=backend.json("rest/v1/notifications?report_id=eq.$id&select=*").asJsonArray
        assertEquals("A single status update must create one notice",1,before.size())
        compose.onNodeWithContentDescription("Notifications").performClick()
        val message="New: Your Water Interruption report is under review."
        waitText(message)
        compose.onAllNodesWithText(message)[0].performClick()
        waitText("Description: $description")
        waitText("Status: Under Review | Priority: Low")
        var read=false
        for(attempt in 0 until 30) {
            read=backend.json("rest/v1/notifications?report_id=eq.$id&select=read").asJsonArray[0].asJsonObject["read"].asBoolean
            if(read) break
            delay(500)
        }
        assertTrue("Opening the notice must persist its read state",read)
        stage("citizen verified status and notification")
    }

    private fun enter(label: String,value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextInput(value)
    }
    private fun waitText(text: String,timeoutMillis: Long=30000) {
        try {
            compose.waitUntil(timeoutMillis) { compose.onAllNodesWithText(text,substring=false).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        } catch(failure: androidx.compose.ui.test.ComposeTimeoutException) {
            var tree=runCatching { compose.onRoot().printToString() }.getOrDefault("No UI root available")
            for(role in listOf("citizen","official")) for(field in listOf("email","password")) {
                args.getString("${role}_$field")?.takeIf { it.isNotEmpty() }?.let { tree=tree.replace(it,"[redacted $field]") }
            }
            throw AssertionError("Timed out waiting for $text. Redacted UI: $tree",failure)
        }
    }
}
