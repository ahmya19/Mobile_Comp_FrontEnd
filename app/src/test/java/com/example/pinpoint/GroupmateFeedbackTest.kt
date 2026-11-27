package com.example.pinpoint

import com.example.pinpoint.data.ProfileInput
import com.example.pinpoint.data.SmsFormatHelper
import com.example.pinpoint.data.UiText
import com.example.pinpoint.data.barangayMatches
import com.example.pinpoint.data.barangayPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-56: regressions reported by the two groupmates' testing round. */
class GroupmateFeedbackTest {
    @Test fun pastedPinPrefixIsNotDuplicatedInTheSmsPreview() {
        // The exact text from the report: the tester typed the whole format into the details box.
        val msg = SmsFormatHelper.buildMessage("ROAD", "Alangilan", "PIN ROAD;Alangilan;hello test test")
        assertEquals("PIN ROAD;Alangilan;hello test test", msg)
        assertEquals(34, SmsFormatHelper.gsm7Septets(msg))
    }

    @Test fun commaFormAndOtherCategoryPrefixesAreAlsoStripped() {
        assertEquals("hello test test", SmsFormatHelper.stripPinPrefix("pin road,Alangilan,hello test test"))
        assertEquals("Large pothole near the hall", SmsFormatHelper.stripPinPrefix("PIN FLOOD; Alangilan ; Large pothole near the hall"))
    }

    @Test fun plainDescriptionsAreLeftAlone() {
        assertEquals("Large pothole near Alangilan barangay hall", SmsFormatHelper.stripPinPrefix("  Large pothole near Alangilan barangay hall "))
        assertEquals("PIN is broken near the gate", SmsFormatHelper.stripPinPrefix("PIN is broken near the gate"))
    }

    @Test fun barangayFilterIgnoresCaseAndAcceptsPartialNames() {
        assertTrue(barangayMatches("Alangilan", "alangilan"))
        assertTrue(barangayMatches("Alangilan", "  ALANG "))
        assertTrue(barangayMatches("Alangilan", ""))
        assertTrue(barangayMatches("Alangilan", null))
        assertFalse(barangayMatches("Kayumanggi", "Alangilan"))
    }

    @Test fun barangayPatternEscapesLikeWildcardsTypedByTheUser() {
        assertEquals("*Alangilan*", barangayPattern(" Alangilan "))
        assertEquals("*a\\%b*", barangayPattern("a%b"))
        assertEquals("*a\\_b*", barangayPattern("a_b"))
        assertEquals("*ab*", barangayPattern("a*b"))
    }

    @Test fun offlineFeedErrorShowsAFriendlySentenceNotTheHostName() {
        val raw = "UnknownHostException: Unable to resolve host \"example.supabase.co\": No address associated with hostname"
        val shown = UiText.humanize(raw)
        assertEquals("Can't reach the server. Check your connection and try again.", shown)
        assertFalse(shown.contains("supabase"))
        // Applying it again to an already-friendly sentence changes nothing.
        assertEquals(shown, UiText.humanize(shown))
    }

    @Test fun profileContactNumberIsNormalizedAndValidated() {
        assertEquals("09171234567", ProfileInput.contact("0917 123-4567"))
        assertEquals("+639171234567", ProfileInput.contact(" +63 917 123 4567 "))
        assertEquals("0431234567", ProfileInput.contact("(043) 123 4567"))
        assertEquals(null, ProfileInput.contact("   "))
        for (bad in listOf("abc", "12345", "09171234567890123", "0917-ABC-4567")) {
            val failure = runCatching { ProfileInput.contact(bad) }.exceptionOrNull()
            assertTrue("$bad should be rejected", failure is IllegalArgumentException)
        }
    }

    @Test fun profileLocationIsTrimmedAndBounded() {
        assertEquals("Purok 3, Alangilan", ProfileInput.location("  Purok 3,   Alangilan "))
        assertEquals(null, ProfileInput.location(""))
        assertTrue(runCatching { ProfileInput.location("x") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ProfileInput.location("a".repeat(121)) }.exceptionOrNull() is IllegalArgumentException)
    }
}
