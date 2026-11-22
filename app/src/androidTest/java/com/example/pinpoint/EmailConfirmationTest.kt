package com.example.pinpoint

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pinpoint.data.*
import com.example.pinpoint.screens.EmailConfirmationScreen
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fake Auth responses test session boundaries without sending email. One test
 * separately checks rejection at the real hosted verification endpoint. */
@RunWith(AndroidJUnit4::class)
class EmailConfirmationTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val backend get()=requireNotNull(SupabaseBackend.create(context))
    private val store get()=SessionStore(context,"pinpoint_confirmation_test")
    private val pendingKey get()="${backend.url}#pending-confirmation"
    private val email="confirmation-check@example.invalid"

    @Before fun reset() { store.clear(backend.url);store.clear(pendingKey) }
    @After fun clean() { store.clear(backend.url);store.clear(pendingKey) }

    @Test fun signupRestoresPendingEmailWithoutPasswordOrSession(): Unit=runBlocking {
        val calls=mutableListOf<Pair<String,JsonObject>>()
        val auth=SupabaseAuth(backend,store) { path,body ->
            calls+=path to backend.gson.toJsonTree(body).asJsonObject
            JsonObject()
        }
        assertFalse(auth.register("Test Citizen"," $email ","local-only-password"))
        assertNull(auth.currentUser)
        assertNull(store.read(backend.url))
        assertEquals("auth/v1/signup",calls.single().first)
        assertEquals(email,calls.single().second["email"].asString)
        val persisted=JsonParser.parseString(requireNotNull(store.read(pendingKey))).asJsonObject
        assertEquals(setOf("email","resend_available_at"),persisted.keySet())
        val restored=SupabaseAuth(backend,store) { _,_ -> error("Restoration must not contact Auth") }
        assertEquals(email,restored.confirmation.value?.email)
        assertTrue(requireNotNull(restored.confirmation.value).resendAvailableAt>System.currentTimeMillis())
        assertNull(restored.currentUser)
    }

    @Test fun onlySuccessfulVerificationCreatesAndRestoresSession(): Unit=runBlocking {
        var calls=0
        var accept=false
        val auth=SupabaseAuth(backend,store) { path,body ->
            calls++
            assertEquals("auth/v1/verify",path)
            val payload=backend.gson.toJsonTree(body).asJsonObject
            assertEquals(email,payload["email"].asString)
            assertEquals("email",payload["type"].asString)
            assertEquals("123456",payload["token"].asString)
            if(!accept) throw BackendException(403,"otp_expired","Token has expired or is invalid")
            JsonParser.parseString("""{"access_token":"test-access","refresh_token":"test-refresh","expires_in":3600,
                "user":{"id":"test-user","email":"$email","user_metadata":{"display_name":"Test Citizen"}}}""").asJsonObject
        }
        auth.enterConfirmation(email)
        try { auth.verifyEmail("12345");fail("Short code accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(0,calls)
        try { auth.verifyEmail("123456");fail("Invalid OTP accepted") } catch (error: BackendException) {
            assertEquals("otp_expired: Token has expired or is invalid",error.message)
        }
        assertNull(auth.currentUser)
        assertNull(store.read(backend.url))
        assertEquals(email,auth.confirmation.value?.email)
        accept=true
        auth.verifyEmail("123456")
        assertEquals("test-user",auth.currentUser?.id)
        assertNull(auth.confirmation.value)
        assertNull(store.read(pendingKey))
        val restored=SupabaseAuth(backend,store) { _,_ -> error("No request expected") }
        assertEquals("test-user",restored.currentUser?.id)
        assertEquals("test-access",restored.accessToken())
        assertFalse(requireNotNull(store.read(backend.url)).contains("123456"))
    }

    @Test fun resendHonorsServerCooldownAcrossRestoration(): Unit=runBlocking {
        var calls=0
        val post: suspend (String,Any)->JsonObject={ path,body ->
            calls++
            assertEquals("auth/v1/resend",path)
            assertEquals("signup",backend.gson.toJsonTree(body).asJsonObject["type"].asString)
            throw BackendException(429,"over_email_send_rate_limit","Email rate limit exceeded",180)
        }
        val auth=SupabaseAuth(backend,store,post)
        auth.enterConfirmation(email)
        try { auth.resendConfirmation();fail("Expected rate limit") } catch (_: BackendException) { }
        assertTrue(requireNotNull(auth.confirmation.value).resendAvailableAt>=System.currentTimeMillis()+179_000)
        val restored=SupabaseAuth(backend,store,post)
        try { restored.resendConfirmation();fail("Cooldown ignored") } catch (_: IllegalStateException) { }
        assertEquals(1,calls)
        restored.cancelConfirmation()
        assertNull(SupabaseAuth(backend,store,post).confirmation.value)
    }

    @Test fun unconfirmedPasswordLoginRequestsConfirmationWithoutSession(): Unit=runBlocking {
        val auth=SupabaseAuth(backend,store) { _,_ ->
            throw BackendException(400,"email_not_confirmed","Email not confirmed")
        }
        try { auth.signIn(email,"local-only-password");fail("Expected confirmation requirement") } catch (_: BackendException) { }
        assertNull(auth.currentUser)
        assertEquals(email,auth.confirmation.value?.email)
    }

    @Test fun codeScreenValidatesClearsCodeAndDisablesBusyActions() {
        val busy=mutableStateOf(false)
        var verified:String?=null
        var cancelled=false
        compose.setContent { EmailConfirmationScreen(PendingEmailConfirmation(email),busy.value,
            {verified=it},{},{cancelled=true}) }
        compose.onNodeWithText("Verify email").assertIsNotEnabled()
        compose.onNodeWithText("Confirmation code").performTextInput("12a345")
        compose.onNodeWithText("Verify email").assertIsNotEnabled()
        compose.onNodeWithText("Confirmation code").performTextInput("6")
        compose.onNodeWithText("Verify email").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("123456",verified) }
        compose.onNodeWithText("Confirmation code").assert(hasText("",substring=false))
        compose.onNodeWithText("Verify email").assertIsNotEnabled()
        compose.runOnIdle { busy.value=true }
        compose.onNodeWithText("Resend code").assertIsNotEnabled()
        compose.onNodeWithText("Use another email / Sign in").assertIsNotEnabled()
        compose.runOnIdle { busy.value=false }
        compose.onNodeWithText("Use another email / Sign in").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun hostedEndpointRejectsInvalidCodeWithoutSession(): Unit=runBlocking {
        try {
            backend.json("auth/v1/verify","POST",mapOf("email" to email,"token" to "000000","type" to "email"),false)
            fail("Hosted Auth accepted an invalid code")
        } catch (error: BackendException) {
            assertEquals("otp_expired",error.code)
            assertTrue(error.status in 400..499)
        }
        assertNull(store.read(backend.url))
    }
}
