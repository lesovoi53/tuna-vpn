package com.wireturn.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePermissionPromptsTest {
    @Test fun powerAllowlistCheckAloneIsNotAMissingPermissionError() {
        // OEM "No restrictions" is not represented by the Android Doze API.
        // Its false result must not claim that the user denied a permission.
        val prompts = HomePermissionPrompts.resolve(true, false, false)
        assertTrue(prompts.showBatteryHint)
        assertFalse(prompts.showNotificationsHint)
        assertFalse(prompts.isError)
    }

    @Test fun dismissingBatteryAdviceDoesNotHideMissingNotificationsPermission() {
        val prompts = HomePermissionPrompts.resolve(false, false, true)
        assertFalse(prompts.showBatteryHint)
        assertTrue(prompts.showNotificationsHint)
        assertTrue(prompts.isError)
    }

    @Test fun configuredSystemNeedsNoPrompt() {
        assertFalse(HomePermissionPrompts.resolve(true, true, false).visible)
    }

    @Test fun batteryAdviceCanBeDismissedWhenOemReportsNoExemption() {
        assertFalse(HomePermissionPrompts.resolve(true, false, true).visible)
    }

    @Test fun closingNotificationsDoesNotAcknowledgeBatteryAdvice() {
        val prompts = HomePermissionPrompts.resolve(false, false, false, true)
        assertTrue(prompts.showBatteryHint)
        assertFalse(prompts.showNotificationsHint)
        assertFalse(prompts.isError)
    }
}
