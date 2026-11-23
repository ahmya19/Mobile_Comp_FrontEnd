package com.example.pinpoint

import com.example.pinpoint.data.BackendException
import com.example.pinpoint.data.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.TimeZone

class UiTextTest {
    private fun friendly(error: Throwable) = UiText.friendlyError(error)

    @Test fun serverStatusesBecomeActionableSentencesWithoutCodes() {
        assertEquals("Your session expired or you don't have permission for that. Sign in again.", friendly(BackendException(401, "PGRST301", "JWT expired")))
        assertEquals("Your session expired or you don't have permission for that. Sign in again.", friendly(BackendException(403, "UPLOAD_403", "Original photo transfer failed (403).")))
        assertEquals("The server had a problem. Please try again shortly.", friendly(BackendException(503, "HTTP_503", "unavailable")))
        assertEquals("The service is busy. Please wait a moment and try again.", friendly(BackendException(429, "HTTP_429", "slow down")))
        assertEquals("Confirm your email address first, then sign in.", friendly(BackendException(400, "email_not_confirmed", "Email not confirmed")))
    }

    @Test fun serverValidationMessagesAreKeptButStripOfCodesAndClassNames() {
        assertEquals("Illegal status transition.", friendly(BackendException(400, "22023", "Illegal status transition")))
        assertEquals("The resource already exists.", friendly(BackendException(400, "HTTP_400", "The resource already exists")))
    }

    @Test fun platformNetworkErrorsBecomeOneConnectionSentence() {
        val offline = "Can't reach the server. Check your connection and try again."
        assertEquals(offline, friendly(UnknownHostException("Unable to resolve host \"x.supabase.co\"")))
        assertEquals(offline, friendly(SocketTimeoutException("timeout")))
        assertEquals(offline, friendly(IOException("Broken pipe")))
    }

    @Test fun ourOwnConnectionSentencesSurvive() {
        assertEquals("Waiting for unmetered Wi-Fi to transfer the original photo.", friendly(IOException("Waiting for unmetered Wi-Fi to transfer the original photo.")))
        assertEquals("The server returned something other than data. Check the connection and try again.", friendly(IOException("The server returned something other than data. Check the connection and try again.")))
    }

    @Test fun ourOwnValidationSentencesShowWithoutTheClassName() {
        assertEquals("Cannot decode the selected image.", friendly(IllegalStateException("Cannot decode the selected image.")))
        assertEquals("Photo is too detailed. Choose a smaller photo.", friendly(IllegalArgumentException("Photo is too detailed. Choose a smaller photo.")))
    }

    @Test fun technicalOrEmptyMessagesFallBackToAGenericSentence() {
        assertEquals("Something went wrong. Please try again.", friendly(IllegalStateException()))
        assertEquals("Something went wrong. Please try again.", friendly(RuntimeException("java.lang.NullPointerException at Foo(Foo.kt:12)")))
        assertEquals("Something went wrong. Please try again.", UiText.humanize(null))
    }

    @Test fun storedDiagnosticStringsAreHumanizedToo() {
        assertEquals("The resource already exists.", UiText.humanize("BackendException: HTTP_400: The resource already exists"))
        assertEquals("Can't reach the server. Check your connection and try again.", UiText.humanize("SocketTimeoutException: timeout"))
        assertFalse(UiText.humanize("IllegalStateException: Saved photo is missing. This report needs attention.").contains("Exception"))
    }

    @Test fun timestampsBecomeReadableInTheDeviceZone() {
        val manila = TimeZone.getTimeZone("Asia/Manila")
        assertEquals("Nov 21, 2026, 9:21 PM", UiText.formatTimestamp("2026-11-21T13:21:22.170879+00:00", manila, Locale.US))
        assertEquals("Nov 21, 2026, 7:44 PM", UiText.formatTimestamp("2026-11-21 11:44:25.576538+00", manila, Locale.US))
        assertEquals("Nov 21, 2026, 1:21 PM", UiText.formatTimestamp("2026-11-21T13:21:22Z", TimeZone.getTimeZone("UTC"), Locale.US))
        assertEquals("Nov 22, 2026, 12:30 AM", UiText.formatTimestamp("2026-11-21T14:30:00-02:00", TimeZone.getTimeZone("Asia/Manila"), Locale.US).replace("Nov 22, 2026, 12:30 AM", "Nov 22, 2026, 12:30 AM"))
    }

    @Test fun unparseableTimestampsAreReturnedUnchanged() {
        assertEquals("yesterday", UiText.formatTimestamp("yesterday")); assertEquals("", UiText.formatTimestamp(null)); assertTrue(UiText.formatTimestamp("2026-13-99").isNotEmpty())
    }
}
