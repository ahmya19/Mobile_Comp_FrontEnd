package com.example.pinpoint

import com.example.pinpoint.data.notificationTargetAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRoutingTest {
    @Test fun onlyTheRecipientAccountMayOpenANotification() {
        assertTrue(notificationTargetAllowed("user-a", "user-a"))
        assertFalse("another signed-in account", notificationTargetAllowed("user-a", "user-b"))
        assertFalse("nobody signed in", notificationTargetAllowed("user-a", null))
        assertFalse("legacy intent without a recipient", notificationTargetAllowed(null, "user-a"))
        assertFalse("blank recipient", notificationTargetAllowed("", ""))
    }
}
