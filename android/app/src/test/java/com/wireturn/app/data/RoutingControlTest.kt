package com.wireturn.app.data

import org.junit.Assert.*
import org.junit.Test

class RoutingControlTest {
    @Test fun profilesCannotDisableTheRequiredRoutingLayer() {
        assertTrue(XrayConfig(enabled = false).isRequired)
        assertTrue(XrayConfig(enabled = true).isRequired)
        assertFalse(XrayConfig(enabled = false, routingEnabled = false).isRequired)
        assertTrue(XrayConfig(enabled = true, routingEnabled = false).isRequired)
    }

    @Test fun finalConfirmationRequiresFirstStepAndRejectsDoubleTap() {
        val flow = RoutingDisableConsent.Confirmation()
        assertNull(flow.confirm(1000))
        flow.continueToSecondStep(1000)
        assertNull(flow.confirm(1001))
        assertNull(flow.confirm(1649))
        val grant = flow.confirm(1650)!!
        assertNull(flow.confirm(2000))
        assertTrue(grant.consume())
        assertFalse(grant.consume())
    }

    @Test fun cancelOrBackgroundingInvalidatesPendingConfirmation() {
        val flow = RoutingDisableConsent.Confirmation()
        flow.continueToSecondStep(1000)
        flow.cancel()
        assertNull(flow.confirm(2000))
        flow.continueToSecondStep(2100)
        assertNotNull(flow.confirm(2750))
    }
}
