package com.example.pinpoint

import com.example.pinpoint.data.PasswordPolicy
import com.example.pinpoint.data.SmsFormatHelper
import com.example.pinpoint.screens.shortIssueCode
import org.junit.Assert.*
import org.junit.Test

class UiHelpersTest {
    @Test fun passwordPolicyClassifiesStrengthAccurately() {
        val weak = PasswordPolicy.evaluate("abc")
        assertEquals(0, weak.score)
        assertEquals("Weak password", weak.label)

        val fair = PasswordPolicy.evaluate("abcdefgh")
        assertTrue(fair.minLength)
        assertFalse(fair.hasUpperAndLower)
        assertEquals(1, fair.score)

        val good = PasswordPolicy.evaluate("Abcdefg1")
        assertEquals(3, good.score)
        assertEquals("Good password", good.label)

        val strong = PasswordPolicy.evaluate("Abcdef1!")
        assertEquals(4, strong.score)
        assertEquals("Strong password", strong.label)
    }

    @Test fun smsFormatHelperMatchesDeployedParserAndGsm7Rules() {
        val msg = SmsFormatHelper.buildMessage("road", "Alangilan", "Big pothole; near school")
        assertEquals("PIN ROAD;Alangilan;Big pothole, near school", msg)
        assertEquals(msg.length, SmsFormatHelper.gsm7Septets(msg))
        assertTrue(SmsFormatHelper.gsm7Septets(msg) <= 160)

        // Extended GSM-7 characters count as 2 septets each.
        assertEquals(2, SmsFormatHelper.gsm7Septets("€"))
        assertEquals(2, SmsFormatHelper.gsm7Septets("["))

        // Non-GSM characters (e.g., emoji or curly quotes) return -1.
        assertEquals(-1, SmsFormatHelper.gsm7Septets("PIN ROAD;Alangilan;Pothole \uD83D\uDEA7"))
    }

    @Test fun shortIssueCodeProducesStableTag() {
        assertEquals("#PP-ABCDEF", shortIssueCode("abcdef12-3456-4789-abcd-000000000001"))
        assertEquals("#PP-LOCAL", shortIssueCode(""))
    }
}
